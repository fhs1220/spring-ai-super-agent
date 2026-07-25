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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对 ACTIVE 策略执行长期概念漂移和质量漂移监控。
 * <p>
 * 参考窗口来自同一策略资产在 CANARY 阶段实际命中的轨迹，当前窗口只包含当前 ACTIVE
 * 发布版本。连续多次越界后自动降级到 SHADOW，避免单次噪声触发误回滚。
 */
@Service
public class RoutingPolicyDriftMonitor {

    private static final String AUTO_ROLLBACK_PREFIX =
            "automatic active drift rollback";

    private static final Logger log =
            LoggerFactory.getLogger(RoutingPolicyDriftMonitor.class);

    private final AgentTrajectoryRepository trajectoryRepository;

    private final RoutingPolicyDeploymentService deploymentService;

    private final boolean enabled;

    private final int minimumReferenceSamples;

    private final int minimumActiveSamples;

    private final int maximumSamplesPerWindow;

    private final int consecutiveViolationsRequired;

    private final double maximumFeatureJsDivergence;

    private final double maximumRewardRegression;

    private final double maximumGroundingRegression;

    private final double maximumCompletionRegression;

    private final double maximumLatencyMultiplier;

    private final double maximumCostMultiplier;

    private final Clock clock;

    private volatile DriftReport latestReport;

    private int consecutiveViolationCount;

