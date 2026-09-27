package com.fhs.aiagent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SystemOneCounterfactualLabel(
        String sampleId,
        String runId,
        Instant labeledAt,
        String split,
        String status,
        String questionFingerprint,
        List<String> disagreementTypes,
        Outcome forcedSingle,
        Outcome forcedMulti,
        double judgeConfidence,
        String judgeRationale,
        String judgeContractVersion,
        double singleUtility,
        double multiUtility,
        double utilityDelta,
        Boolean expectedMultiAgent,
        boolean humanReviewRequired,
        boolean judgeCostMeasured,
        String error,
        Evidence evidence,
        List<HumanReview> reviews
) {

    public SystemOneCounterfactualLabel(String sampleId, String runId, Instant labeledAt,
            String split, String status, String questionFingerprint, List<String> disagreementTypes,
            Outcome forcedSingle, Outcome forcedMulti, double judgeConfidence, String judgeRationale,
            String judgeContractVersion, double singleUtility, double multiUtility, double utilityDelta,
            Boolean expectedMultiAgent, boolean humanReviewRequired, boolean judgeCostMeasured, String error) {
        this(sampleId, runId, labeledAt, split, status, questionFingerprint, disagreementTypes,
                forcedSingle, forcedMulti, judgeConfidence, judgeRationale, judgeContractVersion,
                singleUtility, multiUtility, utilityDelta, expectedMultiAgent, humanReviewRequired,
                judgeCostMeasured, error, null, List.of());
    }

    public SystemOneCounterfactualLabel {
        sampleId = sampleId == null ? "" : sampleId;
        runId = runId == null ? "" : runId;
        labeledAt = labeledAt == null ? Instant.EPOCH : labeledAt;
        split = split == null ? "DEVELOPMENT" : split;
        status = status == null ? "FAILED" : status;
        questionFingerprint = questionFingerprint == null ? "" : questionFingerprint;
        disagreementTypes = disagreementTypes == null ? List.of() : List.copyOf(disagreementTypes);
        judgeConfidence = clamp(judgeConfidence);
        judgeRationale = judgeRationale == null ? "" : judgeRationale;
        judgeContractVersion = judgeContractVersion == null ? "" : judgeContractVersion;
        error = error == null ? "" : error;
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
    }

    /** Known charges only; callers must inspect costAccountingComplete before interpreting totals. */
    public double totalEstimatedCostCny() {
        if (evidence != null) return evidence.attempts().stream()
                .map(StageAttempt::estimatedCostCny).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();
        return (forcedSingle == null ? 0 : forcedSingle.estimatedCostCny())
                + (forcedMulti == null ? 0 : forcedMulti.estimatedCostCny());
    }

    public boolean costAccountingComplete() {
        return evidence != null && !evidence.attempts().isEmpty()
                && evidence.attempts().stream().allMatch(StageAttempt::costKnown);
    }

    public boolean trainingEligible() {
        return "DEVELOPMENT".equals(split) && "APPROVED".equals(status)
                && expectedMultiAgent != null && !reviews.isEmpty() && costAccountingComplete()
                && evidence != null && evidence.completePair()
                && evidence.judgment() != null;
    }

    public SystemOneCounterfactualLabel reviewed(HumanReview review) {
        var history = new java.util.ArrayList<>(reviews);
        history.add(review);
        return new SystemOneCounterfactualLabel(sampleId, runId, labeledAt, split,
                review.accepted() ? "APPROVED" : "REJECTED", questionFingerprint,
                disagreementTypes, forcedSingle, forcedMulti, judgeConfidence, judgeRationale,
                judgeContractVersion, singleUtility, multiUtility, utilityDelta,
                review.accepted() ? review.expectedMultiAgent() : null,
                false, judgeCostMeasured, error, evidence, history);
    }

    public record HumanReview(int revision, Instant reviewedAt, String reviewer, String reason,
                              boolean accepted, Boolean expectedMultiAgent) { }

    public record StageAttempt(String attemptId, String stage, Instant startedAt, Instant finishedAt,
                               String status, Double estimatedCostCny, boolean usageMeasured,
                               String errorType) {
        public boolean costKnown() {
            return finishedAt != null && usageMeasured && estimatedCostCny != null
                    && Double.isFinite(estimatedCostCny) && estimatedCostCny >= 0;
        }
    }

    public record Evidence(String schemaVersion, String question,
                           com.fhs.aiagent.rag.multiagent.SystemOneShadowSample observation,
                           RagVariantExecution single, RagVariantExecution multi,
                           CounterfactualQualityJudge.PairJudgment judgment,
                           SystemOneUtilityPolicy utilityPolicy, Map<String, String> provenance,
                           List<StageAttempt> attempts) {
        public Evidence {
            provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        public boolean completePair() {
            return single != null && multi != null && single.succeeded() && multi.succeeded()
                    && single.variant() == RagEvaluationVariant.AGENTIC_SINGLE_AGENT
                    && "SINGLE_AGENT".equals(single.executionMode())
                    && multi.variant() == RagEvaluationVariant.AGENTIC_MULTI_AGENT
                    && "ADAPTIVE_MULTI_AGENT".equals(multi.executionMode())
                    && single.answer() != null && !single.answer().isBlank()
                    && multi.answer() != null && !multi.answer().isBlank()
                    && !multi.fellBackToSingle();
        }

        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public List<String> reviewReasons() {
            return CounterfactualReviewGate.reasons(this);
        }
    }

    public record Outcome(
            String variant,
            boolean succeeded,
            double quality,
            long latencyMs,
            long totalTokens,
            double estimatedCostCny,
            boolean usageAvailable,
            String executionMode,
            String error
    ) {

        public Outcome {
            variant = variant == null ? "" : variant;
            quality = clamp(quality);
            latencyMs = Math.max(0, latencyMs);
            totalTokens = Math.max(0, totalTokens);
            estimatedCostCny = Math.max(0, estimatedCostCny);
            executionMode = executionMode == null ? "" : executionMode;
            error = error == null ? "" : error;
        }
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
