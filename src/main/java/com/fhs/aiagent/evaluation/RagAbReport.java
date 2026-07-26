package com.fhs.aiagent.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * 可复现的四路基准报告：传统 RAG、强制单 Agent、强制多 Agent和自适应路由。
 */
public record RagAbReport(
        String runId,
        String benchmarkVersion,
        String benchmarkFingerprint,
        Instant startedAt,
        Instant completedAt,
        int caseCount,
        VariantSummary baseline,
        VariantSummary forcedSingle,
        VariantSummary forcedMulti,
        VariantSummary candidate,
        int candidateWins,
        int ties,
        int candidateLosses,
        double routeAccuracy,
        boolean usageComparable,
        double adaptiveOracleCostRatio,
        boolean regressionGatePassed,
        List<String> gateFailures,
        List<TagSummary> tagSummaries,
        List<CaseComparison> cases,
        String reportPath,
        String markdownReportPath
) {

    public RagAbReport {
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
        tagSummaries = tagSummaries == null ? List.of() : List.copyOf(tagSummaries);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

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
            VariantResult forcedSingle,
            VariantResult forcedMulti,
            VariantResult candidate,
            String winner,
            double scoreDelta,
            boolean criticalRegression
    ) {

        public CaseComparison {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record VariantResult(
            RagVariantExecution execution,
            RagAnswerScorer.Score score
    ) {
    }

    public record TagSummary(
            String tag,
            int caseCount,
            double baselineAverageScore,
            double singleAgentAverageScore,
            double multiAgentAverageScore,
            double adaptiveAverageScore
    ) {
    }
}
