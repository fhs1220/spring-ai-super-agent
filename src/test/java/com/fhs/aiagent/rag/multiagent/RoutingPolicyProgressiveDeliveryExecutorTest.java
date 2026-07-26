package com.fhs.aiagent.rag.multiagent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingPolicyProgressiveDeliveryExecutorTest {

    private static final Instant NOW =
            Instant.parse("2026-07-26T00:00:00Z");

    private static final String ARTIFACT_VERSION = "artifact-temporal-v3";

    private final RoutingPolicyRegistryService registry =
            mock(RoutingPolicyRegistryService.class);

    private final RoutingPolicyProgressiveDeliveryAdvisor advisor =
            mock(RoutingPolicyProgressiveDeliveryAdvisor.class);

    @Test
    void safeDefaultsNeverMutateTraffic() {
        RoutingPolicyDeploymentService deployments = deployments();
        when(advisor.recommend()).thenReturn(report(
                deployments.current(), true, false));
        RoutingPolicyProgressiveDeliveryExecutor executor = executor(
                deployments, false, false, false);
        executor.initialize();

        RoutingPolicyProgressiveDeliveryExecutor.ExecutionResult result =
                executor.runOnce("test");

        assertThat(result.attempted()).isFalse();
        assertThat(result.applied()).isFalse();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThat(result.status().blockers())
                .contains("AUTO APPLY 主开关未启用", "持久化自动执行开关未启用");
        verify(registry, never()).requireDeployable(ARTIFACT_VERSION);
    }

    @Test
    void appliesFirstCanaryStageOnlyWhenEveryGateIsEnabled() {
        RoutingPolicyDeploymentService deployments = deployments();
        when(advisor.recommend()).thenReturn(report(
                deployments.current(), true, false));
        when(registry.requireDeployable(ARTIFACT_VERSION))
                .thenReturn(artifact());
        RoutingPolicyProgressiveDeliveryExecutor executor = executor(
                deployments, true, false, true);
        executor.initialize();

        RoutingPolicyProgressiveDeliveryExecutor.ExecutionResult result =
                executor.runOnce("scheduled");

        assertThat(result.attempted()).isTrue();
        assertThat(result.applied()).isTrue();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.CANARY);
        assertThat(deployments.current().canaryRate()).isEqualTo(0.05);
        assertThat(deployments.current().policyVersion())
                .isEqualTo(ARTIFACT_VERSION);
        assertThat(result.audit().outcome()).isEqualTo(
                ProgressiveDeliveryAutomationState.ExecutionOutcome.APPLIED);
        assertThat(executor.status().control().history()).hasSize(1);
    }

    @Test
    void staleRecommendationCannotOverwriteAChangedDeployment() {
        RoutingPolicyDeploymentService deployments = deployments();
        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport stale =
                report(deployments.current(), true, false);
        deployments.deploy(
                RoutingPolicyMode.SHADOW,
                null,
                "manual change",
                new RoutingPolicyDeploymentService.PromotionEvidence(
                        false, 0, false, false)
        );
        String currentVersion = deployments.current().version();
        when(advisor.recommend()).thenReturn(stale);
        RoutingPolicyProgressiveDeliveryExecutor executor = executor(
                deployments, true, false, true);
        executor.initialize();

        RoutingPolicyProgressiveDeliveryExecutor.ExecutionResult result =
                executor.runOnce("scheduled");

        assertThat(result.attempted()).isFalse();
        assertThat(result.reason()).contains("部署版本已变化");
        assertThat(deployments.current().version()).isEqualTo(currentVersion);
        verify(registry, never()).requireDeployable(ARTIFACT_VERSION);
    }

    @Test
    void pausedControlBlocksAnOtherwiseReadyPromotion() {
        RoutingPolicyDeploymentService deployments = deployments();
        when(advisor.recommend()).thenReturn(report(
                deployments.current(), true, false));
        RoutingPolicyProgressiveDeliveryExecutor executor = executor(
                deployments, true, false, true);
        executor.initialize();
        executor.updateControl(null, true, false, "operator pause");

        RoutingPolicyProgressiveDeliveryExecutor.ExecutionResult result =
                executor.runOnce("scheduled");

        assertThat(result.applied()).isFalse();
        assertThat(result.status().blockers()).contains("自动执行已暂停");
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
    }

    @Test
    void emergencyStopRollsCanaryBackToShadowAndAuditsIt() {
        RoutingPolicyDeploymentService deployments = deployments();
        deployCanary(deployments);
        when(advisor.recommend()).thenReturn(report(
                deployments.current(), false, false));
        RoutingPolicyProgressiveDeliveryExecutor executor = executor(
                deployments, false, true, false);
        executor.initialize();

        RoutingPolicyProgressiveDeliveryExecutor.ExecutionResult result =
                executor.runOnce("scheduled");

        assertThat(result.applied()).isTrue();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThat(result.audit().outcome()).isEqualTo(
                ProgressiveDeliveryAutomationState.ExecutionOutcome
                        .EMERGENCY_ROLLBACK);
        assertThat(result.status().eligibleToExecute()).isFalse();
        assertThat(result.status().blockers())
                .contains("紧急停止开关已启用");
    }

    @Test
    void manualPauseCanRollbackAndPersistTheControlState() {
        RoutingPolicyDeploymentService deployments = deployments();
        deployCanary(deployments);
        when(advisor.recommend()).thenReturn(report(
                deployments.current(), false, true));
        RoutingPolicyProgressiveDeliveryExecutor executor = executor(
                deployments, true, false, true);
        executor.initialize();

        ProgressiveDeliveryAutomationState state = executor.updateControl(
                null, true, true, "incident");

        assertThat(state.paused()).isTrue();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThat(state.history().getFirst().outcome()).isEqualTo(
                ProgressiveDeliveryAutomationState.ExecutionOutcome
                        .MANUAL_ROLLBACK);
    }

    private RoutingPolicyProgressiveDeliveryExecutor executor(
            RoutingPolicyDeploymentService deployments,
            boolean executorConfigured,
            boolean emergencyStop,
            boolean defaultEnabled) {
        return new RoutingPolicyProgressiveDeliveryExecutor(
                deployments,
                registry,
                advisor,
                new MemoryAutomationRepository(),
                executorConfigured,
                emergencyStop,
                defaultEnabled,
                100,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private RoutingPolicyDeploymentService deployments() {
        RoutingPolicyDeploymentService service =
                new RoutingPolicyDeploymentService(
                        new MemoryDeploymentRepository(),
                        RoutingPolicyMode.SHADOW,
                        0.1,
                        20,
                        20,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
        service.initialize();
        return service;
    }

    private void deployCanary(RoutingPolicyDeploymentService deployments) {
        deployments.deploy(
                RoutingPolicyMode.CANARY,
                0.05,
                ARTIFACT_VERSION,
                "test canary",
                new RoutingPolicyDeploymentService.PromotionEvidence(
                        true, 0, false, false)
        );
    }

    private RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport
            report(
                    RoutingPolicyDeployment deployment,
                    boolean ready,
                    boolean dryRun) {
        return new RoutingPolicyProgressiveDeliveryAdvisor
                .ProgressiveDeliveryReport(
                true,
                dryRun,
                ready
                        ? RoutingPolicyProgressiveDeliveryAdvisor
                                .ProgressiveDeliveryState.READY
                        : RoutingPolicyProgressiveDeliveryAdvisor
                                .ProgressiveDeliveryState.HOLD,
                deployment.version(),
                deployment.mode(),
                deployment.mode() == RoutingPolicyMode.CANARY
                        ? deployment.canaryRate()
                        : 0,
                deployment.mode() == RoutingPolicyMode.CANARY ? 0 : -1,
                List.of(0.05, 0.10, 0.25, 0.50),
                RoutingPolicyMode.CANARY,
                0.05,
                0,
                ARTIFACT_VERSION,
                true,
                true,
                NOW,
                RoutingPolicyQualityGuard.GuardState.HEALTHY,
                30,
                30,
                20,
                20,
                RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.READY,
                true,
                ready,
                ready ? List.of() : List.of("not ready"),
                NOW,
                ready ? "ready" : "hold"
        );
    }

    private RoutingPolicyArtifact artifact() {
        RoutingPolicyArtifact.TemporalHoldoutEvaluation holdout =
                new RoutingPolicyArtifact.TemporalHoldoutEvaluation(
                        true,
                        0.25,
                        NOW.minusSeconds(3600),
                        "validation-fingerprint",
                        8,
                        4,
                        0.95,
                        0,
                        Map.of(),
                        true,
                        List.of()
                );
        return new RoutingPolicyArtifact(
                3,
                ARTIFACT_VERSION,
                RoutingPolicyArtifactStatus.VALIDATED,
                "trajectory-utility-contextual-policy-v3",
                "qwen-test",
                Map.of(),
                "training-fingerprint",
                24,
                holdout,
                null,
                null,
                Map.of(),
                RoutingPolicyRegistryService.BASELINE_VERSION,
                NOW.minusSeconds(3600),
                "validated"
        );
    }

    private static final class MemoryAutomationRepository
            implements ProgressiveDeliveryAutomationRepository {

        private ProgressiveDeliveryAutomationState state;

        @Override
        public Optional<ProgressiveDeliveryAutomationState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public ProgressiveDeliveryAutomationState save(
                ProgressiveDeliveryAutomationState state) {
            this.state = state;
            return state;
        }
    }

    private static final class MemoryDeploymentRepository
            implements RoutingPolicyDeploymentRepository {

        private RoutingPolicyDeploymentState state;

        @Override
        public Optional<RoutingPolicyDeploymentState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public RoutingPolicyDeploymentState save(
                RoutingPolicyDeploymentState state) {
            this.state = state;
            return state;
        }
    }
}
