package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从线上轨迹估计单 Agent 与多 Agent 的质量、成本和延迟效用。
 * <p>
 * 策略采用保守门禁：两种模式都达到最小样本数后才允许覆盖确定性路由；
 * 安全类任务始终由上层执行安全覆盖，不参与成本驱动的降级。
 */
@Component
public class TrajectoryAwareRoutingPolicy {

    private final AgentTrajectoryRepository repository;

    private final boolean enabled;

    private final int minimumSamplesPerMode;

    private final int maximumTrajectories;

    private final double minimumUtilityLift;

    private final double successRewardThreshold;

    private final double costWeight;

    private final double latencyWeight;

    private final double costBudgetCny;

    private final double latencyBudgetMs;

    private final Duration refreshInterval;

    private final Clock clock;

    private final RoutingPolicyDeployment fallbackDeployment;

    private final RoutingPolicyDeployment unavailableDeployment;

    private RoutingPolicyDeploymentService deploymentService;

    private RoutingPolicyRegistryService registryService;

    private volatile PolicySnapshot cachedSnapshot;

    @Autowired
    public TrajectoryAwareRoutingPolicy(
            AgentTrajectoryRepository repository,
            @Value("${agent.rag.routing-policy.enabled:true}") boolean enabled,
            @Value("${agent.rag.routing-policy.minimum-samples-per-mode:8}")
            int minimumSamplesPerMode,
            @Value("${agent.rag.routing-policy.maximum-trajectories:500}")
            int maximumTrajectories,
            @Value("${agent.rag.routing-policy.minimum-utility-lift:0.03}")
            double minimumUtilityLift,
            @Value("${agent.rag.routing-policy.success-reward-threshold:0.72}")
            double successRewardThreshold,
            @Value("${agent.rag.routing-policy.cost-weight:0.05}") double costWeight,
            @Value("${agent.rag.routing-policy.latency-weight:0.05}") double latencyWeight,
            @Value("${agent.rag.routing-policy.cost-budget-cny:0.02}") double costBudgetCny,
            @Value("${agent.rag.routing-policy.latency-budget-ms:60000}") double latencyBudgetMs,
            @Value("${agent.rag.routing-policy.refresh-seconds:30}") long refreshSeconds,
            RoutingPolicyDeploymentService deploymentService,
            RoutingPolicyRegistryService registryService) {
        this(
                repository,
                enabled,
                minimumSamplesPerMode,
                maximumTrajectories,
                minimumUtilityLift,
                successRewardThreshold,
                costWeight,
                latencyWeight,
                costBudgetCny,
                latencyBudgetMs,
                Duration.ofSeconds(Math.max(1, refreshSeconds)),
                Clock.systemUTC()
        );
        this.deploymentService = Objects.requireNonNull(
                deploymentService, "deploymentService");
        this.registryService = Objects.requireNonNull(
                registryService, "registryService");
    }

    TrajectoryAwareRoutingPolicy(AgentTrajectoryRepository repository,
                                 boolean enabled,
                                 int minimumSamplesPerMode,
                                 int maximumTrajectories,
                                 double minimumUtilityLift,
                                 double successRewardThreshold,
                                 double costWeight,
                                 double latencyWeight,
                                 double costBudgetCny,
                                 double latencyBudgetMs,
                                 Duration refreshInterval,
                                 Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.enabled = enabled;
        this.minimumSamplesPerMode = Math.max(2, minimumSamplesPerMode);
        this.maximumTrajectories = Math.max(20, maximumTrajectories);
        this.minimumUtilityLift = positive(minimumUtilityLift, "minimumUtilityLift");
        this.successRewardThreshold = clamp(successRewardThreshold);
        this.costWeight = clampPositive(costWeight);
        this.latencyWeight = clampPositive(latencyWeight);
        this.costBudgetCny = positive(costBudgetCny, "costBudgetCny");
        this.latencyBudgetMs = positive(latencyBudgetMs, "latencyBudgetMs");
        this.refreshInterval = requirePositive(refreshInterval, "refreshInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fallbackDeployment = new RoutingPolicyDeployment(
                "routing-test-active",
                RoutingPolicyRegistryService.BASELINE_VERSION,
                RoutingPolicyMode.ACTIVE,
                1,
                clock.instant(),
                "test/default deployment"
        );
        // 发布状态读取失败时的展示/决策回退：必须是 OFF，避免把故障显示成“正式生效”。
        this.unavailableDeployment = new RoutingPolicyDeployment(
                "routing-unavailable",
                RoutingPolicyRegistryService.BASELINE_VERSION,
                RoutingPolicyMode.OFF,
                1,
                clock.instant(),
                "发布状态不可用，临时回退确定性路由"
        );
    }

