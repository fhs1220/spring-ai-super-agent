package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.AgentRunCancelledException;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.fhs.aiagent.evaluation.SystemOneCounterfactualLabel.*;

/** Checkpoint before and after every billable call; unknown outcomes never retry automatically. */
@Service
public class SystemOneCounterfactualLabelingService {
    static final String DEFAULT_SAMPLING_FRAME = "DISAGREEMENT_ENRICHED_NOT_POPULATION";
    static final String BOOTSTRAP_SAMPLING_FRAME = "BOOTSTRAP_DEVELOPMENT_ONLY";
    private final RagEvaluationVariantExecutor variantExecutor;
    private final CounterfactualQualityJudge qualityJudge;
    private final SystemOneCounterfactualLabelRepository repository;
    private final SystemOneUtilityPolicy utilityPolicy;
    private final double minimumUtilityGain;
    private final double humanReviewMargin;
    private final double minimumJudgeConfidence;
    private final int developmentPercent;
    private final Map<String, String> provenance;

    @Autowired
    public SystemOneCounterfactualLabelingService(RagEvaluationVariantExecutor executor,
            CounterfactualQualityJudge judge, SystemOneCounterfactualLabelRepository repository,
            Environment env) {
        this(executor, judge, repository,
                number(env, "utility.cost-weight", 0.05), number(env, "utility.latency-weight", 0.05),
                number(env, "utility.cost-scale-cny", 1.0), number(env, "utility.latency-scale-ms", 60000),
                number(env, "labeling.minimum-utility-gain", 0.01),
                number(env, "labeling.human-review-margin", 0.03),
                number(env, "labeling.minimum-judge-confidence", 0.70),
                env.getProperty("agent.evaluation.system-one.labeling.development-percent", Integer.class, 70),
                Map.of("generationModel", env.getProperty("spring.ai.openai.chat.options.model", "UNSPECIFIED"),
                        "generationTemperature", env.getProperty("spring.ai.openai.chat.options.temperature", "PROVIDER_DEFAULT"),
                        "sourceRevision", env.getProperty("agent.evaluation.system-one.labeling.source-revision", "UNSPECIFIED"),
                        "corpusFingerprint", env.getProperty("agent.evaluation.system-one.labeling.corpus-fingerprint", "UNSPECIFIED"),
                        "modelMaxAttempts", env.getProperty("spring.ai.retry.max-attempts", "2"),
                        "inputPriceCnyPerMillionTokens", Double.toString(env.getProperty(
                                "agent.rag.observability.input-price-per-million-tokens-cny", Double.class, 0.0)),
                        "outputPriceCnyPerMillionTokens", Double.toString(env.getProperty(
                                "agent.rag.observability.output-price-per-million-tokens-cny", Double.class, 0.0)),
                        "samplingFrame", env.getProperty("agent.evaluation.system-one.labeling.sampling-frame",
                                DEFAULT_SAMPLING_FRAME),
                        "sourceDatasetFingerprint", env.getProperty(
                                "agent.evaluation.system-one.labeling.source-dataset-fingerprint", "UNSPECIFIED"),
                        "judgeContract", SpringCounterfactualQualityJudge.CONTRACT_VERSION));
    }

    public SystemOneCounterfactualLabelingService(RagEvaluationVariantExecutor executor,
            CounterfactualQualityJudge judge, SystemOneCounterfactualLabelRepository repository,
            double costWeight, double latencyWeight, double costScaleCny, double latencyScaleMs,
            double minimumUtilityGain, double humanReviewMargin, double minimumJudgeConfidence,
            int developmentPercent) {
        this(executor, judge, repository, costWeight, latencyWeight, costScaleCny, latencyScaleMs,
                minimumUtilityGain, humanReviewMargin, minimumJudgeConfidence, developmentPercent,
                Map.of("samplingFrame", DEFAULT_SAMPLING_FRAME));
    }

