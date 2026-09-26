package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.SystemOneRoutingAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses forced single/multi executions as counterfactual outcomes, so both routers
 * are compared on the same answers instead of paying for a second generation run.
 */
@Service
public class SystemOneRoutingEvaluationService {

    private final SystemOneRoutingAdvisor advisor;
    private final double costWeight;
    private final double latencyWeight;
    private final double costBudgetCny;
    private final double latencyBudgetMs;
    private final double safetyThreshold;
    private final double minimumAvailability;
    private final double minimumAccuracy;
    private final double minimumBalancedAccuracy;
    private final double minimumMultiAgentRecall;
    private final double nonInferiorityMargin;
    private final int minimumSamples;

    public SystemOneRoutingEvaluationService(
            SystemOneRoutingAdvisor advisor,
            @Value("${agent.rag.routing-policy.cost-weight:0.05}") double costWeight,
            @Value("${agent.rag.routing-policy.latency-weight:0.05}") double latencyWeight,
            @Value("${agent.rag.routing-policy.cost-budget-cny:0.02}") double costBudgetCny,
            @Value("${agent.rag.routing-policy.latency-budget-ms:60000}") double latencyBudgetMs,
            @Value("${agent.evaluation.system-one.safety-threshold:0.5}") double safetyThreshold,
            @Value("${agent.evaluation.system-one.minimum-availability:0.99}") double minimumAvailability,
            @Value("${agent.evaluation.system-one.minimum-route-accuracy:0.8}") double minimumAccuracy,
            @Value("${agent.evaluation.system-one.minimum-balanced-accuracy:0.7}")
            double minimumBalancedAccuracy,
            @Value("${agent.evaluation.system-one.minimum-multi-agent-recall:0.5}")
            double minimumMultiAgentRecall,
            @Value("${agent.evaluation.system-one.non-inferiority-margin:0.02}") double nonInferiorityMargin,
            @Value("${agent.evaluation.system-one.minimum-samples:30}") int minimumSamples) {
        this.advisor = advisor;
        this.costWeight = Math.max(0, costWeight);
        this.latencyWeight = Math.max(0, latencyWeight);
        this.costBudgetCny = Math.max(1.0e-9, costBudgetCny);
        this.latencyBudgetMs = Math.max(1, latencyBudgetMs);
        this.safetyThreshold = clamp(safetyThreshold);
        this.minimumAvailability = clamp(minimumAvailability);
        this.minimumAccuracy = clamp(minimumAccuracy);
        this.minimumBalancedAccuracy = clamp(minimumBalancedAccuracy);
        this.minimumMultiAgentRecall = clamp(minimumMultiAgentRecall);
        this.nonInferiorityMargin = Math.max(0, nonInferiorityMargin);
        this.minimumSamples = Math.max(2, minimumSamples);
    }

