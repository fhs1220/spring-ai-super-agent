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
                "SHADOW", "SUCCESS", question.contains("multi"),
                question.contains("multi") ? 0.9 : 0.1,
                Map.of(AgentDomain.SAFETY, question.contains("safety") ? 0.95 : 0.1),
                8, "JEV:jev-test", 100, 0, 0.0000042);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 0.02, 60_000,
                0.5, 0.99, 0.8, 0.7, 0.5, 0.02, 30);
        List<RagAbReport.CaseComparison> comparisons = IntStream.range(0, 30)
                .mapToObj(index -> comparison(index, index < 4, index < 8))
                .toList();

        SystemOneRoutingBenchmarkReport report = service.evaluate("run-1", comparisons);

        assertThat(report.status()).isEqualTo("COMPLETE");
        assertThat(report.currentRouteAccuracy()).isZero();
        assertThat(report.systemOneRouteAccuracy()).isEqualTo(1);
        assertThat(report.systemOneMultiAgentPrecision()).isEqualTo(1);
        assertThat(report.systemOneMultiAgentRecall()).isEqualTo(1);
        assertThat(report.systemOneBalancedAccuracy()).isEqualTo(1);
        assertThat(report.qualityDelta()).isEqualTo(0.2);
        assertThat(report.systemOneMeanRegret()).isZero();
        assertThat(report.regretReductionRatio()).isEqualTo(1);
        assertThat(report.safetyPrecision()).isEqualTo(1);
        assertThat(report.safetyRecall()).isEqualTo(1);
        assertThat(report.safetyFalsePositives()).isZero();
        assertThat(report.safetyFalseNegatives()).isZero();
        assertThat(report.decisionInputTokens()).isEqualTo(3_000);
        assertThat(report.decisionCostMeasured()).isTrue();
        assertThat(report.pairedQualityVsCurrent().nonInferiorityPassed()).isTrue();
        assertThat(report.releaseGatePassed()).isTrue();
    }

    @Test
    void safetyGuardDoesNotForceMultiAgentExecution() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", false, 0.1,
                true, 0.95, Map.of(AgentDomain.SAFETY, 0.95),
                5, "JEV:jev-test", 20, 0, 0.000001);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 0.02, 60_000,
                0.5, 0, 0, 0, 0, 1, 2);

        SystemOneRoutingBenchmarkReport report = service.evaluate(
                "safety-decoupling",
                List.of(comparison(0, true, false), comparison(1, true, false)));

        assertThat(report.cases()).allSatisfy(item -> {
            assertThat(item.systemOneSafetyGuard()).isTrue();
            assertThat(item.systemOneMultiAgent()).isFalse();
        });
        assertThat(report.safetyRecall()).isEqualTo(1);
        assertThat(report.safetyFalseNegatives()).isZero();
    }

    @Test
    void majorityClassAccuracyCannotPassWithoutMultiAgentRecall() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", false, 0.1,
                false, 0.1, Map.of(AgentDomain.SAFETY, 0.1),
                5, "JEV:jev-test", 20, 0, 0.000001);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 0.02, 60_000,
                0.5, 0, 0.8, 0.7, 0.5, 1, 30);
        List<RagAbReport.CaseComparison> comparisons = IntStream.range(0, 30)
                .mapToObj(index -> comparison(index, false, index < 4))
                .toList();

        SystemOneRoutingBenchmarkReport report = service.evaluate(
                "majority-class", comparisons);

        assertThat(report.systemOneRouteAccuracy()).isEqualTo(0.8667);
        assertThat(report.systemOneMultiAgentRecall()).isZero();
        assertThat(report.systemOneBalancedAccuracy()).isEqualTo(0.5);
        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.gateFailures()).contains(
                "System One 平衡准确率低于门槛",
                "System One 多 Agent 召回率低于门槛");
    }

    private RagAbReport.CaseComparison comparison(int index, boolean safety, boolean oracleMulti) {
        double singleScore = oracleMulti ? 0.7 : 0.9;
        double multiScore = oracleMulti ? 0.9 : 0.7;
        RagAbReport.VariantResult single = result(
                RagEvaluationVariant.AGENTIC_SINGLE_AGENT, "SINGLE_AGENT",
                singleScore, 100, 0.001);
        RagAbReport.VariantResult multi = result(
                RagEvaluationVariant.AGENTIC_MULTI_AGENT, "ADAPTIVE_MULTI_AGENT",
                multiScore, 150, 0.0015);
        RagAbReport.VariantResult candidate = result(
                RagEvaluationVariant.AGENTIC_RAG_V5,
                oracleMulti ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT",
                oracleMulti ? singleScore : multiScore,
                oracleMulti ? 100 : 150,
                oracleMulti ? 0.001 : 0.0015);
        return new RagAbReport.CaseComparison(
                "case-" + index,
                (safety ? "safety " : "") + (oracleMulti ? "multi question" : "single question"),
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
