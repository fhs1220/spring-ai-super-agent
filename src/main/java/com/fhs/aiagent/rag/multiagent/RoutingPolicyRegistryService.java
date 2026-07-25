package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 把轨迹学习结果注册为内容寻址、可复现且不含用户内容的策略资产。
 */
@Service
public class RoutingPolicyRegistryService {

    public static final String BASELINE_VERSION = "routing-policy-baseline-v1";

    private static final Logger log =
            LoggerFactory.getLogger(RoutingPolicyRegistryService.class);

    private final RoutingPolicyRegistryRepository repository;

    private final AgentTrajectoryRepository trajectoryRepository;

    private final String algorithm;

    private final String upstreamModel;

    private final Map<String, String> parameters;

    private final double minimumUtilityLift;

    private final int maximumArtifacts;

    private final Clock clock;

    private volatile RoutingPolicyRegistryState state;

    @Autowired
    public RoutingPolicyRegistryService(
            RoutingPolicyRegistryRepository repository,
            AgentTrajectoryRepository trajectoryRepository,
            @Value("${agent.rag.routing-policy.registry.algorithm:"
                    + "trajectory-utility-contextual-policy-v2}") String algorithm,
            @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
            String upstreamModel,
            @Value("${agent.rag.routing-policy.minimum-utility-lift:0.03}")
            double minimumUtilityLift,
            @Value("${agent.rag.routing-policy.cost-weight:0.05}") double costWeight,
            @Value("${agent.rag.routing-policy.latency-weight:0.05}") double latencyWeight,
            @Value("${agent.rag.routing-policy.cost-budget-cny:0.02}") double costBudgetCny,
            @Value("${agent.rag.routing-policy.latency-budget-ms:60000}") double latencyBudgetMs,
            @Value("${agent.rag.routing-policy.registry.maximum-artifacts:50}")
            int maximumArtifacts) {
        this(
                repository,
                trajectoryRepository,
                algorithm,
                upstreamModel,
                Map.of(
                        "minimumUtilityLift", Double.toString(minimumUtilityLift),
                        "costWeight", Double.toString(costWeight),
                        "latencyWeight", Double.toString(latencyWeight),
                        "costBudgetCny", Double.toString(costBudgetCny),
                        "latencyBudgetMs", Double.toString(latencyBudgetMs)
                ),
                minimumUtilityLift,
                maximumArtifacts,
                Clock.systemUTC()
        );
    }