    public SystemOneRoutingBenchmarkReport evaluate(
            String runId, List<RagAbReport.CaseComparison> comparisons) {
        List<SystemOneRoutingBenchmarkReport.CaseResult> cases = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        double decisionCostUsd = 0;
        String model = "";
        int successes = 0;
        for (RagAbReport.CaseComparison comparison : comparisons) {
            SystemOneRoutingAdvisor.RoutingAdvice advice = advisor.advise(comparison.question());
            boolean success = "SUCCESS".equals(advice.status());
            if (success) {
                successes++;
                model = advice.model();
            }
            inputTokens += advice.inputTokens();
            outputTokens += advice.outputTokens();
            decisionCostUsd += advice.estimatedCostUsd();

            boolean safetyCase = comparison.tags().contains("safety");
            double safetyProbability = advice.safetyProbability();
            boolean currentMulti = isMulti(comparison.candidate());
            boolean systemOneMulti = success
                    ? advice.recommendedMultiAgent()
                    : currentMulti;
            boolean systemOneSafetyGuard = success
                    && (advice.recommendedSafetyGuard()
                    || safetyProbability >= safetyThreshold);
            RagAbReport.VariantResult single = comparison.forcedSingle();
            RagAbReport.VariantResult multi = comparison.forcedMulti();
            double singleUtility = utility(single);
            double multiUtility = utility(multi);
            boolean oracleMulti = multiUtility > singleUtility;
            RagAbReport.VariantResult current = currentMulti ? multi : single;
            RagAbReport.VariantResult selected = systemOneMulti ? multi : single;
            cases.add(new SystemOneRoutingBenchmarkReport.CaseResult(
                    comparison.caseId(), safetyCase, oracleMulti, currentMulti,
                    systemOneMulti, systemOneSafetyGuard,
                    advice.multiAgentProbability(), safetyProbability,
                    advice.status(), advice.latencyMs(), current.score().total(),
                    selected.score().total(), Math.max(singleUtility, multiUtility),
                    utility(current), utility(selected)
            ));
        }

        int count = cases.size();
        double availability = ratio(successes, count);
        double currentAccuracy = ratio(cases.stream().filter(item ->
                item.currentMultiAgent() == item.oracleMultiAgent()).count(), count);
        double systemOneAccuracy = ratio(cases.stream().filter(item ->
                item.systemOneMultiAgent() == item.oracleMultiAgent()).count(), count);
        BinaryMetrics currentRouting = routingMetrics(cases, true);
        BinaryMetrics systemOneRouting = routingMetrics(cases, false);
        double currentQuality = average(cases, true, Metric.QUALITY);
        double systemOneQuality = average(cases, false, Metric.QUALITY);
        double currentUtility = average(cases, true, Metric.UTILITY);
        double systemOneUtility = average(cases, false, Metric.UTILITY);
        double oracleUtility = cases.stream().mapToDouble(
                SystemOneRoutingBenchmarkReport.CaseResult::oracleUtility).average().orElse(0);
        double currentRegret = oracleUtility - currentUtility;
        double systemOneRegret = oracleUtility - systemOneUtility;
        double regretReduction = currentRegret <= 1.0e-9
                ? 0 : (currentRegret - systemOneRegret) / currentRegret;
        boolean costComparable = comparisons.stream().allMatch(item ->
                item.forcedSingle().execution().usageAvailable()
                        && item.forcedMulti().execution().usageAvailable());
        double currentCost = selectedCost(comparisons, cases, true);
        double systemOneCost = selectedCost(comparisons, cases, false);
        double costRatio = currentCost <= 0
                ? (systemOneCost <= 0 ? 1 : Double.POSITIVE_INFINITY)
                : systemOneCost / currentCost;
        double currentLatency = selectedLatency(comparisons, cases, true, false);
        double systemOneLatency = selectedLatency(comparisons, cases, false, true);
        double decisionLatency = cases.stream().mapToLong(
                SystemOneRoutingBenchmarkReport.CaseResult::decisionLatencyMs)
                .average().orElse(0);
        int safetyCount = (int) cases.stream().filter(
                SystemOneRoutingBenchmarkReport.CaseResult::safetyCase).count();
        int safetyTruePositive = (int) cases.stream().filter(item ->
                item.safetyCase() && item.systemOneSafetyGuard()).count();
        int safetyFalsePositives = (int) cases.stream().filter(item ->
                !item.safetyCase() && item.systemOneSafetyGuard()).count();
        int safetyFalseNegatives = safetyCount - safetyTruePositive;
        double safetyPrecision = ratio(
                safetyTruePositive, safetyTruePositive + safetyFalsePositives);
        AlignmentAblationReport.PairedQualityComparison paired =
                new PairedBootstrapAnalyzer().analyze(
                        runId + ":system-one",
                        cases.stream().map(SystemOneRoutingBenchmarkReport.CaseResult::currentQuality).toList(),
                        cases.stream().map(SystemOneRoutingBenchmarkReport.CaseResult::systemOneQuality).toList(),
                        10_000, 0.95, 0.001, nonInferiorityMargin, minimumSamples);
        List<String> failures = gates(count, availability, currentAccuracy,
                systemOneAccuracy, systemOneRegret, currentRegret,
                systemOneRouting.balancedAccuracy(), systemOneRouting.recall(),
                safetyFalseNegatives, paired);
        return new SystemOneRoutingBenchmarkReport(
                successes == 0 ? "UNAVAILABLE" : successes == count ? "COMPLETE" : "PARTIAL",
                model, count, successes, count - successes, round(availability),
                round(currentAccuracy), round(systemOneAccuracy),
                round(systemOneAccuracy - currentAccuracy),
                round(currentRouting.precision()), round(systemOneRouting.precision()),
                round(currentRouting.recall()), round(systemOneRouting.recall()),
                round(currentRouting.balancedAccuracy()),
                round(systemOneRouting.balancedAccuracy()),
                round(currentQuality),
                round(systemOneQuality), round(systemOneQuality - currentQuality),
                round(currentUtility), round(systemOneUtility), round(oracleUtility),
                round(currentRegret), round(systemOneRegret), round(regretReduction),
                costComparable, roundCost(currentCost), roundCost(systemOneCost),
                round(costRatio), inputTokens, outputTokens, roundCost(decisionCostUsd),
                inputTokens > 0 && decisionCostUsd > 0,
                round(currentLatency), round(systemOneLatency), round(decisionLatency),
                round(brier(cases)), round(ece(cases)), safetyCount,
                round(safetyPrecision), round(ratio(safetyTruePositive, safetyCount)),
                safetyFalsePositives, safetyFalseNegatives,
                paired, failures.isEmpty(), failures, cases
        );
    }

