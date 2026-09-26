package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.AgentRunCancelledException;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class SystemOneCounterfactualLabelingService {

    private final RagEvaluationVariantExecutor variantExecutor;
    private final CounterfactualQualityJudge qualityJudge;
    private final SystemOneCounterfactualLabelRepository repository;
    private final double costWeight;
    private final double latencyWeight;
    private final double costBudgetCny;
    private final double latencyBudgetMs;
    private final double minimumUtilityGain;
    private final double humanReviewMargin;
    private final double minimumJudgeConfidence;
    private final int developmentPercent;

    public SystemOneCounterfactualLabelingService(
            RagEvaluationVariantExecutor variantExecutor,
            CounterfactualQualityJudge qualityJudge,
            SystemOneCounterfactualLabelRepository repository,
            @Value("${agent.rag.routing-policy.cost-weight:0.05}") double costWeight,
            @Value("${agent.rag.routing-policy.latency-weight:0.05}") double latencyWeight,
            @Value("${agent.rag.routing-policy.cost-budget-cny:0.02}") double costBudgetCny,
            @Value("${agent.rag.routing-policy.latency-budget-ms:60000}") double latencyBudgetMs,
            @Value("${agent.evaluation.system-one.labeling.minimum-utility-gain:0.01}")
            double minimumUtilityGain,
            @Value("${agent.evaluation.system-one.labeling.human-review-margin:0.03}")
            double humanReviewMargin,
            @Value("${agent.evaluation.system-one.labeling.minimum-judge-confidence:0.70}")
            double minimumJudgeConfidence,
            @Value("${agent.evaluation.system-one.labeling.development-percent:70}")
            int developmentPercent) {
        this.variantExecutor = Objects.requireNonNull(variantExecutor, "variantExecutor");
        this.qualityJudge = Objects.requireNonNull(qualityJudge, "qualityJudge");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.costWeight = Math.max(0, costWeight);
        this.latencyWeight = Math.max(0, latencyWeight);
        this.costBudgetCny = Math.max(1.0e-9, costBudgetCny);
        this.latencyBudgetMs = Math.max(1, latencyBudgetMs);
        this.minimumUtilityGain = Math.max(0, minimumUtilityGain);
        this.humanReviewMargin = Math.max(0, humanReviewMargin);
        this.minimumJudgeConfidence = clamp(minimumJudgeConfidence);
        this.developmentPercent = Math.max(1, Math.min(99, developmentPercent));
    }

    public SystemOneCounterfactualLabel label(SystemOneShadowSample sample, String runId) {
        Objects.requireNonNull(sample, "sample");
        String split = split(sample.questionFingerprint());
        if (sample.question().isBlank()) {
            return repository.save(failed(sample, runId, split,
                    "SKIPPED_NO_QUESTION", "question text was not retained"));
        }
        RagEvaluationCase evaluationCase = new RagEvaluationCase(
                sample.sampleId(), sample.question(), sample.disagreementTypes(),
                List.of(), List.of(), 0, 0, false, "");
        RagVariantExecution single = execute(
                RagEvaluationVariant.AGENTIC_SINGLE_AGENT, evaluationCase, runId);
        RagVariantExecution multi = execute(
                RagEvaluationVariant.AGENTIC_MULTI_AGENT, evaluationCase, runId);
        if (!single.succeeded() || !multi.succeeded()) {
            return repository.save(new SystemOneCounterfactualLabel(
                    sample.sampleId(), runId, Instant.now(), split, "FAILED",
                    sample.questionFingerprint(), sample.disagreementTypes(),
                    outcome(single, 0), outcome(multi, 0), 0, "", "",
                    0, 0, 0, null, true, false,
                    "forced execution failed"));
        }
        try {
            CounterfactualQualityJudge.PairJudgment judgment = qualityJudge.judge(
                    sample.question(), sample.questionFingerprint(),
                    single.answer(), multi.answer());
            double singleUtility = utility(single, judgment.singleQuality());
            double multiUtility = utility(multi, judgment.multiQuality());
            double delta = multiUtility - singleUtility;
            boolean safetyDisagreement = sample.disagreementTypes().contains(
                    "SAFETY_ACTION_DISAGREEMENT");
            boolean review = safetyDisagreement
                    || judgment.confidence() < minimumJudgeConfidence
                    || Math.abs(delta) < humanReviewMargin;
            return repository.save(new SystemOneCounterfactualLabel(
                    sample.sampleId(), runId, Instant.now(), split,
                    review ? "REVIEW_REQUIRED" : "COMPLETED",
                    sample.questionFingerprint(), sample.disagreementTypes(),
                    outcome(single, judgment.singleQuality()),
                    outcome(multi, judgment.multiQuality()),
                    round(judgment.confidence()), judgment.rationale(),
                    judgment.contractVersion(), round(singleUtility), round(multiUtility),
                    round(delta), delta > minimumUtilityGain, review, false, ""));
        } catch (RuntimeException exception) {
            if (AgentRunCancelledException.isCancellation(exception)) throw exception;
            return repository.save(new SystemOneCounterfactualLabel(
                    sample.sampleId(), runId, Instant.now(), split, "FAILED",
                    sample.questionFingerprint(), sample.disagreementTypes(),
                    outcome(single, 0), outcome(multi, 0), 0, "", "",
                    0, 0, 0, null, true, false,
                    "judge failed: " + exception.getClass().getSimpleName()));
        }
    }

    private RagVariantExecution execute(RagEvaluationVariant variant,
                                        RagEvaluationCase evaluationCase,
                                        String runId) {
        try {
            return variantExecutor.execute(variant, evaluationCase,
                    "shadow-label-%s-%s-%s".formatted(
                            runId, evaluationCase.id(), variant.name().toLowerCase()));
        } catch (RuntimeException exception) {
            if (AgentRunCancelledException.isCancellation(exception)) throw exception;
            return new RagVariantExecution(
                    variant, "", 0, 0, 0, false, "",
                    exception.getClass().getSimpleName() + ": "
                            + Objects.toString(exception.getMessage(), ""));
        }
    }

    private double utility(RagVariantExecution execution, double quality) {
        return quality
                - costWeight * Math.min(1, execution.estimatedCostCny() / costBudgetCny)
                - latencyWeight * Math.min(1, execution.latencyMs() / latencyBudgetMs);
    }

    private SystemOneCounterfactualLabel.Outcome outcome(
            RagVariantExecution execution, double quality) {
        return new SystemOneCounterfactualLabel.Outcome(
                execution.variant().name(), execution.succeeded(), round(quality),
                execution.latencyMs(), execution.totalTokens(),
                roundCost(execution.estimatedCostCny()), execution.usageAvailable(),
                execution.executionMode(), execution.error());
    }

    private String split(String fingerprint) {
        int bucket;
        try {
            bucket = Integer.remainderUnsigned(
                    Integer.parseUnsignedInt(fingerprint.substring(0, 8), 16), 100);
        } catch (RuntimeException exception) {
            bucket = Math.floorMod(Objects.toString(fingerprint, "").hashCode(), 100);
        }
        return bucket < developmentPercent ? "DEVELOPMENT" : "HOLDOUT";
    }

    private SystemOneCounterfactualLabel failed(
            SystemOneShadowSample sample, String runId, String split,
            String status, String error) {
        return new SystemOneCounterfactualLabel(
                sample.sampleId(), runId, Instant.now(), split, status,
                sample.questionFingerprint(), sample.disagreementTypes(),
                null, null, 0, "", "", 0, 0, 0,
                null, true, false, error);
    }

    private double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private double roundCost(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }
}
