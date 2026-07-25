package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用灰度阶段记录的行为策略概率进行自归一化逆倾向评分（SNIPS）。
 * <p>
 * 这里只消费路由元数据、奖励和遥测，不暴露用户问题或答案。
 */
@Service
public class RoutingPolicyOffPolicyEvaluator {

    private final AgentTrajectoryRepository trajectoryRepository;

    private final RoutingPolicyRegistryService registryService;

    private final RoutingPolicyDeploymentService deploymentService;

    private final int minimumSamplesPerAction;

    private final double minimumEffectiveSampleSize;

    private final double maximumImportanceWeight;

    private final double maximumRewardRegression;

    private final Clock clock;

    @Autowired
    public RoutingPolicyOffPolicyEvaluator(
            AgentTrajectoryRepository trajectoryRepository,
            RoutingPolicyRegistryService registryService,
            RoutingPolicyDeploymentService deploymentService,
            @Value("${agent.rag.routing-policy.off-policy.minimum-samples-per-action:20}")
            int minimumSamplesPerAction,
            @Value("${agent.rag.routing-policy.off-policy.minimum-effective-sample-size:20}")
            double minimumEffectiveSampleSize,
            @Value("${agent.rag.routing-policy.off-policy.maximum-importance-weight:20}")
            double maximumImportanceWeight,
            @Value("${agent.rag.routing-policy.off-policy.maximum-reward-regression:0.03}")
            double maximumRewardRegression) {
        this(
                trajectoryRepository,
                registryService,
                deploymentService,
                minimumSamplesPerAction,
                minimumEffectiveSampleSize,
                maximumImportanceWeight,
                maximumRewardRegression,
                Clock.systemUTC()
        );
    }