    TrajectoryAwareRoutingPolicy(AgentTrajectoryRepository repository,
                                 boolean enabled,
                                 int minimumSamplesPerMode,
                                 int maximumTrajectories,
                                 double minimumUtilityLift,
                                 double successRewardThreshold,
                                 double costWeight,
                                 double latencyWeight,
                                 double costBudgetCny,
                                 double latencyBudgetMs,
                                 Duration refreshInterval,
                                 Clock clock,
                                 RoutingPolicyDeploymentService deploymentService) {
        this(
                repository,
                enabled,
                minimumSamplesPerMode,
                maximumTrajectories,
                minimumUtilityLift,
                successRewardThreshold,
                costWeight,
                latencyWeight,
                costBudgetCny,
                latencyBudgetMs,
                refreshInterval,
                clock
        );
        this.deploymentService = Objects.requireNonNull(
                deploymentService, "deploymentService");
    }

    TrajectoryAwareRoutingPolicy(AgentTrajectoryRepository repository,
                                 boolean enabled,
                                 int minimumSamplesPerMode,
                                 int maximumTrajectories,
                                 double minimumUtilityLift,
                                 double successRewardThreshold,
                                 double costWeight,
                                 double latencyWeight,
                                 double costBudgetCny,
                                 double latencyBudgetMs,
                                 Duration refreshInterval,
                                 Clock clock,
                                 RoutingPolicyDeploymentService deploymentService,
                                 RoutingPolicyRegistryService registryService) {
        this(
                repository,
                enabled,
                minimumSamplesPerMode,
                maximumTrajectories,
                minimumUtilityLift,
                successRewardThreshold,
                costWeight,
                latencyWeight,
                costBudgetCny,
                latencyBudgetMs,
                refreshInterval,
                clock,
                deploymentService
        );
        this.registryService = Objects.requireNonNull(registryService, "registryService");
    }

    public RoutingPolicyDecision decide(RoutingContext context) {
        Objects.requireNonNull(context, "context");
        RoutingPolicyDeployment deployment;
        try {
            deployment = currentDeployment();
        } catch (RuntimeException exception) {
            return deterministic(
                    context,
                    unavailableDeployment,
                    "DEPLOYMENT_FALLBACK",
                    "发布状态不可用，回退确定性路由"
            );
        }
        if (context.safetyCritical()) {
            return new RoutingPolicyDecision(
                    true,
                    true,
                    "SAFETY_OVERRIDE",
                    "SAFETY_OVERRIDE",
                    deployment.mode(),
                    false,
                    false,
                    1.0,
                    0,
                    deployment.version(),
                    deployment.policyVersion(),
                    "检测到关系安全风险，强制启用安全专业 Agent"
            );
        }
        if (!enabled || deployment.mode() == RoutingPolicyMode.OFF) {
            return deterministic(
                    context,
                    deployment,
                    "OFF",
                    "学习型路由发布模式为 OFF"
            );
        }

        CandidateDecision candidate = candidate(context, deployment);
        return applyDeployment(context, candidate, deployment);
    }