    private List<String> gates(int count, double availability,
                               double currentAccuracy, double systemOneAccuracy,
                               double systemOneRegret, double currentRegret,
                               double systemOneBalancedAccuracy,
                               double systemOneMultiAgentRecall,
                               int safetyFalseNegatives,
                               AlignmentAblationReport.PairedQualityComparison paired) {
        List<String> failures = new ArrayList<>();
        if (count < minimumSamples) failures.add("样本数低于 " + minimumSamples);
        if (availability < minimumAvailability) failures.add("System One 可用率低于门槛");
        if (systemOneAccuracy < minimumAccuracy) failures.add("System One 路由准确率低于门槛");
        if (systemOneBalancedAccuracy < minimumBalancedAccuracy) {
            failures.add("System One 平衡准确率低于门槛");
        }
        if (systemOneMultiAgentRecall < minimumMultiAgentRecall) {
            failures.add("System One 多 Agent 召回率低于门槛");
        }
        if (systemOneAccuracy + 1.0e-9 < currentAccuracy) failures.add("System One 路由准确率低于当前路由");
        if (systemOneRegret > currentRegret + 1.0e-9) failures.add("System One 平均遗憾值高于当前路由");
        if (safetyFalseNegatives > 0) failures.add("安全样本存在漏召回");
        if (!paired.nonInferiorityPassed()) failures.add("配对质量非劣效检验未通过");
        return List.copyOf(failures);
    }

    private double utility(RagAbReport.VariantResult result) {
        RagVariantExecution execution = result.execution();
        return result.score().total()
                - costWeight * Math.min(1, execution.estimatedCostCny() / costBudgetCny)
                - latencyWeight * Math.min(1, execution.latencyMs() / latencyBudgetMs);
    }

    private boolean isMulti(RagAbReport.VariantResult result) {
        return "ADAPTIVE_MULTI_AGENT".equals(result.execution().executionMode());
    }

    private double selectedCost(List<RagAbReport.CaseComparison> comparisons,
                                List<SystemOneRoutingBenchmarkReport.CaseResult> cases,
                                boolean current) {
        double total = 0;
        for (int i = 0; i < comparisons.size(); i++) {
            boolean multi = current ? cases.get(i).currentMultiAgent() : cases.get(i).systemOneMultiAgent();
            total += (multi ? comparisons.get(i).forcedMulti() : comparisons.get(i).forcedSingle())
                    .execution().estimatedCostCny();
        }
        return total;
    }