    RoutingPolicyOffPolicyEvaluator(
            AgentTrajectoryRepository trajectoryRepository,
            RoutingPolicyRegistryService registryService,
            RoutingPolicyDeploymentService deploymentService,
            int minimumSamplesPerAction,
            double minimumEffectiveSampleSize,
            double maximumImportanceWeight,
            double maximumRewardRegression,
            Clock clock) {
        this.trajectoryRepository = Objects.requireNonNull(
                trajectoryRepository, "trajectoryRepository");
        this.registryService = Objects.requireNonNull(
                registryService, "registryService");
        this.deploymentService = Objects.requireNonNull(
                deploymentService, "deploymentService");
        this.minimumSamplesPerAction = Math.max(1, minimumSamplesPerAction);
        this.minimumEffectiveSampleSize = positive(
                minimumEffectiveSampleSize, "minimumEffectiveSampleSize");
        this.maximumImportanceWeight = positive(
                maximumImportanceWeight, "maximumImportanceWeight");
        this.maximumRewardRegression = Math.max(0, maximumRewardRegression);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OffPolicyEvaluationReport evaluate() {
        Instant evaluatedAt = clock.instant();
        try {
            RoutingPolicyArtifact artifact = evaluationArtifact();
            if (artifact == null) {
                return emptyReport(
                        OffPolicyEvaluationState.NO_ARTIFACT,
                        evaluatedAt,
                        "尚无已验证学习策略资产"
                );
            }

            List<AgentTrajectory> trajectories = trajectoryRepository.findAll();
            int probabilityLogged = 0;
            int exploratory = 0;
            int eligible = 0;
            int singleSamples = 0;
            int multiSamples = 0;
            int matched = 0;
            double behaviorRewardSum = 0;
            double weightedRewardSum = 0;
            double weightSum = 0;
            double squaredWeightSum = 0;
            List<Double> behaviorRewards = new ArrayList<>();
            List<WeightedReward> targetRewards = new ArrayList<>();

            for (AgentTrajectory trajectory : trajectories) {
                AgentStep route = routeStep(trajectory);
                if (!eligibleTrajectory(trajectory, route)
                        || !artifact.version().equals(Objects.toString(
                        route.output().get("policyArtifactVersion"), ""))) {
                    continue;
                }
                Double probability = probability(route.output());
                if (probability == null) {
                    continue;
                }
                probabilityLogged++;
                if (!Boolean.TRUE.equals(
                        route.output().get("policyExplorationEligible"))) {
                    continue;
                }
                exploratory++;
                RoutingPolicyArtifact.DecisionRule targetRule =
                        targetRule(artifact, Objects.toString(
                                route.output().get("featureBucket"), ""));
                if (targetRule == null) {
                    continue;
                }
                String actualMode = Objects.toString(
                        route.output().get("mode"), "");
                if (!AdaptiveMultiAgentOrchestrator.SINGLE_MODE.equals(actualMode)
                        && !AdaptiveMultiAgentOrchestrator.MULTI_MODE.equals(actualMode)) {
                    continue;
                }
                eligible++;
                if (AdaptiveMultiAgentOrchestrator.SINGLE_MODE.equals(actualMode)) {
                    singleSamples++;
                } else {
                    multiSamples++;
                }
                double reward = trajectory.reward() == null
                        ? 0
                        : trajectory.reward().total();
                behaviorRewardSum += reward;
                behaviorRewards.add(reward);
                if (!targetRule.recommendedMode().equals(actualMode)) {
                    continue;
                }
                matched++;
                double weight = Math.min(
                        maximumImportanceWeight,
                        1 / probability
                );
                weightSum += weight;
                squaredWeightSum += weight * weight;
                weightedRewardSum += weight * reward;
                targetRewards.add(new WeightedReward(reward, weight));
            }

            double behaviorReward = eligible == 0
                    ? 0
                    : round(behaviorRewardSum / eligible);
            double estimatedPolicyReward = weightSum == 0
                    ? 0
                    : round(weightedRewardSum / weightSum);
            double effectiveSampleSize = squaredWeightSum == 0
                    ? 0
                    : round(weightSum * weightSum / squaredWeightSum);
            double rewardLift = round(estimatedPolicyReward - behaviorReward);
            double behaviorVariance = behaviorRewards.stream()
                    .mapToDouble(reward -> square(reward - behaviorReward))
                    .average()
                    .orElse(0);
            double targetVariance = weightSum == 0
                    ? 0
                    : targetRewards.stream()
                            .mapToDouble(value -> value.weight()
                                    * square(value.reward() - estimatedPolicyReward))
                            .sum() / weightSum;
            double rewardLiftStandardError = effectiveSampleSize == 0 || eligible == 0
                    ? 0
                    : round(Math.sqrt(
                            targetVariance / effectiveSampleSize
                                    + behaviorVariance / eligible
                    ));
            double rewardLiftLowerConfidenceBound = round(
                    rewardLift - 1.96 * rewardLiftStandardError);

            List<String> blockers = new ArrayList<>();
            if (singleSamples < minimumSamplesPerAction) {
                blockers.add("SINGLE 探索样本 %d/%d"
                        .formatted(singleSamples, minimumSamplesPerAction));
            }
            if (multiSamples < minimumSamplesPerAction) {
                blockers.add("MULTI 探索样本 %d/%d"
                        .formatted(multiSamples, minimumSamplesPerAction));
            }
            if (effectiveSampleSize < minimumEffectiveSampleSize) {
                blockers.add("有效样本量 %.2f/%.2f"
                        .formatted(effectiveSampleSize, minimumEffectiveSampleSize));
            }
            if (matched == 0) {
                blockers.add("没有与目标策略动作匹配的探索样本");
            }

            boolean evidenceReady = blockers.isEmpty();
            boolean healthy = evidenceReady
                    && rewardLiftLowerConfidenceBound >= -maximumRewardRegression;
            OffPolicyEvaluationState state;
            if (!evidenceReady) {
                state = OffPolicyEvaluationState.COLLECTING;
            } else if (healthy) {
                state = OffPolicyEvaluationState.READY;
            } else {
                state = OffPolicyEvaluationState.REGRESSION;
                blockers.add("SNIPS 奖励提升 95%% 下界 %.4f，低于门槛 %.4f"
                        .formatted(
                                rewardLiftLowerConfidenceBound,
                                -maximumRewardRegression
                        ));
            }
            String reason = healthy
                    ? "双动作支持度和有效样本量已通过，SNIPS 评测可用于 ACTIVE 晋升"
                    : String.join("；", blockers);
            return new OffPolicyEvaluationReport(
                    state,
                    artifact.version(),
                    trajectories.size(),
                    probabilityLogged,
                    exploratory,
                    eligible,
                    singleSamples,
                    multiSamples,
                    matched,
                    minimumSamplesPerAction,
                    effectiveSampleSize,
                    minimumEffectiveSampleSize,
                    behaviorReward,
                    estimatedPolicyReward,
                    rewardLift,
                    rewardLiftStandardError,
                    rewardLiftLowerConfidenceBound,
                    maximumRewardRegression,
                    healthy,
                    List.copyOf(blockers),
                    evaluatedAt,
                    reason
            );
        } catch (RuntimeException exception) {
            return emptyReport(
                    OffPolicyEvaluationState.ERROR,
                    evaluatedAt,
                    "离线策略评测不可用：" + exception.getClass().getSimpleName()
            );
        }
    }

    private RoutingPolicyArtifact evaluationArtifact() {
        String deployedVersion = deploymentService.current().policyVersion();
        if (!RoutingPolicyRegistryService.BASELINE_VERSION.equals(deployedVersion)) {
            return registryService.requireDeployable(deployedVersion);
        }
        return registryService.state().artifacts().stream()
                .filter(artifact ->
                        artifact.status() == RoutingPolicyArtifactStatus.VALIDATED)
                .filter(artifact -> !RoutingPolicyRegistryService.BASELINE_VERSION.equals(
                        artifact.version()))
                .findFirst()
                .orElse(null);
    }

    private RoutingPolicyArtifact.DecisionRule targetRule(
            RoutingPolicyArtifact artifact,
            String featureBucket) {
        RoutingPolicyArtifact.DecisionRule contextual =
                artifact.contextualRules().get(featureBucket);
        if (contextual != null && contextual.deployable()) {
            return contextual;
        }
        return artifact.globalRule().deployable() ? artifact.globalRule() : null;
    }

    private Double probability(Map<String, Object> output) {
        Object value = output.get("policyBehaviorActionProbability");
        if (!(value instanceof Number number)) {
            return null;
        }
        double probability = number.doubleValue();
        if (!Double.isFinite(probability) || probability <= 0 || probability > 1) {
            return null;
        }
        return probability;
    }

    private boolean eligibleTrajectory(AgentTrajectory trajectory, AgentStep route) {
        return trajectory != null
                && route != null
                && ("COMPLETED".equals(trajectory.status())
                || "FAILED".equals(trajectory.status()));
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

    private OffPolicyEvaluationReport emptyReport(
            OffPolicyEvaluationState state,
            Instant evaluatedAt,
            String reason) {
        return new OffPolicyEvaluationReport(
                state,
                "",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                minimumSamplesPerAction,
                0,
                minimumEffectiveSampleSize,
                0,
                0,
                0,
                0,
                0,
                maximumRewardRegression,
                false,
                List.of(reason),
                evaluatedAt,
                reason
        );
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

    private double square(double value) {
        return value * value;
    }

    public enum OffPolicyEvaluationState {
        NO_ARTIFACT,
        COLLECTING,
        READY,
        REGRESSION,
        ERROR
    }

    public record OffPolicyEvaluationReport(
            OffPolicyEvaluationState state,
            String policyArtifactVersion,
            int observedTrajectoryCount,
            int probabilityLoggedTrajectoryCount,
            int exploratoryTrajectoryCount,
            int eligibleTrajectoryCount,
            int singleActionSamples,
            int multiActionSamples,
            int targetMatchedSamples,
            int minimumSamplesPerAction,
            double effectiveSampleSize,
            double minimumEffectiveSampleSize,
            double behaviorAverageReward,
            double estimatedPolicyReward,
            double estimatedRewardLift,
            double rewardLiftStandardError,
            double rewardLiftLowerConfidenceBound,
            double maximumRewardRegression,
            boolean healthyForPromotion,
            List<String> blockers,
            Instant evaluatedAt,
            String reason
    ) {
    }

    private record WeightedReward(double reward, double weight) {
    }
}