    private CandidateDecision candidate(RoutingContext context,
                                        RoutingPolicyDeployment deployment) {
        if ((deployment.mode() == RoutingPolicyMode.CANARY
                || deployment.mode() == RoutingPolicyMode.ACTIVE)
                && !RoutingPolicyRegistryService.BASELINE_VERSION.equals(
                deployment.policyVersion())) {
            return artifactCandidate(context, deployment.policyVersion());
        }
        return dynamicCandidate(context);
    }

    private CandidateDecision artifactCandidate(RoutingContext context,
                                                String policyVersion) {
        if (registryService == null) {
            return dynamicCandidate(context);
        }
        try {
            RoutingPolicyArtifact artifact = registryService.requireDeployable(policyVersion);
            RoutingPolicyArtifact.OfflineEvaluation evaluation =
                    artifact.offlineEvaluation();
            boolean multiAgent = AdaptiveMultiAgentOrchestrator.MULTI_MODE.equals(
                    evaluation.recommendedMode());
            int evidenceSamples = evaluation.singleAgent().sampleCount()
                    + evaluation.multiAgent().sampleCount();
            return new CandidateDecision(
                    multiAgent,
                    "LEARNED_ARTIFACT",
                    confidence(evaluation.multiAgentUtilityLift(), evidenceSamples),
                    evidenceSamples,
                    "执行冻结策略资产 %s（训练指纹 %s）"
                            .formatted(
                                    artifact.version(),
                                    artifact.trainingDataFingerprint().substring(0, 12)
                            )
            );
        } catch (RuntimeException exception) {
            return new CandidateDecision(
                    context.deterministicMultiAgent(),
                    "ARTIFACT_FALLBACK",
                    0,
                    0,
                    "策略资产不可用，回退确定性路由"
            );
        }
    }

    private CandidateDecision dynamicCandidate(RoutingContext context) {
        PolicySnapshot snapshot;
        try {
            snapshot = snapshot();
        } catch (RuntimeException exception) {
            return new CandidateDecision(
                    context.deterministicMultiAgent(),
                    "POLICY_FALLBACK",
                    0,
                    0,
                    "轨迹策略暂时不可用，回退确定性路由"
            );
        }
        ModeComparison comparison = snapshot.contextual().get(context.featureBucket());
        String scope = "CONTEXTUAL";
        if (comparison == null || !comparison.ready(minimumSamplesPerMode)) {
            comparison = snapshot.global();
            scope = "GLOBAL";
        }
        if (!comparison.ready(minimumSamplesPerMode)) {
            return new CandidateDecision(
                    context.deterministicMultiAgent(),
                    "COLD_START",
                    0,
                    0,
                    "历史样本不足，继续使用确定性复杂度路由（单/多 Agent 至少各需 %d 条）"
                            .formatted(minimumSamplesPerMode)
            );
        }

        double lift = comparison.multiAgent().utility() - comparison.singleAgent().utility();
        int evidenceSamples = Math.min(
                comparison.multiAgent().sampleCount(),
                comparison.singleAgent().sampleCount()
        );
        double confidence = confidence(lift, evidenceSamples);
        if (lift >= minimumUtilityLift) {
            return new CandidateDecision(
                    true,
                    "LEARNED_" + scope,
                    confidence,
                    evidenceSamples,
                    "历史净效用支持多 Agent（提升 %.4f）".formatted(lift)
            );
        }
        if (lift <= -minimumUtilityLift) {
            return new CandidateDecision(
                    false,
                    "LEARNED_" + scope,
                    confidence,
                    evidenceSamples,
                    "历史净效用支持单 Agent（提升 %.4f）".formatted(-lift)
            );
        }
        return new CandidateDecision(
                context.deterministicMultiAgent(),
                "LEARNED_TIE_" + scope,
                confidence,
                evidenceSamples,
                "两种模式净效用差 %.4f 未达到覆盖门槛 %.4f"
                        .formatted(Math.abs(lift), minimumUtilityLift)
        );
    }

