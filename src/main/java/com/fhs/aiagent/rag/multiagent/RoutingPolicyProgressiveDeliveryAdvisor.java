package com.fhs.aiagent.rag.multiagent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 只读渐进式发布顾问。它不会直接改变线上流量，只根据当前部署及门禁证据给出下一阶段建议。
 */
@Service
public class RoutingPolicyProgressiveDeliveryAdvisor {

    private static final double EPSILON = 0.000_000_1;

    private final RoutingPolicyDeploymentService deploymentService;

    private final TrajectoryAwareRoutingPolicy routingPolicy;

    private final RoutingPolicyRegistryService registryService;

    private final RoutingPolicyQualityGuard qualityGuard;

    private final RoutingPolicyOffPolicyEvaluator offPolicyEvaluator;

    private final boolean enabled;

    private final boolean dryRun;

    private final List<Double> canaryStages;

    private final Duration cooldown;

    private final Clock clock;

    @Autowired
    public RoutingPolicyProgressiveDeliveryAdvisor(
            RoutingPolicyDeploymentService deploymentService,
            TrajectoryAwareRoutingPolicy routingPolicy,
            RoutingPolicyRegistryService registryService,
            RoutingPolicyQualityGuard qualityGuard,
            RoutingPolicyOffPolicyEvaluator offPolicyEvaluator,
            @Value("${agent.rag.routing-policy.progressive-delivery.enabled:true}")
            boolean enabled,
            @Value("${agent.rag.routing-policy.progressive-delivery.dry-run:true}")
            boolean dryRun,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".canary-stages:0.05,0.10,0.25,0.50}")
            String canaryStages,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".cooldown-minutes:30}")
            long cooldownMinutes) {
        this(
                deploymentService,
                routingPolicy,
                registryService,
                qualityGuard,
                offPolicyEvaluator,
                enabled,
                dryRun,
                parseStages(canaryStages),
                Duration.ofMinutes(Math.max(0, cooldownMinutes)),
                Clock.systemUTC()
        );
    }

    RoutingPolicyProgressiveDeliveryAdvisor(
            RoutingPolicyDeploymentService deploymentService,
            TrajectoryAwareRoutingPolicy routingPolicy,
            RoutingPolicyRegistryService registryService,
            RoutingPolicyQualityGuard qualityGuard,
            RoutingPolicyOffPolicyEvaluator offPolicyEvaluator,
            boolean enabled,
            boolean dryRun,
            List<Double> canaryStages,
            Duration cooldown,
            Clock clock) {
        this.deploymentService = Objects.requireNonNull(
                deploymentService, "deploymentService");
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy");
        this.registryService = Objects.requireNonNull(
                registryService, "registryService");
        this.qualityGuard = Objects.requireNonNull(qualityGuard, "qualityGuard");
        this.offPolicyEvaluator = Objects.requireNonNull(
                offPolicyEvaluator, "offPolicyEvaluator");
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.canaryStages = normalizeStages(canaryStages);
        this.cooldown = requireNonNegative(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProgressiveDeliveryReport recommend() {
        Instant evaluatedAt = clock.instant();
        try {
            RoutingPolicyDeployment deployment = deploymentService.current();
            if (!enabled) {
                return report(
                        ProgressiveDeliveryState.DISABLED,
                        deployment,
                        deployment.mode(),
                        trafficRate(deployment),
                        deployment.policyVersion(),
                        false,
                        true,
                        deployment.createdAt(),
                        null,
                        null,
                        List.of("渐进式发布顾问已关闭"),
                        evaluatedAt,
                        "渐进式发布顾问已关闭"
                );
            }
            return switch (deployment.mode()) {
                case OFF -> offReport(deployment, evaluatedAt);
                case SHADOW -> shadowReport(deployment, evaluatedAt);
                case CANARY -> canaryReport(deployment, evaluatedAt);
                case ACTIVE -> activeReport(deployment, evaluatedAt);
            };
        } catch (RuntimeException exception) {
            RoutingPolicyDeployment unavailable = new RoutingPolicyDeployment(
                    "routing-unavailable",
                    RoutingPolicyRegistryService.BASELINE_VERSION,
                    RoutingPolicyMode.OFF,
                    1,
                    evaluatedAt,
                    "progressive delivery unavailable"
            );
            return report(
                    ProgressiveDeliveryState.ERROR,
                    unavailable,
                    RoutingPolicyMode.OFF,
                    0,
                    unavailable.policyVersion(),
                    false,
                    false,
                    evaluatedAt,
                    null,
                    null,
                    List.of(exception.getClass().getSimpleName()),
                    evaluatedAt,
                    "渐进式发布建议不可用：" + exception.getClass().getSimpleName()
            );
        }
    }

    private ProgressiveDeliveryReport offReport(
            RoutingPolicyDeployment deployment,
            Instant evaluatedAt) {
        return report(
                ProgressiveDeliveryState.HOLD,
                deployment,
                RoutingPolicyMode.SHADOW,
                0,
                deployment.policyVersion(),
                false,
                cooldownPassed(deployment, evaluatedAt),
                cooldownUntil(deployment),
                null,
                null,
                List.of("当前为 OFF，恢复 SHADOW 需要人工确认"),
                evaluatedAt,
                "保持 OFF，不自动重新启用学习策略"
        );
    }

    private ProgressiveDeliveryReport shadowReport(
            RoutingPolicyDeployment deployment,
            Instant evaluatedAt) {
        RoutingPolicyArtifact artifact;
        try {
            artifact = registryService.latestValidatedCandidate();
        } catch (NoSuchElementException exception) {
            return report(
                    ProgressiveDeliveryState.WAITING_FOR_ARTIFACT,
                    deployment,
                    RoutingPolicyMode.CANARY,
                    canaryStages.getFirst(),
                    RoutingPolicyRegistryService.BASELINE_VERSION,
                    false,
                    cooldownPassed(deployment, evaluatedAt),
                    cooldownUntil(deployment),
                    null,
                    null,
                    List.of("尚无通过时间留出验证的学习策略资产"),
                    evaluatedAt,
                    "继续在 SHADOW 收集平衡训练和验证轨迹"
            );
        }

        TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status =
                routingPolicy.status();
        List<String> blockers = new ArrayList<>();
        boolean cooldownPassed = cooldownPassed(deployment, evaluatedAt);
        if (!status.ready()) {
            blockers.add("单/多 Agent 训练证据尚未达到平衡门槛");
        }
        if (!artifact.temporalHoldout().validationPassed()) {
            blockers.add("候选策略未通过独立时间留出验证");
        }
        if (!cooldownPassed) {
            blockers.add("发布冷却期尚未结束");
        }
        ProgressiveDeliveryState state = blockers.isEmpty()
                ? ProgressiveDeliveryState.READY
                : (!cooldownPassed
                        ? ProgressiveDeliveryState.COOLDOWN
                        : ProgressiveDeliveryState.HOLD);
        return report(
                state,
                deployment,
                RoutingPolicyMode.CANARY,
                canaryStages.getFirst(),
                artifact.version(),
                artifact.temporalHoldout().validationPassed(),
                cooldownPassed,
                cooldownUntil(deployment),
                null,
                null,
                blockers,
                evaluatedAt,
                blockers.isEmpty()
                        ? "建议进入 5% 灰度，继续保持人工执行"
                        : String.join("；", blockers)
        );
    }

    private ProgressiveDeliveryReport canaryReport(
            RoutingPolicyDeployment deployment,
            Instant evaluatedAt) {
        RoutingPolicyArtifact artifact;
        try {
            artifact = registryService.requireDeployable(
                    deployment.policyVersion());
        } catch (RuntimeException exception) {
            return report(
                    ProgressiveDeliveryState.HOLD,
                    deployment,
                    RoutingPolicyMode.SHADOW,
                    0,
                    deployment.policyVersion(),
                    false,
                    cooldownPassed(deployment, evaluatedAt),
                    cooldownUntil(deployment),
                    null,
                    null,
                    List.of("当前灰度策略资产不再满足发布要求"),
                    evaluatedAt,
                    "建议人工降级 SHADOW 并重新生成候选资产"
            );
        }

        double nextCanaryRate = nextCanaryRate(deployment.canaryRate());
        boolean promoteActive = nextCanaryRate >= 1 - EPSILON;
        RoutingPolicyMode targetMode = promoteActive
                ? RoutingPolicyMode.ACTIVE
                : RoutingPolicyMode.CANARY;
        double targetRate = promoteActive ? 1 : nextCanaryRate;
        boolean cooldownPassed = cooldownPassed(deployment, evaluatedAt);
        RoutingPolicyQualityGuard.QualityGuardReport guard =
                qualityGuard.status();
        RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport offPolicy =
                offPolicyEvaluator.evaluate();
        List<String> blockers = new ArrayList<>();
        if (!cooldownPassed) {
            blockers.add("发布冷却期尚未结束");
        }
        if (!guard.healthyForPromotion()) {
            blockers.add(guard.state() == RoutingPolicyQualityGuard.GuardState.COLLECTING
                    ? "当前阶段仍在收集灰度/对照样本"
                    : "当前阶段在线质量守卫未通过");
        }
        if (promoteActive && !offPolicy.healthyForPromotion()) {
            blockers.add(offPolicy.state()
                    == RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.COLLECTING
                    ? "反事实评测仍在积累双动作探索样本"
                    : "反事实评测未通过 ACTIVE 晋升门禁");
        }

        ProgressiveDeliveryState state;
        if (blockers.isEmpty()) {
            state = ProgressiveDeliveryState.READY;
        } else if (!cooldownPassed) {
            state = ProgressiveDeliveryState.COOLDOWN;
        } else if (guard.state() == RoutingPolicyQualityGuard.GuardState.COLLECTING
                || (promoteActive
                && offPolicy.state()
                == RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.COLLECTING)) {
            state = ProgressiveDeliveryState.COLLECTING;
        } else {
            state = ProgressiveDeliveryState.HOLD;
        }
        return report(
                state,
                deployment,
                targetMode,
                targetRate,
                artifact.version(),
                artifact.temporalHoldout().validationPassed(),
                cooldownPassed,
                cooldownUntil(deployment),
                guard,
                offPolicy,
                blockers,
                evaluatedAt,
                blockers.isEmpty()
                        ? (promoteActive
                                ? "全部门禁通过，建议人工晋升 ACTIVE"
                                : "当前阶段健康，建议人工扩大到 %.0f%% 灰度"
                                        .formatted(targetRate * 100))
                        : String.join("；", blockers)
        );
    }

    private ProgressiveDeliveryReport activeReport(
            RoutingPolicyDeployment deployment,
            Instant evaluatedAt) {
        return report(
                ProgressiveDeliveryState.COMPLETE,
                deployment,
                RoutingPolicyMode.ACTIVE,
                1,
                deployment.policyVersion(),
                true,
                true,
                cooldownUntil(deployment),
                null,
                null,
                List.of(),
                evaluatedAt,
                "策略已完成渐进式发布，交由 ACTIVE 漂移监控持续守护"
        );
    }

    private ProgressiveDeliveryReport report(
            ProgressiveDeliveryState state,
            RoutingPolicyDeployment deployment,
            RoutingPolicyMode recommendedMode,
            double recommendedTrafficRate,
            String policyArtifactVersion,
            boolean temporalHoldoutPassed,
            boolean cooldownPassed,
            Instant cooldownUntil,
            RoutingPolicyQualityGuard.QualityGuardReport guard,
            RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport offPolicy,
            List<String> blockers,
            Instant evaluatedAt,
            String reason) {
        double currentRate = trafficRate(deployment);
        return new ProgressiveDeliveryReport(
                enabled,
                dryRun,
                state,
                deployment.version(),
                deployment.mode(),
                currentRate,
                stageIndex(deployment.mode(), currentRate),
                canaryStages,
                recommendedMode,
                recommendedTrafficRate,
                stageIndex(recommendedMode, recommendedTrafficRate),
                policyArtifactVersion,
                temporalHoldoutPassed,
                cooldownPassed,
                cooldownUntil,
                guard == null
                        ? RoutingPolicyQualityGuard.GuardState.INACTIVE
                        : guard.state(),
                guard == null ? 0 : guard.canary().sampleCount(),
                guard == null ? 0 : guard.control().sampleCount(),
                guard == null ? deploymentService.minimumCanarySamples()
                        : guard.minimumCanarySamples(),
                guard == null ? 0 : guard.minimumControlSamples(),
                offPolicy == null
                        ? RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.NO_ARTIFACT
                        : offPolicy.state(),
                offPolicy != null && offPolicy.healthyForPromotion(),
                blockers.isEmpty()
                        && state == ProgressiveDeliveryState.READY,
                List.copyOf(blockers),
                evaluatedAt,
                reason
        );
    }

    private double nextCanaryRate(double current) {
        return canaryStages.stream()
                .filter(stage -> stage > current + EPSILON)
                .findFirst()
                .orElse(1.0);
    }

    private int stageIndex(RoutingPolicyMode mode, double rate) {
        if (mode == RoutingPolicyMode.OFF || mode == RoutingPolicyMode.SHADOW) {
            return -1;
        }
        if (mode == RoutingPolicyMode.ACTIVE) {
            return canaryStages.size();
        }
        for (int index = 0; index < canaryStages.size(); index++) {
            if (Math.abs(canaryStages.get(index) - rate) < EPSILON) {
                return index;
            }
            if (canaryStages.get(index) > rate) {
                return Math.max(0, index - 1);
            }
        }
        return canaryStages.size() - 1;
    }

    private double trafficRate(RoutingPolicyDeployment deployment) {
        return switch (deployment.mode()) {
            case OFF, SHADOW -> 0;
            case CANARY -> deployment.canaryRate();
            case ACTIVE -> 1;
        };
    }

    private boolean cooldownPassed(
            RoutingPolicyDeployment deployment,
            Instant now) {
        return !now.isBefore(cooldownUntil(deployment));
    }

    private Instant cooldownUntil(RoutingPolicyDeployment deployment) {
        return deployment.createdAt().plus(cooldown);
    }

    private static List<Double> parseStages(String value) {
        String normalized = Objects.toString(value, "");
        if (normalized.isBlank()) {
            return List.of(0.05, 0.10, 0.25, 0.50);
        }
        try {
            return Arrays.stream(normalized.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isBlank())
                    .map(Double::parseDouble)
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "progressive canary stages are invalid", exception);
        }
    }

    private static List<Double> normalizeStages(List<Double> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one progressive canary stage is required");
        }
        List<Double> normalized = stages.stream()
                .filter(Objects::nonNull)
                .peek(stage -> {
                    if (!Double.isFinite(stage) || stage <= 0 || stage >= 1) {
                        throw new IllegalArgumentException(
                                "canary stages must be in (0, 1)");
                    }
                })
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one progressive canary stage is required");
        }
        return List.copyOf(normalized);
    }

    private Duration requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    public enum ProgressiveDeliveryState {
        DISABLED,
        WAITING_FOR_ARTIFACT,
        COOLDOWN,
        COLLECTING,
        READY,
        HOLD,
        COMPLETE,
        ERROR
    }

    public record ProgressiveDeliveryReport(
            boolean enabled,
            boolean dryRun,
            ProgressiveDeliveryState state,
            String deploymentVersion,
            RoutingPolicyMode currentMode,
            double currentTrafficRate,
            int currentStageIndex,
            List<Double> canaryStages,
            RoutingPolicyMode recommendedMode,
            double recommendedTrafficRate,
            int recommendedStageIndex,
            String policyArtifactVersion,
            boolean temporalHoldoutPassed,
            boolean cooldownPassed,
            Instant cooldownUntil,
            RoutingPolicyQualityGuard.GuardState qualityGuardState,
            int canarySamples,
            int controlSamples,
            int minimumCanarySamples,
            int minimumControlSamples,
            RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState
                    offPolicyState,
            boolean offPolicyHealthy,
            boolean readyToAdvance,
            List<String> blockers,
            Instant evaluatedAt,
            String reason
    ) {

        public ProgressiveDeliveryReport {
            canaryStages = canaryStages == null
                    ? List.of()
                    : List.copyOf(canaryStages);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }
}
