package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Separately gated System One rollout; the historical trajectory policy has its own deployment. */
@Component
public final class SystemOneRoutingDeployment {

    private final Mode mode;
    private final int canaryPercent;
    private final String releaseVersion;
    private final String expectedModel;
    private final SystemOneRolloutGuard rolloutGuard;

    public enum Mode { OFF, SHADOW, CANARY, ACTIVE }

    @org.springframework.beans.factory.annotation.Autowired
    public SystemOneRoutingDeployment(
            @Value("${agent.decision.system-one.deployment.mode:OFF}") String mode,
            @Value("${agent.decision.system-one.deployment.canary-percent:0}") int canaryPercent,
            @Value("${agent.decision.system-one.deployment.release-version:}") String releaseVersion,
            @Value("${agent.decision.system-one.deployment.expected-model:}") String expectedModel,
            @Value("${agent.decision.system-one.deployment.manifest-path:}") String manifestPath,
            @Value("${agent.decision.system-one.multi-agent-threshold:0.75}") double threshold,
            @Value("${agent.decision.system-one.enabled:false}") boolean advisorEnabled,
            @Value("${agent.decision.system-one.mode:SHADOW}") String advisorMode,
            @Value("${agent.decision.system-one.comparison.enabled:false}") boolean comparisonEnabled,
            @Value("${agent.rag.multi-agent.enabled:true}") boolean multiAgentEnabled,
            @Value("${agent.decision.system-one.deployment.storage-directory:tmp/system-one-rollout}")
            String storageDirectory,
            @Value("${agent.decision.system-one.deployment.max-consecutive-failures:3}")
            int maxConsecutiveFailures,
            @Value("${agent.decision.system-one.deployment.minimum-selected-for-rate:20}")
            int minimumSelectedForRate,
            @Value("${agent.decision.system-one.deployment.max-failure-rate:0.10}")
            double maxFailureRate) {
        this(Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT)), canaryPercent,
                releaseVersion, expectedModel, manifestPath, threshold, advisorEnabled,
                advisorMode, comparisonEnabled, multiAgentEnabled,
                liveGuard(mode, storageDirectory, releaseVersion, maxConsecutiveFailures,
                        minimumSelectedForRate, maxFailureRate));
    }

    SystemOneRoutingDeployment(Mode mode, int canaryPercent, String releaseVersion,
                               String expectedModel, String manifestPath, double threshold,
                               boolean advisorEnabled) {
        this(mode, canaryPercent, releaseVersion, expectedModel, manifestPath,
                threshold, advisorEnabled, "SHADOW", false, true,
                SystemOneRolloutGuard.disabled());
    }

    SystemOneRoutingDeployment(Mode mode, int canaryPercent, String releaseVersion,
                               String expectedModel, String manifestPath, double threshold,
                               boolean advisorEnabled, String advisorMode,
                               boolean comparisonEnabled, boolean multiAgentEnabled) {
        this(mode, canaryPercent, releaseVersion, expectedModel, manifestPath,
                threshold, advisorEnabled, advisorMode, comparisonEnabled,
                multiAgentEnabled, SystemOneRolloutGuard.disabled());
    }

    SystemOneRoutingDeployment(Mode mode, int canaryPercent, String releaseVersion,
                               String expectedModel, String manifestPath, double threshold,
                               boolean advisorEnabled, String advisorMode,
                               boolean comparisonEnabled, boolean multiAgentEnabled,
                               SystemOneRolloutGuard rolloutGuard) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.rolloutGuard = java.util.Objects.requireNonNull(rolloutGuard, "rolloutGuard");
        if (canaryPercent < 0 || canaryPercent > 100) {
            throw new IllegalArgumentException("System One canary percent must be 0..100");
        }
        this.canaryPercent = canaryPercent;
        this.releaseVersion = releaseVersion == null ? "" : releaseVersion.trim();
        this.expectedModel = expectedModel == null ? "" : expectedModel.trim();
        if (mode == Mode.CANARY || mode == Mode.ACTIVE) {
            if (!advisorEnabled || !multiAgentEnabled || comparisonEnabled
                    || !"SHADOW".equalsIgnoreCase(advisorMode)
                    || this.releaseVersion.isBlank() || this.expectedModel.isBlank()
                    || manifestPath == null || manifestPath.isBlank()) {
                throw new IllegalArgumentException(
                        "System One live routing needs a SHADOW advisor, enabled multi-agent,"
                                + " no dual comparison, and a release manifest");
            }
            if (mode == Mode.CANARY && canaryPercent == 100) {
                throw new IllegalArgumentException("System One canary must retain a control group");
            }
            verifyManifest(Path.of(manifestPath), threshold);
        }
    }

    static SystemOneRoutingDeployment off() {
        return new SystemOneRoutingDeployment(Mode.OFF, 0, "", "", "", 0.75, false);
    }

    Decision decide(String question, SystemOneRoutingAdvisor.RoutingAdvice advice,
                    boolean baselineMulti, List<AgentDomain> baselineDomains,
                    boolean knownSafety, int maxAgents) {
        if (rolloutGuard.isPaused()) {
            return new Decision(false, false, baselineMulti, baselineDomains, "AUTO_PAUSED");
        }
        boolean selected = selected(question);
        if (!selected) {
            return rolloutGuard.record(mode, false, "CONTROL", "", 0)
                    ? new Decision(false, false, baselineMulti, baselineDomains, "NOT_SELECTED")
                    : new Decision(false, false, baselineMulti, baselineDomains,
                            "OBSERVABILITY_FALLBACK");
        }
        Decision proposed = propose(advice, baselineMulti, baselineDomains, knownSafety, maxAgents);
        boolean recorded = rolloutGuard.record(mode, true, proposed.status(),
                advice == null ? "" : advice.model(), advice == null ? 0 : advice.latencyMs());
        if (!recorded) {
            return new Decision(true, false, baselineMulti, baselineDomains,
                    rolloutGuard.isPaused() ? "AUTO_PAUSED" : "OBSERVABILITY_FALLBACK");
        }
        return proposed;
    }

    private Decision propose(SystemOneRoutingAdvisor.RoutingAdvice advice,
                             boolean baselineMulti, List<AgentDomain> baselineDomains,
                             boolean knownSafety, int maxAgents) {
        if (knownSafety || (advice != null && (advice.recommendedSafetyGuard()
                || advice.safetyProbability() >= 0.5))) {
            return new Decision(true, false, baselineMulti, baselineDomains, "SAFETY_FALLBACK");
        }
        if (advice == null || !"SUCCESS".equals(advice.status())
                || !expectedModel.equals(advice.model())) {
            return new Decision(true, false, baselineMulti, baselineDomains, "ADVICE_FALLBACK");
        }
        if (!advice.recommendedMultiAgent()) {
            return new Decision(true, true, false, List.of(), "APPLIED");
        }
        Map<AgentDomain, Double> probabilities = advice.domainProbabilities();
        List<AgentDomain> specialists = new ArrayList<>();
        for (AgentDomain domain : DeterministicRoutingContract.defaultContract().selectionOrder()) {
            Double probability = probabilities.get(domain);
            if (domain != AgentDomain.SAFETY
                    && probability != null && Double.isFinite(probability)
                    && probability >= 0.5 && probability <= 1.0
                    && specialists.size() < maxAgents) {
                specialists.add(domain);
            }
        }
        if (specialists.size() < 2) {
            return new Decision(true, false, baselineMulti, baselineDomains, "EXPERT_FALLBACK");
        }
        return new Decision(true, true, true, List.copyOf(specialists), "APPLIED");
    }

    Mode mode() { return mode; }
    String releaseVersion() { return releaseVersion; }
    boolean selects(String question) { return !rolloutGuard.isPaused() && selected(question); }

    private static SystemOneRolloutGuard liveGuard(String mode, String directory,
                                                   String releaseVersion, int consecutiveLimit,
                                                   int minimumSelected, double failureRateLimit) {
        Mode resolved = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        if (resolved != Mode.CANARY && resolved != Mode.ACTIVE) {
            return SystemOneRolloutGuard.disabled();
        }
        return new SystemOneRolloutGuard(Path.of(directory), releaseVersion,
                consecutiveLimit, minimumSelected, failureRateLimit);
    }

    private boolean selected(String question) {
        if (mode == Mode.ACTIVE) return true;
        if (mode != Mode.CANARY || canaryPercent == 0) return false;
        String key = releaseVersion + "\n" + (question == null ? "" : question.strip());
        byte[] hash = sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int bucket = ((hash[0] & 0xff) << 8 | (hash[1] & 0xff)) % 100;
        return bucket < canaryPercent;
    }

    private void verifyManifest(Path path, double threshold) {
        try {
            JsonNode manifest = new ObjectMapper().readTree(Files.readAllBytes(path));
            String reportPath = required(manifest, "report_path");
            String reportSha = required(manifest, "report_sha256");
            if (!releaseVersion.equals(required(manifest, "release_version"))
                    || !expectedModel.equals(required(manifest, "model"))
                    || !manifest.path("independent_holdout_approved").asBoolean(false)
                    || required(manifest, "approved_by").isBlank()
                    || !required(manifest, "dataset_sha256").matches("[0-9a-fA-F]{64}")
                    || !reportSha.matches("[0-9a-fA-F]{64}")
                    || !Double.isFinite(threshold)
                    || manifest.path("multi_agent_threshold").asDouble(Double.NaN) != threshold) {
                throw new IllegalArgumentException("System One release manifest does not match deployment");
            }
            Path manifestDirectory = path.toAbsolutePath().normalize().getParent();
            Path reportFile = manifestDirectory.resolve(reportPath).normalize();
            if (!reportFile.getParent().equals(manifestDirectory)) {
                throw new IllegalArgumentException("System One release report must be beside manifest");
            }
            byte[] reportBytes = Files.readAllBytes(reportFile);
            if (!HexFormat.of().formatHex(sha256(reportBytes)).equalsIgnoreCase(reportSha)) {
                throw new IllegalArgumentException("System One release report hash mismatch");
            }
            JsonNode report = new ObjectMapper().readTree(reportBytes);
            if (!report.path("releaseGatePassed").asBoolean(false)
                    || !report.path("gateFailures").isArray()
                    || !report.path("gateFailures").isEmpty()
                    || report.path("caseCount").asInt(0) < 30
                    || !expectedModel.equals(report.path("model").asText())) {
                throw new IllegalArgumentException("System One release report failed the gate");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot load System One release manifest/report", exception);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing System One release field: " + field);
        return value;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Decision(boolean canarySelected, boolean applied, boolean multiAgent,
                    List<AgentDomain> domains, String status) { }
}