    @Autowired
    public RoutingPolicyDriftMonitor(
            AgentTrajectoryRepository trajectoryRepository,
            RoutingPolicyDeploymentService deploymentService,
            @Value("${agent.rag.routing-policy.drift-monitor.enabled:true}")
            boolean enabled,
            @Value("${agent.rag.routing-policy.drift-monitor.minimum-reference-samples:20}")
            int minimumReferenceSamples,
            @Value("${agent.rag.routing-policy.drift-monitor.minimum-active-samples:30}")
            int minimumActiveSamples,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-samples-per-window:500}")
            int maximumSamplesPerWindow,
            @Value("${agent.rag.routing-policy.drift-monitor.consecutive-violations:3}")
            int consecutiveViolationsRequired,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-feature-js-divergence:0.20}")
            double maximumFeatureJsDivergence,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-reward-regression:0.05}")
            double maximumRewardRegression,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-grounding-regression:0.05}")
            double maximumGroundingRegression,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-completion-regression:0.05}")
            double maximumCompletionRegression,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-latency-multiplier:1.5}")
            double maximumLatencyMultiplier,
            @Value("${agent.rag.routing-policy.drift-monitor.maximum-cost-multiplier:1.5}")
            double maximumCostMultiplier) {
        this(
                trajectoryRepository,
                deploymentService,
                enabled,
                minimumReferenceSamples,
                minimumActiveSamples,
                maximumSamplesPerWindow,
                consecutiveViolationsRequired,
                maximumFeatureJsDivergence,
                maximumRewardRegression,
                maximumGroundingRegression,
                maximumCompletionRegression,
                maximumLatencyMultiplier,
                maximumCostMultiplier,
                Clock.systemUTC()
        );
    }

    RoutingPolicyDriftMonitor(
            AgentTrajectoryRepository trajectoryRepository,
            RoutingPolicyDeploymentService deploymentService,
            boolean enabled,
            int minimumReferenceSamples,
            int minimumActiveSamples,
            int maximumSamplesPerWindow,
            int consecutiveViolationsRequired,
            double maximumFeatureJsDivergence,
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
        this.minimumReferenceSamples = Math.max(1, minimumReferenceSamples);
        this.minimumActiveSamples = Math.max(1, minimumActiveSamples);
        this.maximumSamplesPerWindow = Math.max(1, maximumSamplesPerWindow);
        this.consecutiveViolationsRequired = Math.max(
                1, consecutiveViolationsRequired);
        this.maximumFeatureJsDivergence = betweenZeroAndOne(
                maximumFeatureJsDivergence, "maximumFeatureJsDivergence");
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
                    "${agent.rag.routing-policy.drift-monitor.evaluation-interval-ms:60000}",
            fixedDelayString =
                    "${agent.rag.routing-policy.drift-monitor.evaluation-interval-ms:60000}")
    public void scheduledEvaluation() {
        evaluateAndMaybeRollback();
    }

    public synchronized DriftReport evaluateAndMaybeRollback() {
        Instant evaluatedAt = clock.instant();
        try {
            RoutingPolicyDeployment deployment = deploymentService.current();
            if (!enabled) {
                consecutiveViolationCount = 0;
                return update(inactive(
                        DriftState.DISABLED,
                        deployment,
                        evaluatedAt,
                        "ACTIVE 漂移监控已关闭"
                ));
            }
            if (deployment.mode() != RoutingPolicyMode.ACTIVE) {
                boolean rolledBack = deployment.reason().startsWith(
                        AUTO_ROLLBACK_PREFIX);
                if (!rolledBack) {
                    consecutiveViolationCount = 0;
                }
                return update(inactive(
                        rolledBack ? DriftState.ROLLED_BACK : DriftState.INACTIVE,
                        deployment,
                        evaluatedAt,
                        rolledBack ? deployment.reason() : "当前发布模式不是 ACTIVE"
                ));
            }

            List<DriftObservation> all = observations(
                    deployment.policyVersion(),
                    deployment.version()
            );
            List<DriftObservation> reference = all.stream()
                    .filter(DriftObservation::reference)
                    .limit(maximumSamplesPerWindow)
                    .toList();
            List<DriftObservation> active = all.stream()
                    .filter(observation -> !observation.reference())
                    .limit(maximumSamplesPerWindow)
                    .toList();
            WindowStats referenceStats = stats(reference);
            WindowStats activeStats = stats(active);
            double featureJsDivergence = jsDivergence(
                    referenceStats.featureDistribution(),
                    activeStats.featureDistribution()
            );

            if (referenceStats.sampleCount() < minimumReferenceSamples
                    || activeStats.sampleCount() < minimumActiveSamples) {
                consecutiveViolationCount = 0;
                return update(report(
                        DriftState.COLLECTING,
                        deployment,
                        referenceStats,
                        activeStats,
                        featureJsDivergence,
                        List.of(),
                        evaluatedAt,
                        "继续收集基线/ACTIVE 样本（至少 %d/%d）"
                                .formatted(
                                        minimumReferenceSamples,
                                        minimumActiveSamples
                                )
                ));
            }

            List<String> violations = violations(
                    referenceStats,
                    activeStats,
                    featureJsDivergence
            );
            if (violations.isEmpty()) {
                consecutiveViolationCount = 0;
                return update(report(
                        DriftState.HEALTHY,
                        deployment,
                        referenceStats,
                        activeStats,
                        featureJsDivergence,
                        List.of(),
                        evaluatedAt,
                        "ACTIVE 策略分布与质量稳定"
                ));
            }

            consecutiveViolationCount++;
            if (consecutiveViolationCount < consecutiveViolationsRequired) {
                return update(report(
                        DriftState.DRIFTED,
                        deployment,
                        referenceStats,
                        activeStats,
                        featureJsDivergence,
                        violations,
                        evaluatedAt,
                        "检测到漂移，连续 %d/%d 次，达到门槛后自动降级"
                                .formatted(
                                        consecutiveViolationCount,
                                        consecutiveViolationsRequired
                                )
                ));
            }

            String reason = AUTO_ROLLBACK_PREFIX + ": "
                    + String.join("; ", violations);
            deploymentService.deploy(
                    RoutingPolicyMode.SHADOW,
                    null,
                    deployment.policyVersion(),
                    reason,
                    new RoutingPolicyDeploymentService.PromotionEvidence(
                            false, 0, false, false)
            );
            log.warn(
                    "ACTIVE 路由策略漂移，已自动降级 SHADOW，deployment={}, violations={}",
                    deployment.version(),
                    violations
            );
            return update(report(
                    DriftState.ROLLED_BACK,
                    deployment,
                    referenceStats,
                    activeStats,
                    featureJsDivergence,
                    violations,
                    evaluatedAt,
                    "连续检测到漂移，已自动降级至 SHADOW"
            ));
        } catch (RuntimeException exception) {
            log.error("ACTIVE 路由策略漂移监控失败，保持当前发布状态", exception);
            RoutingPolicyDeployment deployment = safeCurrentDeployment(evaluatedAt);
            return update(report(
                    DriftState.ERROR,
                    deployment,
                    WindowStats.empty(),
                    WindowStats.empty(),
                    0,
                    List.of(exception.getClass().getSimpleName()),
                    evaluatedAt,
                    "漂移监控失败，未改变当前发布状态"
            ));
        }
    }

    public DriftReport status() {
        DriftReport current = latestReport;
        RoutingPolicyDeployment deployment;
        try {
            deployment = deploymentService.current();
        } catch (RuntimeException exception) {
            return current == null ? evaluateAndMaybeRollback() : current;
        }
        boolean retainedRollback = current != null
                && current.state() == DriftState.ROLLED_BACK
                && deployment.mode() == RoutingPolicyMode.SHADOW
                && deployment.reason().startsWith(AUTO_ROLLBACK_PREFIX);
        if (current == null
                || (!retainedRollback
                && !current.deploymentVersion().equals(deployment.version()))) {
            return evaluateAndMaybeRollback();
        }
        return current;
    }

    private List<DriftObservation> observations(
            String policyArtifactVersion,
            String activeDeploymentVersion) {
        return trajectoryRepository.findAll().stream()
                .map(trajectory -> observation(
                        trajectory,
                        policyArtifactVersion,
                        activeDeploymentVersion
                ))
                .filter(Objects::nonNull)
                .toList();
    }

    private DriftObservation observation(
            AgentTrajectory trajectory,
            String policyArtifactVersion,
            String activeDeploymentVersion) {
        AgentStep route = routeStep(trajectory);
        if (route == null || !policyArtifactVersion.equals(Objects.toString(
                route.output().get("policyArtifactVersion"), ""))) {
            return null;
        }
        String rolloutMode = Objects.toString(
                route.output().get("policyRolloutMode"), "");
        boolean reference = RoutingPolicyMode.CANARY.name().equals(rolloutMode)
                && Boolean.TRUE.equals(
                route.output().get("policyCanarySelected"));
        boolean active = RoutingPolicyMode.ACTIVE.name().equals(rolloutMode)
                && activeDeploymentVersion.equals(Objects.toString(
                route.output().get("policyDeploymentVersion"), ""));
        if (!reference && !active) {
            return null;
        }
        boolean completed = "COMPLETED".equals(trajectory.status());
        double reward = trajectory.reward() == null
                ? 0
                : trajectory.reward().total();
        double grounding = trajectory.reward() == null
                ? 0
                : trajectory.reward().groundingQuality();
        long latency = trajectory.steps() == null
                ? 0
                : trajectory.steps().stream()
                        .mapToLong(AgentStep::durationMs)
                        .sum();
        boolean usageMeasured = trajectory.telemetry() != null;
        double cost = usageMeasured
                ? trajectory.telemetry().estimatedCostCny()
                : 0;
        return new DriftObservation(
                reference,
                Objects.toString(
                        route.output().get("featureBucket"), "UNKNOWN"),
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

    private WindowStats stats(List<DriftObservation> observations) {
        if (observations.isEmpty()) {
            return WindowStats.empty();
        }
        List<DriftObservation> measured = observations.stream()
                .filter(DriftObservation::usageMeasured)
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        observations.forEach(observation -> counts.merge(
                observation.featureBucket().isBlank()
                        ? "UNKNOWN"
                        : observation.featureBucket(),
                1L,
                Long::sum
        ));
        Map<String, Double> distribution = new LinkedHashMap<>();
        counts.forEach((bucket, count) -> distribution.put(
                bucket,
                round(count / (double) observations.size())
        ));
        return new WindowStats(
                observations.size(),
                (int) observations.stream()
                        .filter(DriftObservation::completed)
                        .count(),
                measured.size(),
                round(observations.stream()
                        .mapToDouble(DriftObservation::reward)
                        .average()
                        .orElse(0)),
                round(observations.stream()
                        .mapToDouble(DriftObservation::grounding)
                        .average()
                        .orElse(0)),
                round(observations.stream()
                        .filter(DriftObservation::completed)
                        .count() / (double) observations.size()),
                round(observations.stream()
                        .mapToLong(DriftObservation::latencyMs)
                        .average()
                        .orElse(0)),
                round(measured.stream()
                        .mapToDouble(DriftObservation::costCny)
                        .average()
                        .orElse(0)),
                Map.copyOf(distribution)
        );
    }

    private double jsDivergence(
            Map<String, Double> reference,
            Map<String, Double> current) {
        if (reference.isEmpty() || current.isEmpty()) {
            return 0;
        }
        Set<String> buckets = new LinkedHashSet<>(reference.keySet());
        buckets.addAll(current.keySet());
        double divergence = 0;
        for (String bucket : buckets) {
            double p = reference.getOrDefault(bucket, 0.0);
            double q = current.getOrDefault(bucket, 0.0);
            double midpoint = (p + q) / 2;
            if (p > 0) {
                divergence += 0.5 * p * log2(p / midpoint);
            }
            if (q > 0) {
                divergence += 0.5 * q * log2(q / midpoint);
            }
        }
        return round(divergence);
    }

    private List<String> violations(
            WindowStats reference,
            WindowStats active,
            double featureJsDivergence) {
        List<String> violations = new ArrayList<>();
        if (featureJsDivergence > maximumFeatureJsDivergence) {
            violations.add("特征分布 JS 距离 %.4f，超过阈值 %.4f"
                    .formatted(
                            featureJsDivergence,
                            maximumFeatureJsDivergence
                    ));
        }
        regression(
                violations,
                "平均奖励",
                active.averageReward(),
                reference.averageReward(),
                maximumRewardRegression
        );
        regression(
                violations,
                "忠实度",
                active.groundingRate(),
                reference.groundingRate(),
                maximumGroundingRegression
        );
        regression(
                violations,
                "完成率",
                active.completionRate(),
                reference.completionRate(),
                maximumCompletionRegression
        );
        multiplier(
                violations,
                "平均延迟",
                active.averageLatencyMs(),
                reference.averageLatencyMs(),
                maximumLatencyMultiplier
        );
        if (active.usageMeasuredSamples() > 0
                && reference.usageMeasuredSamples() > 0) {
            multiplier(
                    violations,
                    "平均成本",
                    active.averageCostCny(),
                    reference.averageCostCny(),
                    maximumCostMultiplier
            );
        }
        return List.copyOf(violations);
    }

    private void regression(
            List<String> violations,
            String metric,
            double current,
            double reference,
            double maximumRegression) {
        double delta = current - reference;
        if (delta < -maximumRegression) {
            violations.add("%s回退 %.4f，超过阈值 %.4f"
                    .formatted(metric, -delta, maximumRegression));
        }
    }

    private void multiplier(
            List<String> violations,
            String metric,
            double current,
            double reference,
            double maximumMultiplier) {
        if (reference > 0 && current / reference > maximumMultiplier) {
            violations.add("%s为基线 %.2f 倍，超过阈值 %.2f"
                    .formatted(
                            metric,
                            current / reference,
                            maximumMultiplier
                    ));
        }
    }

    private DriftReport inactive(
            DriftState state,
            RoutingPolicyDeployment deployment,
            Instant evaluatedAt,
            String reason) {
        return report(
                state,
                deployment,
                WindowStats.empty(),
                WindowStats.empty(),
                0,
                List.of(),
                evaluatedAt,
                reason
        );
    }

    private DriftReport report(
            DriftState state,
            RoutingPolicyDeployment deployment,
            WindowStats reference,
            WindowStats active,
            double featureJsDivergence,
            List<String> violations,
            Instant evaluatedAt,
            String reason) {
        return new DriftReport(
                enabled,
                state,
                deployment.version(),
                deployment.policyVersion(),
                deployment.mode(),
                minimumReferenceSamples,
                minimumActiveSamples,
                reference,
                active,
                featureJsDivergence,
                maximumFeatureJsDivergence,
                consecutiveViolationCount,
                consecutiveViolationsRequired,
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

    private DriftReport update(DriftReport report) {
        latestReport = report;
        return report;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private double betweenZeroAndOne(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
        return value;
    }

    private double nonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative");
        }
        return value;
    }

    private double atLeastOne(double value, String name) {
        if (!Double.isFinite(value) || value < 1) {
            throw new IllegalArgumentException(
                    name + " must be finite and at least 1");
        }
        return value;
    }

    private double round(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    public enum DriftState {
        DISABLED,
        INACTIVE,
        COLLECTING,
        HEALTHY,
        DRIFTED,
        ROLLED_BACK,
        ERROR
    }

    public record WindowStats(
            int sampleCount,
            int completedCount,
            int usageMeasuredSamples,
            double averageReward,
            double groundingRate,
            double completionRate,
            double averageLatencyMs,
            double averageCostCny,
            Map<String, Double> featureDistribution
    ) {

        public WindowStats {
            featureDistribution = featureDistribution == null
                    ? Map.of()
                    : Map.copyOf(featureDistribution);
        }

        static WindowStats empty() {
            return new WindowStats(
                    0, 0, 0, 0, 0, 0, 0, 0, Map.of());
        }
    }

    public record DriftReport(
            boolean enabled,
            DriftState state,
            String deploymentVersion,
            String policyArtifactVersion,
            RoutingPolicyMode rolloutMode,
            int minimumReferenceSamples,
            int minimumActiveSamples,
            WindowStats reference,
            WindowStats active,
            double featureJsDivergence,
            double maximumFeatureJsDivergence,
            int consecutiveViolationCount,
            int consecutiveViolationsRequired,
            double maximumRewardRegression,
            double maximumGroundingRegression,
            double maximumCompletionRegression,
            double maximumLatencyMultiplier,
            double maximumCostMultiplier,
            List<String> violations,
            Instant evaluatedAt,
            String reason
    ) {
    }

    private record DriftObservation(
            boolean reference,
            String featureBucket,
            boolean completed,
            double reward,
            double grounding,
            long latencyMs,
            double costCny,
            boolean usageMeasured
    ) {
    }
}
