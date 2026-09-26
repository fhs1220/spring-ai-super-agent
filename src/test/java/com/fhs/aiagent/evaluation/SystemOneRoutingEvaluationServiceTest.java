package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.AgentDomain;
import com.fhs.aiagent.rag.multiagent.SystemOneRoutingAdvisor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SystemOneRoutingEvaluationServiceTest {

    @Test
    void comparesAgainstUtilityOracleAndProducesReleaseGradeStatistics() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", true, 0.9,
                Map.of(AgentDomain.SAFETY, question.contains("safety") ? 0.95 : 0.1),
                8, "JEV:jev-test", 100, 0, 0.0000042);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 0.02, 60_000,
                0.5, 0.99, 0.8, 0.02, 30);
        List<RagAbReport.CaseComparison> comparisons = IntStream.range(0, 30)
                .mapToObj(index -> comparison(index, index < 3))
                .toList();

        SystemOneRoutingBenchmarkReport report = service.evaluate("run-1", comparisons);

        assertThat(report.status()).isEqualTo("COMPLETE");
        assertThat(report.currentRouteAccuracy()).isZero();
        assertThat(report.systemOneRouteAccuracy()).isEqualTo(1);
        assertThat(report.qualityDelta()).isEqualTo(0.2);
        assertThat(report.systemOneMeanRegret()).isZero();
        assertThat(report.regretReductionRatio()).isEqualTo(1);
        assertThat(report.safetyRecall()).isEqualTo(1);
        assertThat(report.safetyFalseNegatives()).isZero();
        assertThat(report.decisionInputTokens()).isEqualTo(3_000);
        assertThat(report.decisionCostMeasured()).isTrue();
        assertThat(report.pairedQualityVsCurrent().nonInferiorityPassed()).isTrue();
        assertThat(report.releaseGatePassed()).isTrue();
    }

    private RagAbReport.CaseComparison comparison(int index, boolean safety) {
        RagAbReport.VariantResult single = result(
                RagEvaluationVariant.AGENTIC_SINGLE_AGENT, "SINGLE_AGENT", 0.7, 100, 0.001);
        RagAbReport.VariantResult multi = result(
                RagEvaluationVariant.AGENTIC_MULTI_AGENT, "ADAPTIVE_MULTI_AGENT", 0.9, 150, 0.0015);
        RagAbReport.VariantResult candidate = result(
                RagEvaluationVariant.AGENTIC_RAG_V5, "SINGLE_AGENT", 0.7, 100, 0.001);
        return new RagAbReport.CaseComparison(
                "case-" + index, safety ? "safety question" : "normal question",
                safety ? List.of("safety") : List.of("simple"),
                single, single, multi, candidate, "TIE", 0, false);
    }

    private RagAbReport.VariantResult result(RagEvaluationVariant variant,
                                              String mode,
                                              double score,
                                              long latency,
                                              double cost) {
        return new RagAbReport.VariantResult(
                new RagVariantExecution(variant, "answer", latency, 100, cost,
                        true, mode, ""),
                new RagAnswerScorer.Score(score, score, 1, 1, 1, 1, true));
    }
}
