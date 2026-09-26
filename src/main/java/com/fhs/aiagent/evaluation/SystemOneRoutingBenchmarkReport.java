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
        List<CaseResult> cases,
        String schemaVersion,
        String utilityVersion,
        SystemOneUtilityPolicy utilityPolicy,
        FixedStrategyBaseline alwaysSingle,
        FixedStrategyBaseline alwaysMulti,
        AlignmentAblationReport.PairedQualityComparison pairedQualityVsAlwaysSingle,
        AlignmentAblationReport.PairedQualityComparison pairedQualityVsAlwaysMulti,
        int invalidCounterfactualPairCount,
        String utilityCostScope,
        String utilityLatencyScope,
        boolean decisionApiCostEstimateComplete,
        int decisionApiCostUnknownCount,
        boolean endToEndCostComparable
) {
    public static final String SCHEMA_VERSION = "system-one-routing-benchmark-v2";

    public SystemOneRoutingBenchmarkReport {
        status = status == null ? "DISABLED" : status;
        model = model == null ? "" : model;
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
        cases = cases == null ? List.of() : List.copyOf(cases);
        // Deserializing historical reports must not silently relabel their clipped utility as v2.
        schemaVersion = schemaVersion == null ? "legacy-unversioned" : schemaVersion;
        utilityVersion = utilityVersion == null ? "legacy-unspecified" : utilityVersion;
        utilityCostScope = utilityCostScope == null ? "LEGACY_UNSPECIFIED" : utilityCostScope;
        utilityLatencyScope = utilityLatencyScope == null ? "LEGACY_UNSPECIFIED" : utilityLatencyScope;
    }

    public record FixedStrategyBaseline(
            String strategy,
            int caseCount,
            double averageQuality,
            double averageUtility,
            double meanRegret,
            double pathCostCny,
            boolean pathCostComparable,
            double averageLatencyMs,
            double routeAccuracy,
            double multiAgentPrecision,
            double multiAgentRecall,
            double balancedAccuracy,
            int failureCount
    ) {
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
