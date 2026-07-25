package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 对同一 CANARY 发布版本的灰度组和对照组执行在线质量守卫。
 * 监控异常不会进入回答主链；确认质量回退后将发布模式自动降级为 SHADOW。
 */
@Service
public class RoutingPolicyQualityGuard {

    private static final String AUTO_ROLLBACK_PREFIX = "automatic quality rollback";

    private static final Logger log =
            LoggerFactory.getLogger(RoutingPolicyQualityGuard.class);

    private final AgentTrajectoryRepository trajectoryRepository;

    private final RoutingPolicyDeploymentService deploymentService;

    private final boolean enabled;

    private final int minimumControlSamples;

    private final int maximumTrajectories;

    private final double maximumRewardRegression;

    private final double maximumGroundingRegression;

    private final double maximumCompletionRegression;

    private final double maximumLatencyMultiplier;

    private final double maximumCostMultiplier;

    private final Clock clock;

    private volatile QualityGuardReport latestReport;

    @Autowired
    public RoutingPolicyQualityGuard(
            AgentTrajectoryRepository trajectoryRepository,
            RoutingPolicyDeploymentService deploymentService,
            @Value("${agent.rag.routing-policy.quality-guard.enabled:true}")
            boolean enabled,
            @Value("${agent.rag.routing-policy.quality-guard.minimum-control-samples:20}")
            int minimumControlSamples,
            @Value("${agent.rag.routing-policy.quality-guard.maximum-trajectories:1000}")
            int maximumTrajectories,
            @Value("${agent.rag.routing-policy.quality-guard.maximum-reward-regression:0.05}")
            double maximumRewardRegression,
            @Value("${agent.rag.routing-policy.quality-guard.maximum-grounding-regression:0.05}")
            double maximumGroundingRegression,
            @Value("${agent.rag.routing-policy.quality-guard.maximum-completion-regression:0.05}")
            double maximumCompletionRegression,
            @Value("${agent.rag.routing-policy.quality-guard.maximum-latency-multiplier:1.5}")
            double maximumLatencyMultiplier,
            @Value("${agent.rag.routing-policy.quality-guard.maximum-cost-multiplier:1.5}")
            double maximumCostMultiplier) {
        this(
                trajectoryRepository,
                deploymentService,
                enabled,
                minimumControlSamples,
                maximumTrajectories,
                maximumRewardRegression,
                maximumGroundingRegression,
                maximumCompletionRegression,
                maximumLatencyMultiplier,
                maximumCostMultiplier,
                Clock.systemUTC()
        );
    }