    private SystemOneCounterfactualLabelingService(RagEvaluationVariantExecutor executor,
            CounterfactualQualityJudge judge, SystemOneCounterfactualLabelRepository repository,
            double costWeight, double latencyWeight, double costScaleCny, double latencyScaleMs,
            double minimumUtilityGain, double humanReviewMargin, double minimumJudgeConfidence,
            int developmentPercent, Map<String, String> provenance) {
        this.variantExecutor = Objects.requireNonNull(executor);
        this.qualityJudge = Objects.requireNonNull(judge);
        this.repository = Objects.requireNonNull(repository);
        this.utilityPolicy = new SystemOneUtilityPolicy(costWeight, costScaleCny, latencyWeight, latencyScaleMs);
        this.minimumUtilityGain = finiteNonNegative(minimumUtilityGain);
        this.humanReviewMargin = finiteNonNegative(humanReviewMargin);
        this.minimumJudgeConfidence = finiteNonNegative(minimumJudgeConfidence);
        if (minimumJudgeConfidence > 1 || developmentPercent < 1 || developmentPercent > 99) {
            throw new IllegalArgumentException("Invalid labeling confidence or split ratio");
        }
        this.developmentPercent = developmentPercent;
        var identity = new java.util.TreeMap<>(provenance);
        identity.put("minimumUtilityGain", Double.toString(minimumUtilityGain));
        identity.put("humanReviewMargin", Double.toString(humanReviewMargin));
        identity.put("minimumJudgeConfidence", Double.toString(minimumJudgeConfidence));
        identity.put("developmentPercent", Integer.toString(developmentPercent));
        identity.put("utilityVersion", SystemOneUtilityPolicy.VERSION);
        identity.put("answerContractVersion", com.fhs.aiagent.rag.AnswerVerificationContract.VERSION);
        identity.put("reviewGateVersion", CounterfactualReviewGate.VERSION);
        this.provenance = Map.copyOf(identity);
    }