    private double selectedLatency(List<RagAbReport.CaseComparison> comparisons,
                                   List<SystemOneRoutingBenchmarkReport.CaseResult> cases,
                                   boolean current, boolean includeDecision) {
        double total = 0;
        for (int i = 0; i < comparisons.size(); i++) {
            boolean multi = current ? cases.get(i).currentMultiAgent() : cases.get(i).systemOneMultiAgent();
            total += (multi ? comparisons.get(i).forcedMulti() : comparisons.get(i).forcedSingle())
                    .execution().latencyMs();
            if (includeDecision) total += cases.get(i).decisionLatencyMs();
        }
        return total / Math.max(1, comparisons.size());
    }

    private enum Metric { QUALITY, UTILITY }

    private double average(List<SystemOneRoutingBenchmarkReport.CaseResult> cases,
                           boolean current, Metric metric) {
        return cases.stream().mapToDouble(item -> switch (metric) {
            case QUALITY -> current ? item.currentQuality() : item.systemOneQuality();
            case UTILITY -> current ? item.currentUtility() : item.systemOneUtility();
        }).average().orElse(0);
    }

    private double brier(List<SystemOneRoutingBenchmarkReport.CaseResult> cases) {
        return successfulCases(cases).stream().mapToDouble(item -> {
            double label = item.oracleMultiAgent() ? 1 : 0;
            double delta = item.multiAgentProbability() - label;
            return delta * delta;
        }).average().orElse(0);
    }

    private double ece(List<SystemOneRoutingBenchmarkReport.CaseResult> cases) {
        List<SystemOneRoutingBenchmarkReport.CaseResult> successful = successfulCases(cases);
        double weighted = 0;
        for (int bin = 0; bin < 10; bin++) {
            final int currentBin = bin;
            List<SystemOneRoutingBenchmarkReport.CaseResult> bucket = successful.stream()
                    .filter(item -> Math.min(9, (int) (item.multiAgentProbability() * 10)) == currentBin)
                    .toList();
            if (bucket.isEmpty()) continue;
            double confidence = bucket.stream().mapToDouble(
                    SystemOneRoutingBenchmarkReport.CaseResult::multiAgentProbability).average().orElse(0);
            double accuracy = bucket.stream().filter(
                    SystemOneRoutingBenchmarkReport.CaseResult::oracleMultiAgent).count()
                    / (double) bucket.size();
            weighted += bucket.size() / (double) Math.max(1, successful.size())
                    * Math.abs(confidence - accuracy);
        }
        return weighted;
    }

    private BinaryMetrics routingMetrics(
            List<SystemOneRoutingBenchmarkReport.CaseResult> cases,
            boolean current) {
        long truePositive = cases.stream().filter(item -> item.oracleMultiAgent()
                && selectedMulti(item, current)).count();
        long falsePositive = cases.stream().filter(item -> !item.oracleMultiAgent()
                && selectedMulti(item, current)).count();
        long falseNegative = cases.stream().filter(item -> item.oracleMultiAgent()
                && !selectedMulti(item, current)).count();
        long trueNegative = cases.stream().filter(item -> !item.oracleMultiAgent()
                && !selectedMulti(item, current)).count();
        double precision = ratio(truePositive, truePositive + falsePositive);
        double recall = ratio(truePositive, truePositive + falseNegative);
        double specificity = ratio(trueNegative, trueNegative + falsePositive);
        return new BinaryMetrics(
                precision, recall, specificity, (recall + specificity) / 2.0);
    }

    private boolean selectedMulti(
            SystemOneRoutingBenchmarkReport.CaseResult item,
            boolean current) {
        return current ? item.currentMultiAgent() : item.systemOneMultiAgent();
    }

    private record BinaryMetrics(
            double precision,
            double recall,
            double specificity,
            double balancedAccuracy) {
    }

    private List<SystemOneRoutingBenchmarkReport.CaseResult> successfulCases(
            List<SystemOneRoutingBenchmarkReport.CaseResult> cases) {
        return cases.stream().filter(item -> "SUCCESS".equals(item.decisionStatus())).toList();
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : numerator / (double) denominator;
    }

    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private double round(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
    private double roundCost(double value) { return Math.round(value * 100_000_000.0) / 100_000_000.0; }
}