    RoutingPolicyRegistryService(
            RoutingPolicyRegistryRepository repository,
            AgentTrajectoryRepository trajectoryRepository,
            String algorithm,
            String upstreamModel,
            Map<String, String> parameters,
            double minimumUtilityLift,
            int maximumArtifacts,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trajectoryRepository = Objects.requireNonNull(
                trajectoryRepository, "trajectoryRepository");
        this.algorithm = requireText(algorithm, "algorithm");
        this.upstreamModel = requireText(upstreamModel, "upstreamModel");
        this.parameters = Map.copyOf(parameters);
        this.minimumUtilityLift = Math.max(0, minimumUtilityLift);
        this.maximumArtifacts = Math.max(2, maximumArtifacts);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostConstruct
    public synchronized void initialize() {
        state = repository.load().orElseGet(this::initialState);
        if (state.artifacts().isEmpty()) {
            state = initialState();
        }
        state = migrateBuiltInBaseline(state);
        state = repository.save(state);
    }

    public synchronized RoutingPolicyRegistryState reconcileNow(
            TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status) {
        double lift = round(
                status.multiAgent().utility() - status.singleAgent().utility());
        int evidenceSamples = Math.min(
                status.singleAgent().sampleCount(),
                status.multiAgent().sampleCount()
        );
        TrajectoryAwareRoutingPolicy.LearnedRule global =
                new TrajectoryAwareRoutingPolicy.LearnedRule(
                        status.ready(),
                        status.ready() && Math.abs(lift) >= minimumUtilityLift,
                        lift >= minimumUtilityLift,
                        0,
                        evidenceSamples,
                        lift,
                        status.singleAgent(),
                        status.multiAgent(),
                        "global rule derived from routing status"
                );
        return reconcileNow(
                status,
                new TrajectoryAwareRoutingPolicy.LearnedPolicySnapshot(
                        global,
                        Map.of(),
                        status.observedTrajectoryCount(),
                        status.minimumSamplesPerMode(),
                        status.refreshedAt()
                )
        );
    }

    public synchronized RoutingPolicyRegistryState reconcileNow(
            TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status,
            TrajectoryAwareRoutingPolicy.LearnedPolicySnapshot learnedSnapshot) {
        RoutingPolicyRegistryState currentState = requireState();
        if (!status.ready()) {
            return currentState;
        }
        List<AgentTrajectory> trainingData = eligibleTrajectories();
        String fingerprint = fingerprint(trainingData);
        RoutingPolicyArtifact.DecisionRule globalRule =
                freezeRule(learnedSnapshot.global());
        Map<String, RoutingPolicyArtifact.DecisionRule> contextualRules =
                freezeContextualRules(learnedSnapshot.contextual());
        RoutingPolicyArtifact.OfflineEvaluation evaluation = evaluation(
                status,
                globalRule,
                contextualRules
        );
        String version = artifactVersion(
                fingerprint,
                evaluation,
                globalRule,
                contextualRules
        );
        boolean alreadyRegistered = currentState.artifacts().stream()
                .anyMatch(artifact -> version.equals(artifact.version()));
        if (alreadyRegistered) {
            return currentState;
        }

        RoutingPolicyArtifactStatus artifactStatus = evaluation.validationPassed()
                ? RoutingPolicyArtifactStatus.VALIDATED
                : RoutingPolicyArtifactStatus.REJECTED;
        String validationReason = evaluation.validationPassed()
                ? "balanced evidence and at least one frozen rule passed utility gate"
                : String.join("; ", evaluation.validationFailures());
        RoutingPolicyArtifact artifact = new RoutingPolicyArtifact(
                2,
                version,
                artifactStatus,
                algorithm,
                upstreamModel,
                parameters,
                fingerprint,
                trainingData.size(),
                evaluation,
                globalRule,
                contextualRules,
                latestValidatedVersion(currentState),
                clock.instant(),
                validationReason
        );
        List<RoutingPolicyArtifact> artifacts = new ArrayList<>();
        artifacts.add(artifact);
        artifacts.addAll(currentState.artifacts());
        state = repository.save(new RoutingPolicyRegistryState(
                artifacts.stream().limit(maximumArtifacts).toList(),
                clock.instant()
        ));
        log.info(
                "已注册路由策略资产 version={}, status={}, samples={}, fingerprint={}",
                version,
                artifactStatus,
                trainingData.size(),
                fingerprint
        );
        return state;
    }

    public RoutingPolicyRegistryState state() {
        return requireState();
    }

    public RoutingPolicyArtifact find(String version) {
        return requireState().artifacts().stream()
                .filter(artifact -> artifact.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Routing policy artifact not found: " + version));
    }

    public RoutingPolicyArtifact requireDeployable(String version) {
        RoutingPolicyArtifact artifact = find(version);
        if (artifact.status() != RoutingPolicyArtifactStatus.VALIDATED) {
            throw new IllegalStateException(
                    "Routing policy artifact is not validated: " + version);
        }
        if (BASELINE_VERSION.equals(version)) {
            throw new IllegalStateException(
                    "Baseline routing policy cannot be promoted to CANARY");
        }
        return artifact;
    }

    public RoutingPolicyArtifact latestValidatedCandidate() {
        return requireState().artifacts().stream()
                .filter(artifact ->
                        artifact.status() == RoutingPolicyArtifactStatus.VALIDATED)
                .filter(artifact -> !BASELINE_VERSION.equals(artifact.version()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "No validated learned routing policy artifact"));
    }

    private RoutingPolicyRegistryState initialState() {
        return new RoutingPolicyRegistryState(
                List.of(baselineArtifact(clock.instant())),
                clock.instant()
        );
    }

    private RoutingPolicyRegistryState migrateBuiltInBaseline(
            RoutingPolicyRegistryState currentState) {
        boolean requiresMigration = currentState.artifacts().stream()
                .anyMatch(artifact -> BASELINE_VERSION.equals(artifact.version())
                        && artifact.schemaVersion() < 2);
        if (!requiresMigration) {
            return currentState;
        }
        List<RoutingPolicyArtifact> migrated = currentState.artifacts().stream()
                .map(artifact -> BASELINE_VERSION.equals(artifact.version())
                        && artifact.schemaVersion() < 2
                        ? baselineArtifact(artifact.createdAt())
                        : artifact)
                .toList();
        return new RoutingPolicyRegistryState(migrated, clock.instant());
    }

    private RoutingPolicyArtifact baselineArtifact(Instant createdAt) {
        return new RoutingPolicyArtifact(
                2,
                BASELINE_VERSION,
                RoutingPolicyArtifactStatus.BASELINE,
                "deterministic-complexity-router-v1",
                upstreamModel,
                Map.of(),
                "baseline",
                0,
                RoutingPolicyArtifact.OfflineEvaluation.empty(),
                RoutingPolicyArtifact.DecisionRule.empty(),
                Map.of(),
                "",
                createdAt,
                "built-in deterministic baseline"
        );
    }

    private RoutingPolicyArtifact.OfflineEvaluation evaluation(
            TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status,
            RoutingPolicyArtifact.DecisionRule globalRule,
            Map<String, RoutingPolicyArtifact.DecisionRule> contextualRules) {
        RoutingPolicyArtifact.ModeEvaluation single = mode(status.singleAgent());
        RoutingPolicyArtifact.ModeEvaluation multi = mode(status.multiAgent());
        double lift = round(multi.utility() - single.utility());
        List<String> failures = new ArrayList<>();
        if (!status.ready()) {
            failures.add("single/multi evidence is not balanced");
        }
        if (!globalRule.deployable() && contextualRules.isEmpty()) {
            failures.add("no global or contextual rule passed sample and utility lift gates");
        }
        return new RoutingPolicyArtifact.OfflineEvaluation(
                status.ready(),
                status.observedTrajectoryCount(),
                status.minimumSamplesPerMode(),
                single,
                multi,
                lift,
                globalRule.deployable()
                        ? globalRule.recommendedMode()
                        : "DETERMINISTIC",
                failures.isEmpty(),
                failures
        );
    }

    private Map<String, RoutingPolicyArtifact.DecisionRule> freezeContextualRules(
            Map<String, TrajectoryAwareRoutingPolicy.LearnedRule> learnedRules) {
        Map<String, RoutingPolicyArtifact.DecisionRule> frozen = new LinkedHashMap<>();
        learnedRules.entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && entry.getValue().deployable())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> frozen.put(entry.getKey(), freezeRule(entry.getValue())));
        return Map.copyOf(frozen);
    }

