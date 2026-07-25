package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用严格晚于训练窗口的轨迹验证冻结规则，阻止训练集指标直接充当发布证据。
 */
@Component
public class RoutingPolicyTemporalHoldoutEvaluator {

    private static final double Z_95 = 1.959963984540054;

    private final double validationRatio;

    private final int minimumSamplesPerMode;

    private final double minimumUtilityLiftLowerBound;

    private final double costWeight;

    private final double latencyWeight;

    private final double costBudgetCny;

    private final double latencyBudgetMs;

    @Autowired
    public RoutingPolicyTemporalHoldoutEvaluator(
            @Value("${agent.rag.routing-policy.temporal-holdout.validation-ratio:0.25}")
            double validationRatio,
            @Value("${agent.rag.routing-policy.temporal-holdout.minimum-samples-per-mode:4}")
            int minimumSamplesPerMode,
            @Value("${agent.rag.routing-policy.temporal-holdout"
                    + ".minimum-utility-lift-lower-bound:0.0}")
            double minimumUtilityLiftLowerBound,
            @Value("${agent.rag.routing-policy.cost-weight:0.05}") double costWeight,
            @Value("${agent.rag.routing-policy.latency-weight:0.05}") double latencyWeight,
            @Value("${agent.rag.routing-policy.cost-budget-cny:0.02}") double costBudgetCny,
            @Value("${agent.rag.routing-policy.latency-budget-ms:60000}")
            double latencyBudgetMs) {
        if (!Double.isFinite(validationRatio)
                || validationRatio <= 0
                || validationRatio > 0.5) {
            throw new IllegalArgumentException(
                    "validationRatio must be in (0, 0.5]");
        }
        this.validationRatio = validationRatio;
        this.minimumSamplesPerMode = Math.max(1, minimumSamplesPerMode);
        this.minimumUtilityLiftLowerBound =
                Math.max(0, minimumUtilityLiftLowerBound);
        this.costWeight = Math.max(0, costWeight);
        this.latencyWeight = Math.max(0, latencyWeight);
        this.costBudgetCny = positive(costBudgetCny, "costBudgetCny");
        this.latencyBudgetMs = positive(latencyBudgetMs, "latencyBudgetMs");
    }

