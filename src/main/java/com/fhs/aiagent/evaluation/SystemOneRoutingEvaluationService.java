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
    private final SystemOneUtilityPolicy utilityPolicy;
    private final double safetyThreshold;
    private final double minimumAvailability;
    private final double minimumAccuracy;
    private final double minimumBalancedAccuracy;
    private final double minimumMultiAgentRecall;
    private final double nonInferiorityMargin;
    private final int minimumSamples;

    public SystemOneRoutingEvaluationService(
            SystemOneRoutingAdvisor advisor,
            @Value("${agent.evaluation.system-one.utility.cost-weight:0.05}") double costWeight,
            @Value("${agent.evaluation.system-one.utility.latency-weight:0.05}") double latencyWeight,
            @Value("${agent.evaluation.system-one.utility.cost-scale-cny:1.0}") double costScaleCny,
            @Value("${agent.evaluation.system-one.utility.latency-scale-ms:60000}") double latencyScaleMs,
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
        this.utilityPolicy = new SystemOneUtilityPolicy(
                costWeight, costScaleCny, latencyWeight, latencyScaleMs);
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
        int invalidPairs = 0;
        int decisionUnknownCostCount = 0;
        for (RagAbReport.CaseComparison comparison : comparisons) {
            SystemOneRoutingAdvisor.RoutingAdvice advice = advisor.advise(comparison.question());
            boolean success = "SUCCESS".equals(advice.status());
            if (success) {
                successes++;
                model = advice.model();
            }
            inputTokens += advice.inputTokens();
            outputTokens += advice.outputTokens();
            if (Double.isFinite(advice.estimatedCostUsd()) && advice.estimatedCostUsd() > 0
                    && (advice.inputTokens() > 0 || advice.outputTokens() > 0)) {
                decisionCostUsd += advice.estimatedCostUsd();
            } else {
                // There is no explicit known-zero-price contract in RoutingAdvice.
                decisionUnknownCostCount++;
            }

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
            if (!validPair(single.execution(), multi.execution())) invalidPairs++;
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
                    utility(current), selectedUtility(selected, advice.latencyMs())
            ));
        }

        int count = cases.size();
        boolean decisionCostComplete = count > 0 && decisionUnknownCostCount == 0;
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
        boolean costComparable = !comparisons.isEmpty() && comparisons.stream().allMatch(item ->
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
        SystemOneRoutingBenchmarkReport.FixedStrategyBaseline alwaysSingle =
                fixedBaseline(comparisons, cases, false, oracleUtility);
        SystemOneRoutingBenchmarkReport.FixedStrategyBaseline alwaysMulti =
                fixedBaseline(comparisons, cases, true, oracleUtility);
        AlignmentAblationReport.PairedQualityComparison pairedVsSingle =
                pairedVsFixed(runId, comparisons, cases, false);
        AlignmentAblationReport.PairedQualityComparison pairedVsMulti =
                pairedVsFixed(runId, comparisons, cases, true);
        List<String> failures = gates(count, availability, currentAccuracy,
                systemOneAccuracy, systemOneRegret, currentRegret,
                systemOneRouting.balancedAccuracy(), systemOneRouting.recall(),
                safetyFalseNegatives, costComparable, invalidPairs, paired,
                systemOneUtility,
                comparisons.stream().mapToDouble(item -> utility(item.forcedSingle())).average().orElse(0),
                comparisons.stream().mapToDouble(item -> utility(item.forcedMulti())).average().orElse(0),
                pairedVsSingle, pairedVsMulti);
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
                decisionCostComplete,
                round(currentLatency), round(systemOneLatency), round(decisionLatency),
                round(brier(cases)), round(ece(cases)), safetyCount,
                round(safetyPrecision), round(ratio(safetyTruePositive, safetyCount)),
                safetyFalsePositives, safetyFalseNegatives,
                paired, failures.isEmpty(), failures, cases,
                SystemOneRoutingBenchmarkReport.SCHEMA_VERSION,
                SystemOneUtilityPolicy.VERSION, utilityPolicy,
                alwaysSingle, alwaysMulti, pairedVsSingle, pairedVsMulti,
                invalidPairs, "GENERATOR_COST_ONLY", "GENERATION_PLUS_SYSTEM_ONE_DECISION",
                decisionCostComplete, decisionUnknownCostCount, false
        );
    }

    private List<String> gates(int count, double availability,
                               double currentAccuracy, double systemOneAccuracy,
                               double systemOneRegret, double currentRegret,
                               double systemOneBalancedAccuracy,
                               double systemOneMultiAgentRecall,
                               int safetyFalseNegatives,
                               boolean costComparable,
                               int invalidPairs,
                               AlignmentAblationReport.PairedQualityComparison paired,
                               double systemOneUtility, double singleUtility, double multiUtility,
                               AlignmentAblationReport.PairedQualityComparison pairedVsSingle,
                               AlignmentAblationReport.PairedQualityComparison pairedVsMulti) {
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
        if (!costComparable) failures.add("强制单/多 Agent 费用计量不完整，成本与效用不可可靠比较");
        if (invalidPairs > 0) failures.add("反事实配对包含失败、空答案或多 Agent 降级，无法通过发布门禁");
        if (!paired.nonInferiorityPassed()) failures.add("配对质量非劣效检验未通过");
        if (systemOneUtility + 1.0e-9 < Math.max(singleUtility, multiUtility)) {
            failures.add("System One 平均效用低于最佳固定单/多 Agent 策略");
        }
        if (!pairedVsSingle.nonInferiorityPassed()) {
            failures.add("相对 always-single 的配对质量非劣效检验未通过");
        }
        if (!pairedVsMulti.nonInferiorityPassed()) {
            failures.add("相对 always-multi 的配对质量非劣效检验未通过");
        }
        return List.copyOf(failures);
    }

    private double utility(RagAbReport.VariantResult result) {
        RagVariantExecution execution = result.execution();
        return utilityPolicy.utility(
                result.score().total(), execution.estimatedCostCny(), execution.latencyMs());
    }

    private double selectedUtility(RagAbReport.VariantResult selected, long decisionLatencyMs) {
        RagVariantExecution execution = selected.execution();
        // Decision API estimates are USD while generation estimates are CNY. They remain
        // separately reported, never implicitly converted; this utility is generator-cost-only.
        return utilityPolicy.utility(selected.score().total(), execution.estimatedCostCny(),
                (double) execution.latencyMs() + decisionLatencyMs);
    }

    private boolean validPair(RagVariantExecution single, RagVariantExecution multi) {
        return single.succeeded() && multi.succeeded()
                && single.answer() != null && !single.answer().isBlank()
                && multi.answer() != null && !multi.answer().isBlank()
                && "SINGLE_AGENT".equals(single.executionMode())
                && "ADAPTIVE_MULTI_AGENT".equals(multi.executionMode())
                && !multi.fellBackToSingle();
    }

    private SystemOneRoutingBenchmarkReport.FixedStrategyBaseline fixedBaseline(
            List<RagAbReport.CaseComparison> comparisons,
            List<SystemOneRoutingBenchmarkReport.CaseResult> cases,
            boolean multi, double oracleUtility) {
        List<RagAbReport.VariantResult> outcomes = comparisons.stream()
                .map(item -> multi ? item.forcedMulti() : item.forcedSingle()).toList();
        long positives = cases.stream().filter(
                SystemOneRoutingBenchmarkReport.CaseResult::oracleMultiAgent).count();
        long negatives = cases.size() - positives;
        double averageUtility = outcomes.stream().mapToDouble(this::utility).average().orElse(0);
        double recall = multi ? ratio(positives, positives) : 0;
        double specificity = multi ? 0 : ratio(negatives, negatives);
        return new SystemOneRoutingBenchmarkReport.FixedStrategyBaseline(
                multi ? "ALWAYS_MULTI" : "ALWAYS_SINGLE", cases.size(),
                round(outcomes.stream().mapToDouble(item -> item.score().total()).average().orElse(0)),
                round(averageUtility), round(oracleUtility - averageUtility),
                roundCost(outcomes.stream().mapToDouble(item -> item.execution().estimatedCostCny()).sum()),
                !outcomes.isEmpty() && outcomes.stream().allMatch(item -> item.execution().usageAvailable()),
                round(outcomes.stream().mapToLong(item -> item.execution().latencyMs()).average().orElse(0)),
                round(ratio(multi ? positives : negatives, cases.size())),
                multi ? round(ratio(positives, cases.size())) : 0,
                round(recall), round((recall + specificity) / 2),
                (int) outcomes.stream().filter(item -> !item.execution().succeeded()).count());
    }

    private AlignmentAblationReport.PairedQualityComparison pairedVsFixed(
            String runId, List<RagAbReport.CaseComparison> comparisons,
            List<SystemOneRoutingBenchmarkReport.CaseResult> cases, boolean multi) {
        return new PairedBootstrapAnalyzer().analyze(
                runId + (multi ? ":always-multi" : ":always-single"),
                comparisons.stream().map(item -> (multi ? item.forcedMulti() : item.forcedSingle())
                        .score().total()).toList(),
                cases.stream().map(SystemOneRoutingBenchmarkReport.CaseResult::systemOneQuality).toList(),
                10_000, 0.95, 0.001, nonInferiorityMargin, minimumSamples);
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
