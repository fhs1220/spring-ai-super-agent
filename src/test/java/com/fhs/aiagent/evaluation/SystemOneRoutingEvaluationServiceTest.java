package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.multiagent.AgentDomain;
import com.fhs.aiagent.rag.multiagent.SystemOneRoutingAdvisor;
import com.fhs.aiagent.rl.model.AgentTrace;
import com.fhs.aiagent.rl.model.AgentTraceStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.ArrayList;
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
        assertThat(report.decisionApiCostEstimateComplete()).isTrue();
        assertThat(report.decisionApiCostUnknownCount()).isZero();
        assertThat(report.utilityCostScope()).isEqualTo("GENERATOR_COST_ONLY");
        assertThat(report.utilityLatencyScope()).isEqualTo("GENERATION_PLUS_SYSTEM_ONE_DECISION");
        assertThat(report.endToEndCostComparable()).isFalse();
        assertThat(report.pairedQualityVsCurrent().nonInferiorityPassed()).isTrue();
        assertThat(report.releaseGatePassed()).isTrue();
        assertThat(report.schemaVersion()).isEqualTo(SystemOneRoutingBenchmarkReport.SCHEMA_VERSION);
        assertThat(report.utilityVersion()).isEqualTo(SystemOneUtilityPolicy.VERSION);
        assertThat(report.utilityPolicy().costScaleCny()).isEqualTo(0.02);
        assertThat(report.alwaysSingle().routeAccuracy()).isEqualTo(0.7333);
        assertThat(report.alwaysSingle().multiAgentRecall()).isZero();
        assertThat(report.alwaysSingle().averageQuality()).isEqualTo(0.8467);
        assertThat(report.alwaysMulti().routeAccuracy()).isEqualTo(0.2667);
        assertThat(report.alwaysMulti().multiAgentRecall()).isEqualTo(1);
        assertThat(report.alwaysMulti().averageQuality()).isEqualTo(0.7533);
        assertThat(report.pairedQualityVsAlwaysSingle().nonInferiorityPassed()).isTrue();
        assertThat(report.pairedQualityVsAlwaysMulti().nonInferiorityPassed()).isTrue();
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

    @Test
    void missingCostTelemetryBlocksAnOtherwisePassingPolicy() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", question.contains("multi"),
                question.contains("multi") ? 0.9 : 0.1,
                Map.of(AgentDomain.SAFETY, question.contains("safety") ? 0.95 : 0.1),
                8, "JEV:test", 100, 0, 0.0000042);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 1.0, 60_000,
                0.5, 0.99, 0.8, 0.7, 0.5, 0.02, 30);
        List<RagAbReport.CaseComparison> comparisons = new ArrayList<>(IntStream.range(0, 30)
                .mapToObj(index -> comparison(index, index < 4, index < 8)).toList());
        RagAbReport.CaseComparison first = comparisons.get(0);
        RagVariantExecution original = first.forcedSingle().execution();
        RagAbReport.VariantResult unknownCost = new RagAbReport.VariantResult(
                new RagVariantExecution(original.variant(), original.answer(), original.latencyMs(),
                        original.totalTokens(), 0, false, original.executionMode(), ""),
                first.forcedSingle().score());
        comparisons.set(0, new RagAbReport.CaseComparison(first.caseId(), first.question(), first.tags(),
                first.baseline(), unknownCost, first.forcedMulti(), first.candidate(), "TIE", 0, false));

        SystemOneRoutingBenchmarkReport report = service.evaluate("missing-usage", comparisons);

        assertThat(report.pathCostComparable()).isFalse();
        assertThat(report.alwaysSingle().pathCostComparable()).isFalse();
        assertThat(report.alwaysMulti().pathCostComparable()).isTrue();
        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.gateFailures()).containsExactly(
                "强制单/多 Agent 费用计量不完整，成本与效用不可可靠比较");
    }

    @Test
    void matchingWeakCurrentRouterCannotPassWhenAlwaysSingleIsBetter() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", true, 0.9, false, 0.1, Map.of(),
                5, "JEV:test", 20, 0, 0.000001);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 1.0, 60_000,
                0.5, 0, 0, 0, 0, 0.02, 2);
        SystemOneRoutingBenchmarkReport report = service.evaluate("weak-rule-baseline", List.of(
                comparison(0, false, false), comparison(1, false, false)));

        assertThat(report.pairedQualityVsCurrent().nonInferiorityPassed()).isTrue();
        assertThat(report.alwaysSingle().routeAccuracy()).isEqualTo(1);
        assertThat(report.alwaysSingle().averageQuality()).isEqualTo(0.9);
        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.gateFailures()).contains(
                "System One 平均效用低于最佳固定单/多 Agent 策略",
                "相对 always-single 的配对质量非劣效检验未通过");
    }

    @Test
    void higherCostsBeyondOldCapCanChangeTheOracleLabel() {
        RagAbReport.VariantResult single = result(RagEvaluationVariant.AGENTIC_SINGLE_AGENT,
                "SINGLE_AGENT", 0.90, 0, 0.20);
        RagAbReport.VariantResult multi = result(RagEvaluationVariant.AGENTIC_MULTI_AGENT,
                "ADAPTIVE_MULTI_AGENT", 0.92, 0, 1.20);
        RagAbReport.CaseComparison comparison = new RagAbReport.CaseComparison(
                "cost-sensitive", "question", List.of(), single, single, multi, multi, "TIE", 0, false);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                SystemOneRoutingAdvisor.disabled(), 0.05, 0.05, 1.0, 60_000,
                0.5, 0, 0, 0, 0, 0.02, 2);

        SystemOneRoutingBenchmarkReport report = service.evaluate("cost-sensitive", List.of(comparison));

        assertThat(report.cases().get(0).oracleMultiAgent()).isFalse();
        assertThat(report.alwaysSingle().averageUtility()).isEqualTo(0.89);
        assertThat(report.alwaysMulti().averageUtility()).isEqualTo(0.86);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "empty", "fallback"})
    void invalidCounterfactualPairCannotPassEvenWithMeasuredCost(String invalidReason) {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", question.contains("multi"),
                question.contains("multi") ? 0.9 : 0.1,
                Map.of(AgentDomain.SAFETY, question.contains("safety") ? 0.95 : 0.1),
                8, "JEV:test", 100, 0, 0.0000042);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 1.0, 60_000,
                0.5, 0.99, 0.8, 0.7, 0.5, 0.02, 30);
        List<RagAbReport.CaseComparison> comparisons = new ArrayList<>(IntStream.range(0, 30)
                .mapToObj(index -> comparison(index, index < 4, index < 8)).toList());
        RagAbReport.CaseComparison first = comparisons.getFirst();
        RagVariantExecution original = first.forcedMulti().execution();
        AgentTrace trace = "fallback".equals(invalidReason)
                ? new AgentTrace(150, "ADAPTIVE_MULTI_AGENT", List.of(
                    new AgentTraceStep("GENERATE", "fallback", "single answer", 150, true, List.of())),
                    List.of(), null)
                : null;
        RagAbReport.VariantResult invalidMulti = new RagAbReport.VariantResult(
                new RagVariantExecution(original.variant(), "empty".equals(invalidReason) ? "" : "answer",
                        original.latencyMs(), original.totalTokens(), original.estimatedCostCny(), true,
                        original.executionMode(), "failure".equals(invalidReason) ? "failed" : "", trace),
                first.forcedMulti().score());
        comparisons.set(0, new RagAbReport.CaseComparison(first.caseId(), first.question(), first.tags(),
                first.baseline(), first.forcedSingle(), invalidMulti, first.candidate(), "TIE", 0, false));

        SystemOneRoutingBenchmarkReport report = service.evaluate("invalid-pair", comparisons);

        assertThat(report.pathCostComparable()).isTrue();
        assertThat(report.invalidCounterfactualPairCount()).isEqualTo(1);
        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.gateFailures()).containsExactly(
                "反事实配对包含失败、空答案或多 Agent 降级，无法通过发布门禁");
    }

    @Test
    void slowPerfectRouterLosesToFixedStrategyAfterDecisionLatencyIsCharged() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", question.contains("multi"),
                question.contains("multi") ? 0.9 : 0.1,
                Map.of(AgentDomain.SAFETY, question.contains("safety") ? 0.95 : 0.1),
                600_000, "JEV:test", 100, 0, 0.0000042);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 1.0, 60_000,
                0.5, 0.99, 0.8, 0.7, 0.5, 0.02, 30);

        SystemOneRoutingBenchmarkReport report = service.evaluate("slow-perfect-router",
                IntStream.range(0, 30).mapToObj(index -> comparison(index, index < 4, index < 8)).toList());

        assertThat(report.systemOneRouteAccuracy()).isEqualTo(1);
        assertThat(report.systemOneAverageQuality()).isEqualTo(0.9);
        assertThat(report.systemOneAverageUtility()).isLessThan(report.alwaysSingle().averageUtility());
        assertThat(report.systemOneMeanRegret()).isEqualTo(0.5);
        assertThat(report.releaseGatePassed()).isFalse();
        assertThat(report.gateFailures()).contains("System One 平均效用低于最佳固定单/多 Agent 策略");
    }

    @Test
    void partialDecisionBillingCannotBeReportedAsCompleteOrMixedWithCny() {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", question.contains("multi"),
                question.contains("multi") ? 0.9 : 0.1,
                Map.of(AgentDomain.SAFETY, question.contains("safety") ? 0.95 : 0.1),
                8, "JEV:test", 100, 0, question.contains("safety") ? 0 : 0.01);
        SystemOneRoutingEvaluationService service = new SystemOneRoutingEvaluationService(
                advisor, 0.05, 0.05, 1.0, 60_000,
                0.5, 0.99, 0.8, 0.7, 0.5, 0.02, 30);

        SystemOneRoutingBenchmarkReport report = service.evaluate("partial-decision-cost",
                IntStream.range(0, 30).mapToObj(index -> comparison(index, index < 4, index < 8)).toList());

        assertThat(report.decisionCostUsd()).isEqualTo(0.26);
        assertThat(report.systemOnePathCostCny()).isEqualTo(0.034);
        assertThat(report.decisionCostMeasured()).isFalse();
        assertThat(report.decisionApiCostEstimateComplete()).isFalse();
        assertThat(report.decisionApiCostUnknownCount()).isEqualTo(4);
        assertThat(report.utilityCostScope()).isEqualTo("GENERATOR_COST_ONLY");
        assertThat(report.endToEndCostComparable()).isFalse();
    }

    @Test
    void deserializingHistoricalReportDoesNotClaimV2Utility() throws Exception {
        SystemOneRoutingBenchmarkReport report = new ObjectMapper().readValue(
                "{\"status\":\"COMPLETE\"}", SystemOneRoutingBenchmarkReport.class);

        assertThat(report.schemaVersion()).isEqualTo("legacy-unversioned");
        assertThat(report.utilityVersion()).isEqualTo("legacy-unspecified");
        assertThat(report.utilityPolicy()).isNull();
        assertThat(report.alwaysSingle()).isNull();
        assertThat(report.utilityCostScope()).isEqualTo("LEGACY_UNSPECIFIED");
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
