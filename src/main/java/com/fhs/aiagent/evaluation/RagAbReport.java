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
        RuntimeIdentity runtimeIdentity,
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
        runtimeIdentity = runtimeIdentity == null
                ? RuntimeIdentity.unspecified()
                : runtimeIdentity;
        gateFailures = gateFailures == null ? List.of() : List.copyOf(gateFailures);
        tagSummaries = tagSummaries == null ? List.of() : List.copyOf(tagSummaries);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * 绑定一次评测实际使用的模型资产和训练配置，防止事后把同一模型
     * 重命名成不同消融组。
     */
    public record RuntimeIdentity(
            String modelVersion,
            String modelArtifactFingerprint,
            String trainingConfigFingerprint,
            String rewardSchemaVersion,
            String sourceDeployment
    ) {
        public RuntimeIdentity {
            modelVersion = normalize(modelVersion);
            modelArtifactFingerprint =
                    normalizeFingerprint(modelArtifactFingerprint);
            trainingConfigFingerprint =
                    normalizeFingerprint(trainingConfigFingerprint);
            rewardSchemaVersion = normalize(rewardSchemaVersion);
            sourceDeployment = normalize(sourceDeployment);
        }

        public static RuntimeIdentity unspecified() {
            return new RuntimeIdentity(
                    "UNSPECIFIED",
                    "UNSPECIFIED",
                    "UNSPECIFIED",
                    "UNSPECIFIED",
                    "UNSPECIFIED"
            );
        }

        public boolean isVerifiable() {
            return hasText(modelVersion)
                    && isSha256(modelArtifactFingerprint)
                    && isSha256(trainingConfigFingerprint)
                    && hasText(rewardSchemaVersion)
                    && hasText(sourceDeployment);
        }

        private static boolean isSha256(String value) {
            return value != null && value.matches("[a-fA-F0-9]{64}");
        }

        private static boolean hasText(String value) {
            return value != null
                    && !value.isBlank()
                    && !"UNSPECIFIED".equalsIgnoreCase(value);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank()
                    ? "UNSPECIFIED"
                    : value.trim();
        }

        private static String normalizeFingerprint(String value) {
            String normalized = normalize(value);
            return isSha256(normalized)
                    ? normalized.toLowerCase()
                    : normalized;
        }
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