    public synchronized SystemOneCounterfactualLabel label(SystemOneShadowSample sample, String runId) {
        validateConfiguration();
        Objects.requireNonNull(sample, "sample");
        validateSampleSource(sample);
        var previous = repository.findBySampleId(sample.sampleId()).orElse(null);
        Evidence evidence = previous == null ? null : previous.evidence();
        // Same-question shadow observations may be overwritten; resume the original frozen source.
        if (evidence != null && evidence.observation() != null) sample = evidence.observation();
        validateSampleSource(sample);
        if (evidence != null && (!Objects.equals(provenance.get("samplingFrame"),
                evidence.provenance().getOrDefault("samplingFrame", DEFAULT_SAMPLING_FRAME))
                || !Objects.equals(provenance.getOrDefault("sourceDatasetFingerprint", "UNSPECIFIED"),
                evidence.provenance().getOrDefault("sourceDatasetFingerprint", "UNSPECIFIED")))) {
            throw new IllegalStateException("Checkpoint sampling source changed; use a new experiment storage directory");
        }
        if (bootstrapDevelopmentOnly() && previous != null
                && (evidence == null || !"DEVELOPMENT".equals(previous.split()))) {
            throw new IllegalStateException("Bootstrap requires development-only source evidence");
        }
        if (previous != null && List.of("COMPLETED", "REVIEW_REQUIRED", "APPROVED", "REJECTED",
                "UNKNOWN_OUTCOME").contains(previous.status())) return previous;
        String split = previous == null ? split(sample.questionFingerprint()) : previous.split();
        if (previous != null && (evidence == null || evidence.attempts().stream().anyMatch(a -> !a.costKnown()))) {
            return save(sample, runId, split, "UNKNOWN_OUTCOME", evidence,
                    "Prior call outcome/cost is unknown; automatic retry is blocked");
        }
        if (evidence != null && (!utilityPolicy.equals(evidence.utilityPolicy())
                || !provenance.equals(evidence.provenance())
                || !sample.question().equals(evidence.question()))) {
            throw new IllegalStateException("Checkpoint identity changed; use a new experiment storage directory");
        }
        if (evidence == null) evidence = new Evidence("counterfactual-evidence-v2", sample.question(),
                sample, null, null, null, utilityPolicy, provenance, List.of());
        if (sample.question().isBlank()) return save(sample, runId, split, "SKIPPED_NO_QUESTION", evidence,
                "Question text was not retained");

        List<RagEvaluationVariant> order = Math.floorMod(sample.questionFingerprint().hashCode(), 2) == 0
                ? List.of(RagEvaluationVariant.AGENTIC_SINGLE_AGENT, RagEvaluationVariant.AGENTIC_MULTI_AGENT)
                : List.of(RagEvaluationVariant.AGENTIC_MULTI_AGENT, RagEvaluationVariant.AGENTIC_SINGLE_AGENT);
        for (var variant : order) {
            var cached = variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? evidence.single() : evidence.multi();
            if (cached != null && cached.succeeded()) continue;
            AgentRunCancelledException.throwIfCancelled();
            evidence = begin(evidence, variant.name());
            save(sample, runId, split, "IN_PROGRESS", evidence, "");
            RagVariantExecution execution;
            try {
                execution = variantExecutor.execute(variant, new RagEvaluationCase(sample.sampleId(),
                        sample.question(), sample.disagreementTypes(), List.of(), List.of(), 0, 0, false, ""),
                        "shadow-label-" + runId + "-" + sample.sampleId() + "-" + variant.name());
            } catch (RuntimeException exception) {
                evidence = finish(evidence, null, false, exception.getClass().getSimpleName());
                var failed = save(sample, runId, split, "UNKNOWN_OUTCOME", evidence,
                        "Generation failed with unknown charge");
                if (AgentRunCancelledException.isCancellation(exception)) throw exception;
                return failed;
            }
            if (execution == null) throw new IllegalStateException("Missing execution result; pending checkpoint retained");
            evidence = new Evidence(evidence.schemaVersion(), evidence.question(), evidence.observation(),
                    variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? execution : evidence.single(),
                    variant == RagEvaluationVariant.AGENTIC_MULTI_AGENT ? execution : evidence.multi(),
                    evidence.judgment(), utilityPolicy, provenance, evidence.attempts());
            boolean measured = execution.usageAvailable() && Double.isFinite(execution.estimatedCostCny())
                    && execution.estimatedCostCny() >= 0;
            evidence = finish(evidence, measured ? execution.estimatedCostCny() : null,
                    measured, execution.succeeded() ? "" : "EXECUTION_FAILED");
            var checkpoint = save(sample, runId, split,
                    measured ? (execution.succeeded() ? "IN_PROGRESS" : "FAILED") : "UNKNOWN_OUTCOME",
                    evidence, measured ? "" : "Generation usage unavailable; further billing stopped");
            if (!measured || !execution.succeeded()) return checkpoint;
        }

        AgentRunCancelledException.throwIfCancelled();
        if (!evidence.completePair()) {
            return save(sample, runId, split, "REVIEW_REQUIRED", evidence,
                    "Invalid counterfactual pair (empty answer or multi-agent fallback); Judge skipped");
        }
        if (evidence.judgment() == null) {
            evidence = begin(evidence, "JUDGE");
            save(sample, runId, split, "IN_PROGRESS", evidence, "");
            try {
                var judgment = qualityJudge.judge(sample.question(), sample.questionFingerprint(),
                        evidence.single().answer(), evidence.multi().answer());
                evidence = new Evidence(evidence.schemaVersion(), evidence.question(), evidence.observation(),
                        evidence.single(), evidence.multi(), judgment, utilityPolicy, provenance, evidence.attempts());
                var usage = judgment.usage();
                evidence = finish(evidence, usage.measured() ? usage.estimatedCostCny() : null,
                        usage.measured(), "");
            } catch (RuntimeException exception) {
                var usage = exception instanceof SpringCounterfactualQualityJudge.JudgeResponseException invalid
                        ? invalid.usage() : CounterfactualQualityJudge.JudgeUsage.unknown();
                evidence = finish(evidence, usage.measured() ? usage.estimatedCostCny() : null,
                        usage.measured(), exception.getClass().getSimpleName());
                var failed = save(sample, runId, split, usage.measured() ? "FAILED" : "UNKNOWN_OUTCOME",
                        evidence, "Judge failed: " + exception.getClass().getSimpleName());
                if (AgentRunCancelledException.isCancellation(exception)) throw exception;
                return failed;
            }
        }
        return save(sample, runId, split, "AUTO", evidence, "");
    }

    public void validateConfiguration() {
        if (!"1".equals(provenance.getOrDefault("modelMaxAttempts", "1"))) {
            throw new IllegalStateException("Labeling requires SPRING_AI_RETRY_MAX_ATTEMPTS=1; "
                    + "opaque SDK retries cannot be accounted by the attempt ledger");
        }
        String frame = provenance.get("samplingFrame");
        if (!DEFAULT_SAMPLING_FRAME.equals(frame) && !BOOTSTRAP_SAMPLING_FRAME.equals(frame)) {
            throw new IllegalStateException("Unsupported labeling sampling-frame");
        }
        if (bootstrapDevelopmentOnly() && !provenance.getOrDefault("sourceDatasetFingerprint", "")
                .matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Bootstrap requires a valid source-dataset-fingerprint SHA-256");
        }
    }

    private boolean bootstrapDevelopmentOnly() {
        return BOOTSTRAP_SAMPLING_FRAME.equals(provenance.get("samplingFrame"));
    }

