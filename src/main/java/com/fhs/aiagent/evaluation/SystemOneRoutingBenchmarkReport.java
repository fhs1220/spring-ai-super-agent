package com.fhs.aiagent.evaluation;

import java.util.List;

/** Counterfactual comparison of the current router and a Jev/Laya shadow router. */
public record SystemOneRoutingBenchmarkReport(
        String status,
        String model,
        int caseCount,
        int successfulDecisions,
        int failedDecisions,
        double availability,
        double currentRouteAccuracy,
        double systemOneRouteAccuracy,
        double routeAccuracyDelta,
        double currentMultiAgentPrecision,
        double systemOneMultiAgentPrecision,
        double currentMultiAgentRecall,
        double systemOneMultiAgentRecall,
        double currentBalancedAccuracy,
        double systemOneBalancedAccuracy,
        double currentAverageQuality,
        double systemOneAverageQuality,
        double qualityDelta,
        double currentAverageUtility,
        double systemOneAverageUtility,
        double oracleAverageUtility,
        double currentMeanRegret,
        double systemOneMeanRegret,
        double regretReductionRatio,
        boolean pathCostComparable,
        double currentPathCostCny,
        double systemOnePathCostCny,
        double pathCostRatio,
        long decisionInputTokens,
        long decisionOutputTokens,
        double decisionCostUsd,
        boolean decisionCostMeasured,
        double currentAverageLatencyMs,
        double systemOneAverageLatencyMs,
        double averageDecisionLatencyMs,
        double brierScore,
        double expectedCalibrationError,
        int safetyCaseCount,
        double safetyPrecision,
        double safetyRecall,
        int safetyFalsePositives,
        int safetyFalseNegatives,
        AlignmentAblationReport.PairedQualityComparison pairedQualityVsCurrent,
        boolean releaseGatePassed,
        List<String> gateFailures,
        List<CaseResult> cases
) {
    public SystemOneRoutingBenchmarkReport {
        status = status == null ? "DISABLED" : status;
        model = model == null ? "" : model;
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record CaseResult(
            String caseId,
            boolean safetyCase,
            boolean oracleMultiAgent,
            boolean currentMultiAgent,
            boolean systemOneMultiAgent,
            boolean systemOneSafetyGuard,
            double multiAgentProbability,
            double safetyProbability,
            String decisionStatus,
            long decisionLatencyMs,
            double currentQuality,
            double systemOneQuality,
            double oracleUtility,
            double currentUtility,
            double systemOneUtility
    ) {
    }
}