    public RoutingPolicyStatus status() {
        RoutingPolicyDeployment deployment;
        try {
            deployment = currentDeployment();
        } catch (RuntimeException exception) {
            deployment = unavailableDeployment;
        }
        PolicySnapshot snapshot;
        try {
            snapshot = snapshot();
        } catch (RuntimeException exception) {
            ModeStats empty = new ModeStats(0, 0, 0, 0, 0, 0, 0);
            return new RoutingPolicyStatus(
                    enabled,
                    false,
                    deployment,
                    minimumSamplesPerMode,
                    0,
                    empty,
                    empty,
                    0,
                    0,
                    minimumCanarySamples(),
                    clock.instant(),
                    "轨迹策略状态不可用：" + exception.getClass().getSimpleName()
            );
        }
        boolean ready = enabled && snapshot.global().ready(minimumSamplesPerMode);
        return new RoutingPolicyStatus(
                enabled,
                ready,
                deployment,
                minimumSamplesPerMode,
                snapshot.observedTrajectoryCount(),
                snapshot.global().singleAgent(),
                snapshot.global().multiAgent(),
                snapshot.contextual().size(),
                snapshot.canarySelectedTrajectoryCount(),
                minimumCanarySamples(),
                snapshot.refreshedAt(),
                ready
                        ? "策略已有足够的单/多 Agent 对照轨迹"
                        : "冷启动：继续收集两种模式的完成或失败轨迹"
        );
    }

    public void invalidate() {
        cachedSnapshot = null;
    }

    private RoutingPolicyDecision applyDeployment(
            RoutingContext context,
            CandidateDecision candidate,
            RoutingPolicyDeployment deployment) {
        boolean learnedCandidate = candidate.source().startsWith("LEARNED_")
                && !candidate.source().startsWith("LEARNED_TIE_");
        if (deployment.mode() == RoutingPolicyMode.SHADOW) {
            return new RoutingPolicyDecision(
                    context.deterministicMultiAgent(),
                    candidate.multiAgent(),
                    "SHADOW_DETERMINISTIC",
                    candidate.source(),
                    deployment.mode(),
                    false,
                    false,
                    candidate.confidence(),
                    candidate.evidenceSamples(),
                    deployment.version(),
                    deployment.policyVersion(),
                    "Shadow 仅记录候选，不改变执行；" + candidate.reason()
            );
        }
        if (deployment.mode() == RoutingPolicyMode.CANARY) {
            boolean selected = learnedCandidate
                    && stableFraction(context.routingKey()) < deployment.canaryRate();
            boolean applied = selected
                    && candidate.multiAgent() != context.deterministicMultiAgent();
            return new RoutingPolicyDecision(
                    selected ? candidate.multiAgent() : context.deterministicMultiAgent(),
                    candidate.multiAgent(),
                    selected ? "CANARY_" + candidate.source() : "CANARY_CONTROL",
                    candidate.source(),
                    deployment.mode(),
                    applied,
                    selected,
                    candidate.confidence(),
                    candidate.evidenceSamples(),
                    deployment.version(),
                    deployment.policyVersion(),
                    selected
                            ? "命中灰度流量；" + candidate.reason()
                            : "未命中灰度流量，执行确定性路由；" + candidate.reason()
            );
        }
        boolean applied = learnedCandidate
                && candidate.multiAgent() != context.deterministicMultiAgent();
        return new RoutingPolicyDecision(
                candidate.multiAgent(),
                candidate.multiAgent(),
                candidate.source(),
                candidate.source(),
                deployment.mode(),
                applied,
                false,
                candidate.confidence(),
                candidate.evidenceSamples(),
                deployment.version(),
                deployment.policyVersion(),
                candidate.reason()
        );
    }

    private RoutingPolicyDecision deterministic(RoutingContext context,
                                                RoutingPolicyDeployment deployment,
                                                String source,
                                                String reason) {
        return new RoutingPolicyDecision(
                context.deterministicMultiAgent(),
                context.deterministicMultiAgent(),
                source,
                source,
                deployment.mode(),
                false,
                false,
                0,
                0,
                deployment.version(),
                deployment.policyVersion(),
                reason
        );
    }