    private void validateSampleSource(SystemOneShadowSample sample) {
        boolean bootstrapSample = BOOTSTRAP_SAMPLING_FRAME.equals(sample.sampledReason());
        if (bootstrapDevelopmentOnly() != bootstrapSample) {
            throw new IllegalArgumentException("Observation sampledReason does not match labeling sampling-frame");
        }
    }

    private Evidence begin(Evidence e, String stage) {
        var attempts = new ArrayList<>(e.attempts());
        attempts.add(new StageAttempt(UUID.randomUUID().toString(), stage, Instant.now(), null,
                "PENDING", null, false, ""));
        return withAttempts(e, attempts);
    }

    private Evidence finish(Evidence e, Double cost, boolean measured, String error) {
        var attempts = new ArrayList<>(e.attempts());
        var start = attempts.getLast();
        attempts.set(attempts.size() - 1, new StageAttempt(start.attemptId(), start.stage(), start.startedAt(),
                Instant.now(), error.isEmpty() ? "SUCCEEDED" : "FAILED", cost, measured, error));
        return withAttempts(e, attempts);
    }

    private Evidence withAttempts(Evidence e, List<StageAttempt> attempts) {
        return new Evidence(e.schemaVersion(), e.question(), e.observation(), e.single(), e.multi(),
                e.judgment(), e.utilityPolicy(), e.provenance(), attempts);
    }

    private SystemOneCounterfactualLabel save(SystemOneShadowSample sample, String runId, String split,
            String status, Evidence evidence, String error) {
        var judgment = evidence == null ? null : evidence.judgment();
        var single = evidence == null ? null : evidence.single();
        var multi = evidence == null ? null : evidence.multi();
        double singleQuality = judgment == null ? 0 : judgment.singleQuality();
        double multiQuality = judgment == null ? 0 : judgment.multiQuality();
        double singleUtility = judgment == null ? 0 : utilityPolicy.utility(singleQuality, single.estimatedCostCny(), single.latencyMs());
        double multiUtility = judgment == null ? 0 : utilityPolicy.utility(multiQuality, multi.estimatedCostCny(), multi.latencyMs());
        double delta = multiUtility - singleUtility;
        boolean review = judgment == null || !evidence.completePair()
                || !evidence.reviewReasons().isEmpty()
                || sample.disagreementTypes().contains("SAFETY_ACTION_DISAGREEMENT")
                || judgment.confidence() < minimumJudgeConfidence
                || Math.abs(delta - minimumUtilityGain) < humanReviewMargin
                || evidence.attempts().stream().anyMatch(a -> !a.costKnown());
        if ("AUTO".equals(status)) status = review ? "REVIEW_REQUIRED" : "COMPLETED";
        return repository.save(new SystemOneCounterfactualLabel(sample.sampleId(), runId, Instant.now(), split,
                status, sample.questionFingerprint(), sample.disagreementTypes(), outcome(single, singleQuality),
                outcome(multi, multiQuality), judgment == null ? 0 : judgment.confidence(),
                judgment == null ? "" : judgment.rationale(), judgment == null ? "" : judgment.contractVersion(),
                singleUtility, multiUtility, delta, review ? null : delta > minimumUtilityGain,
                review, judgment != null && judgment.usage().measured(), error, evidence, List.of()));
    }

    private Outcome outcome(RagVariantExecution e, double quality) {
        return e == null ? null : new Outcome(e.variant().name(), e.succeeded(), quality, e.latencyMs(),
                e.totalTokens(), e.estimatedCostCny(), e.usageAvailable(),
                e.fellBackToSingle() ? "SINGLE_AGENT_FALLBACK" : e.executionMode(), e.error());
    }

    private String split(String fingerprint) {
        if (bootstrapDevelopmentOnly()) return "DEVELOPMENT";
        int bucket;
        try { bucket = Integer.remainderUnsigned(Integer.parseUnsignedInt(fingerprint.substring(0, 8), 16), 100); }
        catch (RuntimeException exception) { bucket = Math.floorMod(Objects.toString(fingerprint, "").hashCode(), 100); }
        return bucket < developmentPercent ? "DEVELOPMENT" : "HOLDOUT";
    }

    private static double number(Environment env, String key, double fallback) {
        return env.getProperty("agent.evaluation.system-one." + key, Double.class, fallback);
    }

    private static double finiteNonNegative(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid labeling threshold");
        return value;
    }
}
