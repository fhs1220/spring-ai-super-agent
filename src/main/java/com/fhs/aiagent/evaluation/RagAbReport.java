package com.fhs.aiagent.evaluation;

import java.time.Instant;
import java.util.List;

public record RagAbReport(
        String runId,
        Instant startedAt,
        Instant completedAt,
        int caseCount,
        VariantSummary baseline,
        VariantSummary candidate,
        int candidateWins,
        int ties,
        int candidateLosses,
        double routeAccuracy,
        boolean regressionGatePassed,
        List<String> gateFailures,
        List<CaseComparison> cases,
        String reportPath
) {

    public record VariantSummary(
            RagEvaluationVariant variant,
            double averageScore,
            double passRate,
            double averageLatencyMs,
            long totalTokens,
            double estimatedCostCny,
            int usageMeasuredCases,
            int failureCount
    ) {
    }

    public record CaseComparison(
            String caseId,
            String question,
            List<String> tags,
            VariantResult baseline,
            VariantResult candidate,
            String winner,
            double scoreDelta,
            boolean criticalRegression
    ) {
    }

    public record VariantResult(
            RagVariantExecution execution,
            RagAnswerScorer.Score score
    ) {
    }
}
