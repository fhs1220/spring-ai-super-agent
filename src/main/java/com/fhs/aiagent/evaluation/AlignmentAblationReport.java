package com.fhs.aiagent.evaluation;

import java.time.Instant;
import java.util.List;

public record AlignmentAblationReport(
        String reportId,
        String benchmarkVersion,
        String benchmarkFingerprint,
        int caseCount,
        Instant generatedAt,
        List<ArmMetrics> arms,
        double fullQualityDeltaVsBaseline,
        double fullPassRateDeltaVsBaseline,
        double fullCostRatioVsBaseline,
        boolean benchmarkComparable,
        boolean costComparable,
        boolean releaseGatePassed,
        List<String> gateFailures,
        String reportPath,
        String markdownReportPath
) {
    public AlignmentAblationReport {
        arms = arms == null ? List.of() : List.copyOf(arms);
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
    }

    public enum ExperimentArm {
        BASELINE_STATIC_REWARD,
        RLVR_ONLY,
        RLVR_RLAIF,
        FULL_TRAJECTORY_GUIDED
    }

    public record ArmInput(
            ExperimentArm arm,
            String evaluationRunId,
            String modelVersion
    ) {
    }

    public record ArmMetrics(
            ExperimentArm arm,
            String evaluationRunId,
            String modelVersion,
            double averageQuality,
            double passRate,
            double averageLatencyMs,
            long totalTokens,
            double estimatedCostCny,
            double routeAccuracy,
            int failureCount,
            boolean originalRegressionGatePassed,
            double qualityDeltaVsBaseline,
            double passRateDeltaVsBaseline,
            double costRatioVsBaseline
    ) {
    }
}