    private RoutingPolicyArtifact.DecisionRule freezeRule(
            TrajectoryAwareRoutingPolicy.LearnedRule learnedRule) {
        if (learnedRule == null || !learnedRule.deployable()) {
            return RoutingPolicyArtifact.DecisionRule.empty();
        }
        return new RoutingPolicyArtifact.DecisionRule(
                true,
                learnedRule.selectMultiAgent()
                        ? AdaptiveMultiAgentOrchestrator.MULTI_MODE
                        : AdaptiveMultiAgentOrchestrator.SINGLE_MODE,
                learnedRule.confidence(),
                learnedRule.evidenceSamples(),
                learnedRule.utilityLift(),
                mode(learnedRule.singleAgent()),
                mode(learnedRule.multiAgent()),
                learnedRule.reason()
        );
    }

    private RoutingPolicyArtifact.ModeEvaluation mode(
            TrajectoryAwareRoutingPolicy.ModeStats stats) {
        return new RoutingPolicyArtifact.ModeEvaluation(
                stats.sampleCount(),
                stats.successfulCount(),
                stats.usageMeasuredSamples(),
                stats.averageReward(),
                stats.averageCostCny(),
                stats.averageLatencyMs(),
                stats.utility()
        );
    }

    private List<AgentTrajectory> eligibleTrajectories() {
        return trajectoryRepository.findAll().stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status())
                        || "FAILED".equals(trajectory.status()))
                .filter(trajectory -> routeStep(trajectory) != null)
                .sorted(Comparator.comparing(AgentTrajectory::trajectoryId))
                .toList();
    }

    private String fingerprint(List<AgentTrajectory> trajectories) {
        MessageDigest digest = sha256Digest();
        for (AgentTrajectory trajectory : trajectories) {
            AgentStep route = routeStep(trajectory);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("trajectoryId", trajectory.trajectoryId());
            fields.put("completedAt", Objects.toString(trajectory.completedAt(), ""));
            fields.put("status", Objects.toString(trajectory.status(), ""));
            fields.put("reward", trajectory.reward() == null
                    ? ""
                    : Double.toString(trajectory.reward().total()));
            fields.put("mode", Objects.toString(route.output().get("mode"), ""));
            fields.put(
                    "featureBucket",
                    Objects.toString(route.output().get("featureBucket"), "")
            );
            fields.forEach((key, value) -> digest.update(
                    (key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String artifactVersion(
            String trainingDataFingerprint,
            RoutingPolicyArtifact.OfflineEvaluation evaluation,
            RoutingPolicyArtifact.DecisionRule globalRule,
            Map<String, RoutingPolicyArtifact.DecisionRule> contextualRules) {
        MessageDigest digest = sha256Digest();
        digest.update("schemaVersion=2\n".getBytes(StandardCharsets.UTF_8));
        digest.update(("algorithm=" + algorithm + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update(("upstreamModel=" + upstreamModel + "\n")
                .getBytes(StandardCharsets.UTF_8));
        parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> digest.update(
                        ("parameter." + entry.getKey() + "=" + entry.getValue() + "\n")
                                .getBytes(StandardCharsets.UTF_8)));
        digest.update(("trainingDataFingerprint=" + trainingDataFingerprint + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update(("minimumSamplesPerMode=" + evaluation.minimumSamplesPerMode() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        updateModeDigest(digest, "evaluation.single", evaluation.singleAgent());
        updateModeDigest(digest, "evaluation.multi", evaluation.multiAgent());
        digest.update(("multiAgentUtilityLift="
                + evaluation.multiAgentUtilityLift() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update(("recommendedMode=" + evaluation.recommendedMode() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update(("validationPassed=" + evaluation.validationPassed() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        for (int index = 0; index < evaluation.validationFailures().size(); index++) {
            digest.update(("validationFailure." + index + "="
                    + evaluation.validationFailures().get(index) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        updateRuleDigest(digest, "global", globalRule);
        contextualRules.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> updateRuleDigest(
                        digest,
                        "contextual." + entry.getKey(),
                        entry.getValue()
                ));
        return "routing-policy-"
                + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

    private void updateRuleDigest(MessageDigest digest,
                                  String scope,
                                  RoutingPolicyArtifact.DecisionRule rule) {
        digest.update((scope + ".deployable=" + rule.deployable() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".recommendedMode=" + rule.recommendedMode() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".confidence=" + rule.confidence() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".evidenceSamples=" + rule.evidenceSamples() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".utilityLift=" + rule.utilityLift() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        updateModeDigest(digest, scope + ".single", rule.singleAgent());
        updateModeDigest(digest, scope + ".multi", rule.multiAgent());
        digest.update((scope + ".reason=" + rule.reason() + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void updateModeDigest(
            MessageDigest digest,
            String scope,
            RoutingPolicyArtifact.ModeEvaluation mode) {
        digest.update((scope + ".sampleCount=" + mode.sampleCount() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".successfulCount=" + mode.successfulCount() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".usageMeasuredSamples="
                + mode.usageMeasuredSamples() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".averageReward=" + mode.averageReward() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".averageCostCny=" + mode.averageCostCny() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".averageLatencyMs=" + mode.averageLatencyMs() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        digest.update((scope + ".utility=" + mode.utility() + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AgentStep routeStep(AgentTrajectory trajectory) {
        if (trajectory == null || trajectory.steps() == null) {
            return null;
        }
        return trajectory.steps().stream()
                .filter(step -> step.type() == AgentStepType.ROUTE)
                .findFirst()
                .orElse(null);
    }

    private String latestValidatedVersion(RoutingPolicyRegistryState currentState) {
        return currentState.artifacts().stream()
                .filter(artifact ->
                        artifact.status() == RoutingPolicyArtifactStatus.VALIDATED)
                .map(RoutingPolicyArtifact::version)
                .findFirst()
                .orElse(BASELINE_VERSION);
    }

    private RoutingPolicyRegistryState requireState() {
        RoutingPolicyRegistryState current = state;
        if (current == null) {
            synchronized (this) {
                if (state == null) {
                    initialize();
                }
                current = state;
            }
        }
        return current;
    }

    private String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private double round(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }
}