    RoutingPolicyQualityGuard(
            AgentTrajectoryRepository trajectoryRepository,
            RoutingPolicyDeploymentService deploymentService,
            boolean enabled,
            int minimumControlSamples,
            int maximumTrajectories,
            double maximumRewardRegression,
            double maximumGroundingRegression,
            double maximumCompletionRegression,
            double maximumLatencyMultiplier,
            double maximumCostMultiplier,
            Clock clock) {
        this.trajectoryRepository = Objects.requireNonNull(
                trajectoryRepository, "trajectoryRepository");
        this.deploymentService = Objects.requireNonNull(
                deploymentService, "deploymentService");
        this.enabled = enabled;
        this.minimumControlSamples = Math.max(1, minimumControlSamples);
        this.maximumTrajectories = Math.max(1, maximumTrajectories);
        this.maximumRewardRegression = nonNegative(
                maximumRewardRegression, "maximumRewardRegression");
        this.maximumGroundingRegression = nonNegative(
                maximumGroundingRegression, "maximumGroundingRegression");
        this.maximumCompletionRegression = nonNegative(
                maximumCompletionRegression, "maximumCompletionRegression");
        this.maximumLatencyMultiplier = atLeastOne(
                maximumLatencyMultiplier, "maximumLatencyMultiplier");
        this.maximumCostMultiplier = atLeastOne(
                maximumCostMultiplier, "maximumCostMultiplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(
            initialDelayString =
                    "${agent.rag.routing-policy.quality-guard.evaluation-interval-ms:30000}",
            fixedDelayString =
                    "${agent.rag.routing-policy.quality-guard.evaluation-interval-ms:30000}")
    public void scheduledEvaluation() {
        evaluateAndMaybeRollback();
    }

    public synchronized QualityGuardReport evaluateAndMaybeRollback() {
        Instant evaluatedAt = clock.instant();
        try {
            RoutingPolicyDeployment deployment = deploymentService.current();
            if (!enabled) {
                return update(inactive(
                        GuardState.DISABLED,
                        deployment,
                        evaluatedAt,
                        "灰度质量守卫已关闭"
                ));
            }
            if (deployment.mode() != RoutingPolicyMode.CANARY) {
                GuardState state = deployment.reason().startsWith(AUTO_ROLLBACK_PREFIX)
                        ? GuardState.ROLLED_BACK
                        : GuardState.INACTIVE;
                return update(inactive(
                        state,
                        deployment,
                        evaluatedAt,
                        state == GuardState.ROLLED_BACK
                                ? deployment.reason()
                                : "当前发布模式不是 CANARY"
                ));
            }

            List<CohortObservation> observations = observations(deployment.version());
            CohortStats canary = stats(observations, true);
            CohortStats control = stats(observations, false);
            int minimumCanarySamples = deploymentService.minimumCanarySamples();
            if (canary.sampleCount() < minimumCanarySamples
                    || control.sampleCount() < minimumControlSamples) {
                return update(report(
                        GuardState.COLLECTING,
                        deployment,
                        canary,
                        control,
                        List.of(),
                        evaluatedAt,
                        "继续收集灰度/对照样本（至少 %d/%d）"
                                .formatted(minimumCanarySamples, minimumControlSamples)
                ));
            }

            List<String> violations = violations(canary, control);
            if (violations.isEmpty()) {
                return update(report(
                        GuardState.HEALTHY,
                        deployment,
                        canary,
                        control,
                        List.of(),
                        evaluatedAt,
                        "灰度质量门禁通过，可评估晋升 ACTIVE"
                ));
            }

            String reason = AUTO_ROLLBACK_PREFIX + ": " + String.join("; ", violations);
            deploymentService.deploy(
                    RoutingPolicyMode.SHADOW,
                    null,
                    reason,
                    new RoutingPolicyDeploymentService.PromotionEvidence(false, 0, false)
            );
            log.warn(
                    "路由灰度质量回退，已自动降级 SHADOW，deployment={}, violations={}",
                    deployment.version(),
                    violations
            );
            return update(report(
                    GuardState.ROLLED_BACK,
                    deployment,
                    canary,
                    control,
                    violations,
                    evaluatedAt,
                    "检测到质量回退，已自动降级至 SHADOW"
            ));
        } catch (RuntimeException exception) {
            log.error("路由灰度质量守卫评估失败，保持当前发布状态", exception);
            RoutingPolicyDeployment deployment = safeCurrentDeployment(evaluatedAt);
            return update(report(
                    GuardState.ERROR,
                    deployment,
                    CohortStats.empty(),
                    CohortStats.empty(),
                    List.of(exception.getClass().getSimpleName()),
                    evaluatedAt,
                    "质量守卫评估失败，未改变当前发布状态"
            ));
        }
    }

    public QualityGuardReport status() {
        QualityGuardReport current = latestReport;
        RoutingPolicyDeployment deployment;
        try {
            deployment = deploymentService.current();
        } catch (RuntimeException exception) {
            return current == null ? evaluateAndMaybeRollback() : current;
        }
        boolean retainedRollback = current != null
                && current.state() == GuardState.ROLLED_BACK
                && deployment.mode() == RoutingPolicyMode.SHADOW
                && deployment.reason().startsWith(AUTO_ROLLBACK_PREFIX);
        if (current == null
                || (!retainedRollback
                && !current.deploymentVersion().equals(deployment.version()))) {
            return evaluateAndMaybeRollback();
        }
        return current;
    }

    private List<CohortObservation> observations(String deploymentVersion) {
        return trajectoryRepository.findAll().stream()
                .limit(maximumTrajectories)
                .map(trajectory -> observation(trajectory, deploymentVersion))
                .filter(Objects::nonNull)
                .toList();
    }

    private CohortObservation observation(AgentTrajectory trajectory,
                                          String deploymentVersion) {
        AgentStep route = routeStep(trajectory);
        if (route == null
                || !"CANARY".equals(Objects.toString(
                route.output().get("policyRolloutMode"), ""))
                || !deploymentVersion.equals(Objects.toString(
                route.output().get("policyDeploymentVersion"), ""))) {
            return null;
        }
        boolean selected = Boolean.TRUE.equals(
                route.output().get("policyCanarySelected"));
        boolean completed = "COMPLETED".equals(trajectory.status());
        double reward = trajectory.reward() == null ? 0 : trajectory.reward().total();
        double grounding = trajectory.reward() == null
                ? 0
                : trajectory.reward().groundingQuality();
        long latency = trajectory.steps() == null
                ? 0
                : trajectory.steps().stream().mapToLong(AgentStep::durationMs).sum();
        boolean usageMeasured = trajectory.telemetry() != null;
        double cost = usageMeasured ? trajectory.telemetry().estimatedCostCny() : 0;
        return new CohortObservation(
                selected,
                completed,
                reward,
                grounding,
                latency,
                cost,
                usageMeasured
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

    private CohortStats stats(List<CohortObservation> observations,
                              boolean canarySelected) {
        List<CohortObservation> cohort = observations.stream()
                .filter(observation -> observation.canarySelected() == canarySelected)
                .toList();
        if (cohort.isEmpty()) {
            return CohortStats.empty();
        }
        List<CohortObservation> measured = cohort.stream()
                .filter(CohortObservation::usageMeasured)
                .toList();
        return new CohortStats(
                cohort.size(),
                (int) cohort.stream().filter(CohortObservation::completed).count(),
                measured.size(),
                round(cohort.stream().mapToDouble(CohortObservation::reward)
                        .average().orElse(0)),
                round(cohort.stream().mapToDouble(CohortObservation::grounding)
                        .average().orElse(0)),
                round(cohort.stream().filter(CohortObservation::completed).count()
                        / (double) cohort.size()),
                round(cohort.stream().mapToLong(CohortObservation::latencyMs)
                        .average().orElse(0)),
                round(measured.stream().mapToDouble(CohortObservation::costCny)
                        .average().orElse(0))
        );
    }

    private List<String> violations(CohortStats canary, CohortStats control) {
        List<String> violations = new ArrayList<>();
        regression(
                violations,
                "平均奖励",
                canary.averageReward(),
                control.averageReward(),
                maximumRewardRegression
        );
        regression(
                violations,
                "忠实度",
                canary.groundingRate(),
                control.groundingRate(),
                maximumGroundingRegression
        );
        regression(
                violations,
                "完成率",
                canary.completionRate(),
                control.completionRate(),
                maximumCompletionRegression
        );
        multiplier(
                violations,
                "平均延迟",
                canary.averageLatencyMs(),
                control.averageLatencyMs(),
                maximumLatencyMultiplier
        );
        if (canary.usageMeasuredSamples() > 0 && control.usageMeasuredSamples() > 0) {
            multiplier(
                    violations,
                    "平均成本",
                    canary.averageCostCny(),
                    control.averageCostCny(),
                    maximumCostMultiplier
            );
        }
        return List.copyOf(violations);
    }

    private void regression(List<String> violations,
                            String metric,
                            double canary,
                            double control,
                            double maximumRegression) {
        double delta = canary - control;
        if (delta < -maximumRegression) {
            violations.add("%s回退 %.4f，超过阈值 %.4f"
                    .formatted(metric, -delta, maximumRegression));
        }
    }

    private void multiplier(List<String> violations,
                            String metric,
                            double canary,
                            double control,
                            double maximumMultiplier) {
        if (control > 0 && canary / control > maximumMultiplier) {
            violations.add("%s为对照组 %.2f 倍，超过阈值 %.2f"
                    .formatted(metric, canary / control, maximumMultiplier));
        }
    }

    private QualityGuardReport inactive(GuardState state,
                                        RoutingPolicyDeployment deployment,
                                        Instant evaluatedAt,
                                        String reason) {
        return report(
                state,
                deployment,
                CohortStats.empty(),
                CohortStats.empty(),
                List.of(),
                evaluatedAt,
                reason
        );
    }

    private QualityGuardReport report(GuardState state,
                                      RoutingPolicyDeployment deployment,
                                      CohortStats canary,
                                      CohortStats control,
                                      List<String> violations,
                                      Instant evaluatedAt,
                                      String reason) {
        return new QualityGuardReport(
                enabled,
                state,
                deployment.version(),
                deployment.mode(),
                deploymentService.minimumCanarySamples(),
                minimumControlSamples,
                canary,
                control,
                maximumRewardRegression,
                maximumGroundingRegression,
                maximumCompletionRegression,
                maximumLatencyMultiplier,
                maximumCostMultiplier,
                violations,
                evaluatedAt,
                reason
        );
    }

    private RoutingPolicyDeployment safeCurrentDeployment(Instant now) {
        try {
            return deploymentService.current();
        } catch (RuntimeException exception) {
            return new RoutingPolicyDeployment(
                    "routing-unavailable",
                    RoutingPolicyRegistryService.BASELINE_VERSION,
                    RoutingPolicyMode.OFF,
                    1,
                    now,
                    "deployment unavailable"
            );
        }
    }

    private QualityGuardReport update(QualityGuardReport report) {
        latestReport = report;
        return report;
    }

    private double nonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }

    private double atLeastOne(double value, String name) {
        if (!Double.isFinite(value) || value < 1) {
            throw new IllegalArgumentException(name + " must be finite and at least 1");
        }
        return value;
    }

    private double round(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    public enum GuardState {
        DISABLED,
        INACTIVE,
        COLLECTING,
        HEALTHY,
        ROLLED_BACK,
        ERROR
    }

    public record CohortStats(
            int sampleCount,
            int completedCount,
            int usageMeasuredSamples,
            double averageReward,
            double groundingRate,
            double completionRate,
            double averageLatencyMs,
            double averageCostCny
    ) {
        static CohortStats empty() {
            return new CohortStats(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record QualityGuardReport(
            boolean enabled,
            GuardState state,
            String deploymentVersion,
            RoutingPolicyMode rolloutMode,
            int minimumCanarySamples,
            int minimumControlSamples,
            CohortStats canary,
            CohortStats control,
            double maximumRewardRegression,
            double maximumGroundingRegression,
            double maximumCompletionRegression,
            double maximumLatencyMultiplier,
            double maximumCostMultiplier,
            List<String> violations,
            Instant evaluatedAt,
            String reason
    ) {
        public boolean healthyForPromotion() {
            return state == GuardState.HEALTHY;
        }
    }

    private record CohortObservation(
            boolean canarySelected,
            boolean completed,
            double reward,
            double grounding,
            long latencyMs,
            double costCny,
            boolean usageMeasured
    ) {
    }
}