    public TemporalDatasetSplit split(List<AgentTrajectory> trajectories) {
        List<AgentTrajectory> ordered = trajectories == null
                ? List.of()
                : trajectories.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator
                                .comparing(this::eventTime)
                                .thenComparing(AgentTrajectory::trajectoryId))
                        .toList();
        if (ordered.size() < 2) {
            return new TemporalDatasetSplit(
                    ordered,
                    List.of(),
                    Instant.EPOCH,
                    validationRatio
            );
        }
        int requested = Math.max(
                minimumSamplesPerMode * 2,
                (int) Math.ceil(ordered.size() * validationRatio)
        );
        int validationCount = Math.min(ordered.size() - 1, requested);
        int splitIndex = ordered.size() - validationCount;
        List<AgentTrajectory> training = List.copyOf(
                ordered.subList(0, splitIndex));
        List<AgentTrajectory> validation = List.copyOf(
                ordered.subList(splitIndex, ordered.size()));
        return new TemporalDatasetSplit(
                training,
                validation,
                eventTime(validation.getFirst()),
                validationRatio
        );
    }

    public TemporalValidationOutcome evaluate(
            TemporalDatasetSplit split,
            String validationFingerprint,
            RoutingPolicyArtifact.DecisionRule globalRule,
            Map<String, RoutingPolicyArtifact.DecisionRule> contextualRules) {
        Objects.requireNonNull(split, "split");
        List<Observation> validation = split.validation().stream()
                .map(this::observation)
                .filter(Objects::nonNull)
                .toList();
        Map<String, RoutingPolicyArtifact.RuleValidation> validations =
                new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        RoutingPolicyArtifact.DecisionRule validatedGlobal =
                validateAndRetain(
                        "GLOBAL",
                        globalRule,
                        validation,
                        validations,
                        failures
                );
        Map<String, RoutingPolicyArtifact.DecisionRule> validatedContextual =
                new LinkedHashMap<>();
        contextualRules.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<Observation> bucket = validation.stream()
                            .filter(observation -> entry.getKey().equals(
                                    observation.featureBucket()))
                            .toList();
                    RoutingPolicyArtifact.DecisionRule retained =
                            validateAndRetain(
                                    "CONTEXTUAL:" + entry.getKey(),
                                    entry.getValue(),
                                    bucket,
                                    validations,
                                    failures
                            );
                    if (retained.deployable()) {
                        validatedContextual.put(entry.getKey(), retained);
                    }
                });

        boolean passed = validatedGlobal.deployable()
                || !validatedContextual.isEmpty();
        if (!passed && failures.isEmpty()) {
            failures.add("no trained rule was available for temporal validation");
        }
        RoutingPolicyArtifact.TemporalHoldoutEvaluation report =
                new RoutingPolicyArtifact.TemporalHoldoutEvaluation(
                        true,
                        split.validationRatio(),
                        split.cutoff(),
                        validationFingerprint,
                        validation.size(),
                        minimumSamplesPerMode,
                        0.95,
                        minimumUtilityLiftLowerBound,
                        Map.copyOf(validations),
                        passed,
                        List.copyOf(failures)
                );
        return new TemporalValidationOutcome(
                report,
                validatedGlobal,
                Map.copyOf(validatedContextual)
        );
    }

    private RoutingPolicyArtifact.DecisionRule validateAndRetain(
            String scope,
            RoutingPolicyArtifact.DecisionRule rule,
            List<Observation> observations,
            Map<String, RoutingPolicyArtifact.RuleValidation> validations,
            List<String> failures) {
        if (rule == null || !rule.deployable()) {
            return RoutingPolicyArtifact.DecisionRule.empty();
        }
        List<Double> singleUtilities = utilities(
                observations,
                AdaptiveMultiAgentOrchestrator.SINGLE_MODE);
        List<Double> multiUtilities = utilities(
                observations,
                AdaptiveMultiAgentOrchestrator.MULTI_MODE);
        boolean enoughSamples =
                singleUtilities.size() >= minimumSamplesPerMode
                        && multiUtilities.size() >= minimumSamplesPerMode;
        double singleMean = average(singleUtilities);
        double multiMean = average(multiUtilities);
        boolean recommendsMulti = AdaptiveMultiAgentOrchestrator.MULTI_MODE.equals(
                rule.recommendedMode());
        double lift = recommendsMulti
                ? multiMean - singleMean
                : singleMean - multiMean;
        double standardError = Math.sqrt(
                varianceOfMean(singleUtilities)
                        + varianceOfMean(multiUtilities));
        double lowerBound = lift - Z_95 * standardError;
        boolean passed = enoughSamples
                && lowerBound >= minimumUtilityLiftLowerBound;
        String reason;
        if (!enoughSamples) {
            reason = "%s holdout evidence below %d per mode (single=%d, multi=%d)"
                    .formatted(
                            scope,
                            minimumSamplesPerMode,
                            singleUtilities.size(),
                            multiUtilities.size()
                    );
        } else if (!passed) {
            reason = "%s 95%% utility-lift lower bound %.4f below %.4f"
                    .formatted(
                            scope,
                            lowerBound,
                            minimumUtilityLiftLowerBound
                    );
        } else {
            reason = "%s passed temporal holdout: lift %.4f, 95%% lower bound %.4f"
                    .formatted(scope, lift, lowerBound);
        }
        RoutingPolicyArtifact.RuleValidation validation =
                new RoutingPolicyArtifact.RuleValidation(
                        scope,
                        rule.recommendedMode(),
                        singleUtilities.size(),
                        multiUtilities.size(),
                        round(lift),
                        round(standardError),
                        round(lowerBound),
                        passed,
                        reason
                );
        validations.put(scope, validation);
        if (!passed) {
            failures.add(reason);
            return RoutingPolicyArtifact.DecisionRule.empty();
        }
        return rule;
    }

    private List<Double> utilities(List<Observation> observations, String mode) {
        return observations.stream()
                .filter(observation -> mode.equals(observation.mode()))
                .map(Observation::utility)
                .toList();
    }

    private Observation observation(AgentTrajectory trajectory) {
        AgentStep route = routeStep(trajectory);
        if (route == null) {
            return null;
        }
        String mode = Objects.toString(route.output().get("mode"), "");
        if (!AdaptiveMultiAgentOrchestrator.SINGLE_MODE.equals(mode)
                && !AdaptiveMultiAgentOrchestrator.MULTI_MODE.equals(mode)) {
            return null;
        }
        double reward = trajectory.reward() == null
                ? 0
                : trajectory.reward().total();
        AgentRunMetrics telemetry = trajectory.telemetry();
        double cost = telemetry == null ? 0 : telemetry.estimatedCostCny();
        long latency = trajectory.steps() == null
                ? 0
                : trajectory.steps().stream()
                        .mapToLong(AgentStep::durationMs)
                        .sum();
        double utility = reward
                - costWeight * Math.min(1, cost / costBudgetCny)
                - latencyWeight * Math.min(1, latency / latencyBudgetMs);
        return new Observation(
                mode,
                Objects.toString(route.output().get("featureBucket"), ""),
                round(utility)
        );
    }

    private AgentStep routeStep(AgentTrajectory trajectory) {
        if (trajectory == null || trajectory.steps() == null) {
            return null;
        }
        return trajectory.steps().stream()
                .filter(step -> step.type() == AgentStepType.ROUTE)
                .findFirst()
                .orElse(null);
    }

    private Instant eventTime(AgentTrajectory trajectory) {
        if (trajectory.completedAt() != null) {
            return trajectory.completedAt();
        }
        return trajectory.startedAt() == null
                ? Instant.EPOCH
                : trajectory.startedAt();
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double varianceOfMean(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = average(values);
        double sum = values.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .sum();
        double sampleVariance = sum / (values.size() - 1);
        return sampleVariance / values.size();
    }

    private double positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private double round(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    public record TemporalDatasetSplit(
            List<AgentTrajectory> training,
            List<AgentTrajectory> validation,
            Instant cutoff,
            double validationRatio
    ) {

        public TemporalDatasetSplit {
            training = training == null ? List.of() : List.copyOf(training);
            validation = validation == null ? List.of() : List.copyOf(validation);
            cutoff = cutoff == null ? Instant.EPOCH : cutoff;
        }
    }

    public record TemporalValidationOutcome(
            RoutingPolicyArtifact.TemporalHoldoutEvaluation report,
            RoutingPolicyArtifact.DecisionRule globalRule,
            Map<String, RoutingPolicyArtifact.DecisionRule> contextualRules
    ) {

        public TemporalValidationOutcome {
            report = Objects.requireNonNull(report, "report");
            globalRule = globalRule == null
                    ? RoutingPolicyArtifact.DecisionRule.empty()
                    : globalRule;
            contextualRules = contextualRules == null
                    ? Map.of()
                    : Map.copyOf(contextualRules);
        }
    }

    private record Observation(
            String mode,
            String featureBucket,
            double utility
    ) {
    }
}