    private RoutingPolicyDeployment currentDeployment() {
        return deploymentService == null
                ? fallbackDeployment
                : deploymentService.current();
    }

    private int minimumCanarySamples() {
        return deploymentService == null ? 1 : deploymentService.minimumCanarySamples();
    }

    private double stableFraction(String routingKey) {
        long unsigned = Integer.toUnsignedLong(
                Objects.toString(routingKey, "").hashCode());
        return unsigned / 4_294_967_296.0;
    }

    private PolicySnapshot snapshot() {
        PolicySnapshot current = cachedSnapshot;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.refreshedAt().plus(refreshInterval))) {
            return current;
        }
        synchronized (this) {
            current = cachedSnapshot;
            if (current != null && now.isBefore(current.refreshedAt().plus(refreshInterval))) {
                return current;
            }
            PolicySnapshot refreshed = buildSnapshot(now);
            cachedSnapshot = refreshed;
            return refreshed;
        }
    }

    private PolicySnapshot buildSnapshot(Instant refreshedAt) {
        List<Observation> observations = repository.findAll().stream()
                .filter(this::eligible)
                .limit(maximumTrajectories)
                .map(this::observation)
                .filter(Objects::nonNull)
                .toList();
        ModeComparison global = comparison(observations);
        Map<String, List<Observation>> grouped = new LinkedHashMap<>();
        observations.stream()
                .filter(observation -> !observation.featureBucket().isBlank())
                .forEach(observation -> grouped
                        .computeIfAbsent(observation.featureBucket(), ignored -> new ArrayList<>())
                        .add(observation));
        Map<String, ModeComparison> contextual = new LinkedHashMap<>();
        grouped.forEach((bucket, values) -> contextual.put(bucket, comparison(values)));
        return new PolicySnapshot(
                observations.size(),
                global,
                Map.copyOf(contextual),
                (int) observations.stream()
                        .filter(Observation::canarySelected)
                        .count(),
                refreshedAt
        );
    }

    private boolean eligible(AgentTrajectory trajectory) {
        return trajectory != null
                && ("COMPLETED".equals(trajectory.status()) || "FAILED".equals(trajectory.status()))
                && routeStep(trajectory) != null;
    }

    private Observation observation(AgentTrajectory trajectory) {
        AgentStep route = routeStep(trajectory);
        String mode = Objects.toString(route.output().get("mode"), "");
        if (!AdaptiveMultiAgentOrchestrator.SINGLE_MODE.equals(mode)
                && !AdaptiveMultiAgentOrchestrator.MULTI_MODE.equals(mode)) {
            return null;
        }
        double reward = trajectory.reward() == null ? 0 : trajectory.reward().total();
        boolean successful = "COMPLETED".equals(trajectory.status())
                && trajectory.reward() != null
                && reward >= successRewardThreshold;
        long latencyMs = trajectory.steps() == null
                ? 0
                : trajectory.steps().stream().mapToLong(AgentStep::durationMs).sum();
        boolean usageMeasured = trajectory.telemetry() != null;
        double cost = usageMeasured ? trajectory.telemetry().estimatedCostCny() : 0;
        return new Observation(
                mode,
                Objects.toString(route.output().get("featureBucket"), ""),
                reward,
                successful,
                cost,
                usageMeasured,
                latencyMs,
                "CANARY".equals(Objects.toString(
                        route.output().get("policyRolloutMode"), ""))
                        && Boolean.TRUE.equals(route.output().get("policyCanarySelected"))
        );
    }

    private AgentStep routeStep(AgentTrajectory trajectory) {
        if (trajectory.steps() == null) {
            return null;
        }
        return trajectory.steps().stream()
                .filter(step -> step.type() == AgentStepType.ROUTE)
                .findFirst()
                .orElse(null);
    }

    private ModeComparison comparison(List<Observation> observations) {
        return new ModeComparison(
                stats(observations, AdaptiveMultiAgentOrchestrator.SINGLE_MODE),
                stats(observations, AdaptiveMultiAgentOrchestrator.MULTI_MODE)
        );
    }

    private ModeStats stats(List<Observation> observations, String mode) {
        List<Observation> selected = observations.stream()
                .filter(observation -> mode.equals(observation.mode()))
                .toList();
        if (selected.isEmpty()) {
            return new ModeStats(0, 0, 0, 0, 0, 0, 0);
        }
        int successful = (int) selected.stream().filter(Observation::successful).count();
        double reward = selected.stream().mapToDouble(Observation::reward).average().orElse(0);
        List<Observation> measuredUsage = selected.stream()
                .filter(Observation::usageMeasured)
                .toList();
        double cost = measuredUsage.stream()
                .mapToDouble(Observation::costCny)
                .average()
                .orElse(0);
        double latency = selected.stream()
                .mapToLong(Observation::latencyMs)
                .average()
                .orElse(0);
        double utility = reward
                - costWeight * Math.min(1, cost / costBudgetCny)
                - latencyWeight * Math.min(1, latency / latencyBudgetMs);
        return new ModeStats(
                selected.size(),
                successful,
                measuredUsage.size(),
                round(reward),
                round(cost),
                round(latency),
                round(utility)
        );
    }

    private double confidence(double utilityLift, int evidenceSamples) {
        double sampleConfidence = Math.min(1, evidenceSamples / (double) (minimumSamplesPerMode * 3));
        double liftConfidence = Math.min(1, Math.abs(utilityLift) / (minimumUtilityLift * 3));
        return round(0.55 * sampleConfidence + 0.45 * liftConfidence);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double clampPositive(double value) {
        return Math.max(0, value);
    }

    private double positive(double value, String name) {
        if (value <= 0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private double round(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    public record RoutingContext(
            String featureBucket,
            boolean deterministicMultiAgent,
            boolean safetyCritical,
            String routingKey
    ) {
    }

    public record RoutingPolicyDecision(
            boolean multiAgent,
            boolean candidateMultiAgent,
            String source,
            String candidateSource,
            RoutingPolicyMode rolloutMode,
            boolean learnedApplied,
            boolean canarySelected,
            double confidence,
            int evidenceSamples,
            String deploymentVersion,
            String policyArtifactVersion,
            String reason
    ) {
    }

    public record ModeStats(
            int sampleCount,
            int successfulCount,
            int usageMeasuredSamples,
            double averageReward,
            double averageCostCny,
            double averageLatencyMs,
            double utility
    ) {
    }

    public record RoutingPolicyStatus(
            boolean enabled,
            boolean ready,
            RoutingPolicyDeployment deployment,
            int minimumSamplesPerMode,
            int observedTrajectoryCount,
            ModeStats singleAgent,
            ModeStats multiAgent,
            int contextualBucketCount,
            int canarySelectedTrajectoryCount,
            int minimumCanarySamples,
            Instant refreshedAt,
            String reason
    ) {
    }

    private record Observation(
            String mode,
            String featureBucket,
            double reward,
            boolean successful,
            double costCny,
            boolean usageMeasured,
            long latencyMs,
            boolean canarySelected
    ) {
    }

    private record ModeComparison(ModeStats singleAgent, ModeStats multiAgent) {

        private boolean ready(int minimumSamplesPerMode) {
            return singleAgent.sampleCount() >= minimumSamplesPerMode
                    && multiAgent.sampleCount() >= minimumSamplesPerMode;
        }
    }

    private record PolicySnapshot(
            int observedTrajectoryCount,
            ModeComparison global,
            Map<String, ModeComparison> contextual,
            int canarySelectedTrajectoryCount,
            Instant refreshedAt
    ) {
    }

    private record CandidateDecision(
            boolean multiAgent,
            String source,
            double confidence,
            int evidenceSamples,
            String reason
    ) {
    }
}
