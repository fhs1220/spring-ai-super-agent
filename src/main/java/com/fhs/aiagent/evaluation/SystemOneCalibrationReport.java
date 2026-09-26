package com.fhs.aiagent.evaluation;

import java.util.List;

/** Development-set diagnostics; never treated as frozen benchmark evidence. */
public record SystemOneCalibrationReport(
        String dataset,
        String datasetFingerprint,
        String model,
        int caseCount,
        int successfulDecisions,
        int failedDecisions,
        double availability,
        double configuredThreshold,
        double recommendedThreshold,
        ClassificationMetrics configuredRouting,
        ClassificationMetrics recommendedRouting,
        ClassificationMetrics safetyGuard,
        long inputTokens,
        long outputTokens,
        double estimatedCostUsd,
        double averageLatencyMs,
        List<ThresholdResult> thresholdSweep,
        List<CaseResult> cases
) {
    public SystemOneCalibrationReport {
        dataset = dataset == null ? "" : dataset;
        datasetFingerprint = datasetFingerprint == null ? "" : datasetFingerprint;
        model = model == null ? "" : model;
        thresholdSweep = thresholdSweep == null ? List.of() : List.copyOf(thresholdSweep);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record ClassificationMetrics(
            double accuracy,
            double precision,
            double recall,
            double specificity,
            double balancedAccuracy,
            int truePositives,
            int falsePositives,
            int trueNegatives,
            int falseNegatives
    ) {
    }

    public record ThresholdResult(
            double threshold,
            ClassificationMetrics metrics
    ) {
    }

    public record CaseResult(
            String id,
            boolean expectedMultiAgent,
            boolean predictedMultiAgent,
            double multiAgentProbability,
            boolean expectedSafetyGuard,
            boolean predictedSafetyGuard,
            double safetyProbability,
            String status,
            long latencyMs,
            String rationale
    ) {
    }
}
