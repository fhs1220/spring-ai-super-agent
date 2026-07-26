package com.fhs.aiagent.rag.multiagent;

import jakarta.annotation.PostConstruct;
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
import java.util.UUID;

/**
 * 在显式三重授权后执行渐进式发布建议，并持久化每次真实流量变更。
 */
@Service
public class RoutingPolicyProgressiveDeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(
            RoutingPolicyProgressiveDeliveryExecutor.class);

    private final RoutingPolicyDeploymentService deploymentService;

    private final RoutingPolicyRegistryService registryService;

    private final RoutingPolicyProgressiveDeliveryAdvisor advisor;

    private final ProgressiveDeliveryAutomationRepository repository;

    private final boolean executorConfigured;

    private final boolean emergencyStop;

    private final boolean defaultAutomationEnabled;

    private final int maximumHistory;

    private final Clock clock;

    private volatile ProgressiveDeliveryAutomationState state;

    @Autowired
    public RoutingPolicyProgressiveDeliveryExecutor(
            RoutingPolicyDeploymentService deploymentService,
            RoutingPolicyRegistryService registryService,
            RoutingPolicyProgressiveDeliveryAdvisor advisor,
            ProgressiveDeliveryAutomationRepository repository,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".auto-apply-enabled:false}")
            boolean executorConfigured,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".emergency-stop:false}")
            boolean emergencyStop,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".automation-enabled-by-default:false}")
            boolean defaultAutomationEnabled,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".maximum-audit-history:100}")
            int maximumHistory) {
        this(
                deploymentService,
                registryService,
                advisor,
                repository,
                executorConfigured,
                emergencyStop,
                defaultAutomationEnabled,
                maximumHistory,
                Clock.systemUTC()
        );
    }

    RoutingPolicyProgressiveDeliveryExecutor(
            RoutingPolicyDeploymentService deploymentService,
            RoutingPolicyRegistryService registryService,
            RoutingPolicyProgressiveDeliveryAdvisor advisor,
            ProgressiveDeliveryAutomationRepository repository,
            boolean executorConfigured,
            boolean emergencyStop,
            boolean defaultAutomationEnabled,
            int maximumHistory,
            Clock clock) {
        this.deploymentService = Objects.requireNonNull(
                deploymentService, "deploymentService");
        this.registryService = Objects.requireNonNull(
                registryService, "registryService");
        this.advisor = Objects.requireNonNull(advisor, "advisor");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executorConfigured = executorConfigured;
        this.emergencyStop = emergencyStop;
        this.defaultAutomationEnabled = defaultAutomationEnabled;
        this.maximumHistory = Math.max(10, maximumHistory);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostConstruct
    public synchronized void initialize() {
        state = repository.load().orElseGet(this::initialState);
        state = repository.save(state);
    }

    @Scheduled(
            initialDelayString =
                    "${agent.rag.routing-policy.progressive-delivery"
                            + ".evaluation-interval-ms:60000}",
            fixedDelayString =
                    "${agent.rag.routing-policy.progressive-delivery"
                            + ".evaluation-interval-ms:60000}")
    public void scheduledExecution() {
        try {
            runOnce("scheduled");
        } catch (RuntimeException exception) {
            log.error("渐进式自动发布定时执行失败", exception);
        }
    }

    public synchronized ExecutionResult runOnce(String trigger) {
        ProgressiveDeliveryAutomationState currentState = requireState();
        Instant now = clock.instant();
        if (emergencyStop) {
            return emergencyStop(currentState, trigger, now);
        }

        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport
                recommendation = advisor.recommend();
        List<String> blockers = executionBlockers(
                currentState,
                recommendation
        );
        if (!blockers.isEmpty()) {
            return new ExecutionResult(
                    false,
                    false,
                    null,
                    status(recommendation, blockers, now),
                    String.join("；", blockers)
            );
        }

        RoutingPolicyDeployment source = deploymentService.current();
        if (!source.version().equals(recommendation.deploymentVersion())) {
            String reason = "部署版本已变化，本次建议作废";
            return new ExecutionResult(
                    false,
                    false,
                    null,
                    status(recommendation, List.of(reason), now),
                    reason
            );
        }

        try {
            RoutingPolicyArtifact artifact = registryService.requireDeployable(
                    recommendation.policyArtifactVersion());
            RoutingPolicyDeploymentState deployed = deploymentService.deploy(
                    recommendation.recommendedMode(),
                    recommendation.recommendedMode() == RoutingPolicyMode.CANARY
                            ? recommendation.recommendedTrafficRate()
                            : null,
                    artifact.version(),
                    "automatic progressive delivery: " + recommendation.reason(),
                    new RoutingPolicyDeploymentService.PromotionEvidence(
                            recommendation.temporalHoldoutPassed(),
                            recommendation.canarySamples(),
                            recommendation.qualityGuardState()
                                    == RoutingPolicyQualityGuard.GuardState.HEALTHY,
                            recommendation.offPolicyHealthy()
                    )
            );
            ProgressiveDeliveryAutomationState.ExecutionAudit audit = audit(
                    trigger,
                    source,
                    deployed.current(),
                    ProgressiveDeliveryAutomationState.ExecutionOutcome.APPLIED,
                    "自动晋级成功"
            );
            state = saveAudit(currentState, audit, "自动晋级成功");
            log.info(
                    "渐进式自动发布成功 source={}, target={}, rate={}, artifact={}",
                    source.version(),
                    deployed.current().mode(),
                    recommendation.recommendedTrafficRate(),
                    artifact.version()
            );
            return new ExecutionResult(
                    true,
                    true,
                    audit,
                    status(),
                    "自动晋级成功"
            );
        } catch (RuntimeException exception) {
            RoutingPolicyDeployment resulting = deploymentService.current();
            ProgressiveDeliveryAutomationState.ExecutionAudit audit = audit(
                    trigger,
                    source,
                    resulting,
                    ProgressiveDeliveryAutomationState.ExecutionOutcome.FAILED,
                    exception.getClass().getSimpleName() + ": "
                            + Objects.toString(exception.getMessage(), "")
            );
            state = saveAudit(currentState, audit, "自动晋级失败");
            log.error("渐进式自动发布失败", exception);
            return new ExecutionResult(
                    true,
                    false,
                    audit,
                    status(
                            recommendation,
                            List.of("自动晋级失败：" + exception.getClass().getSimpleName()),
                            clock.instant()
                    ),
                    audit.reason()
            );
        }
    }

    public synchronized ProgressiveDeliveryAutomationState updateControl(
            Boolean automationEnabled,
            Boolean paused,
            boolean rollbackToShadow,
            String reason) {
        ProgressiveDeliveryAutomationState currentState = requireState();
        boolean nextEnabled = automationEnabled == null
                ? currentState.automationEnabled()
                : automationEnabled;
        boolean nextPaused = paused == null ? currentState.paused() : paused;
        String normalizedReason = normalizeReason(reason, "control updated");
        ProgressiveDeliveryAutomationState next =
                new ProgressiveDeliveryAutomationState(
                        nextEnabled,
                        nextPaused,
                        clock.instant(),
                        normalizedReason,
                        currentState.history()
                );
        if (rollbackToShadow) {
            RoutingPolicyDeployment source = deploymentService.current();
            if (source.mode() == RoutingPolicyMode.CANARY
                    || source.mode() == RoutingPolicyMode.ACTIVE) {
                RoutingPolicyDeploymentState deployed = deploymentService.deploy(
                        RoutingPolicyMode.SHADOW,
                        null,
                        source.policyVersion(),
                        "manual progressive delivery pause: " + normalizedReason,
                        new RoutingPolicyDeploymentService.PromotionEvidence(
                                false, 0, false, false)
                );
                ProgressiveDeliveryAutomationState.ExecutionAudit audit =
                        audit(
                                "management-api",
                                source,
                                deployed.current(),
                                ProgressiveDeliveryAutomationState
                                        .ExecutionOutcome.MANUAL_ROLLBACK,
                                normalizedReason
                        );
                next = withAudit(next, audit);
            }
        }
        state = repository.save(next);
        return state;
    }

    public AutomationStatus status() {
        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport
                recommendation = advisor.recommend();
        ProgressiveDeliveryAutomationState currentState = requireState();
        return status(
                recommendation,
                executionBlockers(currentState, recommendation),
                clock.instant()
        );
    }

    private ExecutionResult emergencyStop(
            ProgressiveDeliveryAutomationState currentState,
            String trigger,
            Instant now) {
        RoutingPolicyDeployment source = deploymentService.current();
        if (source.mode() != RoutingPolicyMode.CANARY
                && source.mode() != RoutingPolicyMode.ACTIVE) {
            String reason = "紧急停止已启用；当前没有学习策略流量需要回退";
            return new ExecutionResult(
                    false,
                    false,
                    null,
                    status(advisor.recommend(), List.of(reason), now),
                    reason
            );
        }
        RoutingPolicyDeploymentState deployed = deploymentService.deploy(
                RoutingPolicyMode.SHADOW,
                null,
                source.policyVersion(),
                "automatic emergency stop rollback",
                new RoutingPolicyDeploymentService.PromotionEvidence(
                        false, 0, false, false)
        );
        ProgressiveDeliveryAutomationState.ExecutionAudit audit = audit(
                trigger,
                source,
                deployed.current(),
                ProgressiveDeliveryAutomationState.ExecutionOutcome
                        .EMERGENCY_ROLLBACK,
                "紧急停止自动回退 SHADOW"
        );
        state = saveAudit(
                currentState,
                audit,
                "紧急停止自动回退 SHADOW"
        );
        log.warn("紧急停止已把路由策略从 {} 回退至 SHADOW", source.mode());
        return new ExecutionResult(
                true,
                true,
                audit,
                status(),
                audit.reason()
        );
    }

    private List<String> executionBlockers(
            ProgressiveDeliveryAutomationState currentState,
            RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport
                    recommendation) {
        List<String> blockers = new ArrayList<>();
        if (!executorConfigured) {
            blockers.add("AUTO APPLY 主开关未启用");
        }
        if (!currentState.automationEnabled()) {
            blockers.add("持久化自动执行开关未启用");
        }
        if (currentState.paused()) {
            blockers.add("自动执行已暂停");
        }
        if (emergencyStop) {
            blockers.add("紧急停止开关已启用");
        }
        if (recommendation.dryRun()) {
            blockers.add("发布顾问仍处于 DRY RUN");
        }
        if (!recommendation.readyToAdvance()) {
            blockers.addAll(recommendation.blockers());
            if (recommendation.blockers().isEmpty()) {
                blockers.add("当前建议尚未达到自动晋级条件");
            }
        }
        return blockers.stream().distinct().toList();
    }

    private AutomationStatus status(
            RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport
                    recommendation,
            List<String> blockers,
            Instant evaluatedAt) {
        ProgressiveDeliveryAutomationState currentState = requireState();
        return new AutomationStatus(
                executorConfigured,
                emergencyStop,
                currentState,
                recommendation,
                blockers.isEmpty(),
                currentState.history().isEmpty()
                        ? null
                        : currentState.history().getFirst(),
                List.copyOf(blockers),
                evaluatedAt,
                blockers.isEmpty()
                        ? "自动执行已就绪，下一次调度可应用建议"
                        : String.join("；", blockers)
        );
    }

    private ProgressiveDeliveryAutomationState saveAudit(
            ProgressiveDeliveryAutomationState currentState,
            ProgressiveDeliveryAutomationState.ExecutionAudit audit,
            String reason) {
        ProgressiveDeliveryAutomationState next =
                new ProgressiveDeliveryAutomationState(
                        currentState.automationEnabled(),
                        currentState.paused(),
                        clock.instant(),
                        reason,
                        currentState.history()
                );
        return repository.save(withAudit(next, audit));
    }

    private ProgressiveDeliveryAutomationState withAudit(
            ProgressiveDeliveryAutomationState currentState,
            ProgressiveDeliveryAutomationState.ExecutionAudit audit) {
        List<ProgressiveDeliveryAutomationState.ExecutionAudit> history =
                new ArrayList<>();
        history.add(audit);
        history.addAll(currentState.history());
        return new ProgressiveDeliveryAutomationState(
                currentState.automationEnabled(),
                currentState.paused(),
                clock.instant(),
                currentState.reason(),
                history.stream().limit(maximumHistory).toList()
        );
    }

    private ProgressiveDeliveryAutomationState.ExecutionAudit audit(
            String trigger,
            RoutingPolicyDeployment source,
            RoutingPolicyDeployment resulting,
            ProgressiveDeliveryAutomationState.ExecutionOutcome outcome,
            String reason) {
        return new ProgressiveDeliveryAutomationState.ExecutionAudit(
                "progressive-" + UUID.randomUUID(),
                normalizeReason(trigger, "unknown"),
                source.version(),
                resulting.version(),
                source.mode(),
                trafficRate(source),
                resulting.mode(),
                trafficRate(resulting),
                resulting.policyVersion(),
                outcome,
                clock.instant(),
                normalizeReason(reason, outcome.name())
        );
    }

    private ProgressiveDeliveryAutomationState initialState() {
        return new ProgressiveDeliveryAutomationState(
                defaultAutomationEnabled,
                false,
                clock.instant(),
                "configured default",
                List.of()
        );
    }

    private ProgressiveDeliveryAutomationState requireState() {
        ProgressiveDeliveryAutomationState current = state;
        if (current == null) {
            synchronized (this) {
                if (state == null) {
                    initialize();
                }
                current = state;
            }
        }
        return current;
    }

    private double trafficRate(RoutingPolicyDeployment deployment) {
        return switch (deployment.mode()) {
            case OFF, SHADOW -> 0;
            case CANARY -> deployment.canaryRate();
            case ACTIVE -> 1;
        };
    }

    private String normalizeReason(String value, String fallback) {
        String normalized = Objects.toString(value, "").trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() <= 500
                ? normalized
                : normalized.substring(0, 500);
    }

    public record AutomationStatus(
            boolean executorConfigured,
            boolean emergencyStop,
            ProgressiveDeliveryAutomationState control,
            RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport
                    recommendation,
            boolean eligibleToExecute,
            ProgressiveDeliveryAutomationState.ExecutionAudit lastExecution,
            List<String> blockers,
            Instant evaluatedAt,
            String reason
    ) {

        public AutomationStatus {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record ExecutionResult(
            boolean attempted,
            boolean applied,
            ProgressiveDeliveryAutomationState.ExecutionAudit audit,
            AutomationStatus status,
            String reason
    ) {
    }
}
