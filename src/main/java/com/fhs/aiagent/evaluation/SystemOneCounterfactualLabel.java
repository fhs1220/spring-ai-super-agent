package com.fhs.aiagent.evaluation;

import java.time.Instant;
import java.util.List;

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
        String error
) {

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
