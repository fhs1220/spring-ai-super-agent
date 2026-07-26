package com.fhs.aiagent.rag.multiagent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingPolicyProgressiveDeliveryAdvisorTest {

    private static final Instant NOW =
            Instant.parse("2026-07-26T00:00:00Z");

    private static final String ARTIFACT_VERSION =
            "routing-policy-temporal-v3";

    private final TrajectoryAwareRoutingPolicy routingPolicy =
            mock(TrajectoryAwareRoutingPolicy.class);

    private final RoutingPolicyRegistryService registryService =
            mock(RoutingPolicyRegistryService.class);

    private final RoutingPolicyQualityGuard qualityGuard =
            mock(RoutingPolicyQualityGuard.class);

    private final RoutingPolicyOffPolicyEvaluator offPolicyEvaluator =
            mock(RoutingPolicyOffPolicyEvaluator.class);

    @Test
    void waitsInShadowUntilTemporalArtifactExists() {
        RoutingPolicyDeploymentService deployments = deployments();
        when(registryService.latestValidatedCandidate())
                .thenThrow(new NoSuchElementException("none"));

        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport report =
                advisor(deployments, NOW.plus(Duration.ofHours(1))).recommend();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyProgressiveDeliveryAdvisor
                        .ProgressiveDeliveryState.WAITING_FOR_ARTIFACT);
        assertThat(report.currentMode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThat(report.recommendedMode()).isEqualTo(RoutingPolicyMode.CANARY);
        assertThat(report.recommendedTrafficRate()).isEqualTo(0.05);
        assertThat(report.readyToAdvance()).isFalse();
    }

    @Test
    void recommendsFirstFivePercentStageAfterOfflineGatesAndCooldown() {
        RoutingPolicyDeploymentService deployments = deployments();
        RoutingPolicyArtifact artifact = artifact();
        when(registryService.latestValidatedCandidate()).thenReturn(artifact);
        when(routingPolicy.status()).thenReturn(status(deployments.current(), true));

        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport report =
                advisor(deployments, NOW.plus(Duration.ofHours(1))).recommend();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyProgressiveDeliveryAdvisor
                        .ProgressiveDeliveryState.READY);
        assertThat(report.policyArtifactVersion()).isEqualTo(ARTIFACT_VERSION);
        assertThat(report.temporalHoldoutPassed()).isTrue();
        assertThat(report.recommendedTrafficRate()).isEqualTo(0.05);
        assertThat(report.readyToAdvance()).isTrue();
        assertThat(report.dryRun()).isTrue();
    }

    @Test
    void waitsForFreshCohortEvidenceAtEveryCanaryStage() {
        RoutingPolicyDeploymentService deployments = deployments();
        deployCanary(deployments, 0.10);
        when(registryService.requireDeployable(ARTIFACT_VERSION))
                .thenReturn(artifact());
        when(qualityGuard.status()).thenReturn(guard(
                deployments.current(),
                RoutingPolicyQualityGuard.GuardState.COLLECTING,
                8,
                20
        ));
        when(offPolicyEvaluator.evaluate()).thenReturn(offPolicy(
                RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.COLLECTING,
                false
        ));

        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport report =
                advisor(deployments, NOW.plus(Duration.ofHours(1))).recommend();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyProgressiveDeliveryAdvisor
                        .ProgressiveDeliveryState.COLLECTING);
        assertThat(report.currentTrafficRate()).isEqualTo(0.10);
        assertThat(report.recommendedTrafficRate()).isEqualTo(0.25);
        assertThat(report.canarySamples()).isEqualTo(8);
        assertThat(report.blockers().getFirst())
                .contains("收集灰度/对照样本");
    }

    @Test
    void recommendsActiveOnlyAfterQualityAndOffPolicyGatesPass() {
        RoutingPolicyDeploymentService deployments = deployments();
        deployCanary(deployments, 0.50);
        when(registryService.requireDeployable(ARTIFACT_VERSION))
                .thenReturn(artifact());
        when(qualityGuard.status()).thenReturn(guard(
                deployments.current(),
                RoutingPolicyQualityGuard.GuardState.HEALTHY,
                30,
                30
        ));
        when(offPolicyEvaluator.evaluate()).thenReturn(offPolicy(
                RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.READY,
                true
        ));

        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport report =
                advisor(deployments, NOW.plus(Duration.ofHours(1))).recommend();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyProgressiveDeliveryAdvisor
                        .ProgressiveDeliveryState.READY);
        assertThat(report.recommendedMode()).isEqualTo(RoutingPolicyMode.ACTIVE);
        assertThat(report.recommendedTrafficRate()).isEqualTo(1);
        assertThat(report.offPolicyHealthy()).isTrue();
        assertThat(report.readyToAdvance()).isTrue();
    }

    @Test
    void cooldownBlocksAnOtherwiseHealthyStage() {
        RoutingPolicyDeploymentService deployments = deployments();
        deployCanary(deployments, 0.05);
        when(registryService.requireDeployable(ARTIFACT_VERSION))
                .thenReturn(artifact());
        when(qualityGuard.status()).thenReturn(guard(
                deployments.current(),
                RoutingPolicyQualityGuard.GuardState.HEALTHY,
                30,
                30
        ));
        when(offPolicyEvaluator.evaluate()).thenReturn(offPolicy(
                RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.READY,
                true
        ));

        RoutingPolicyProgressiveDeliveryAdvisor.ProgressiveDeliveryReport report =
                advisor(deployments, NOW.plus(Duration.ofMinutes(10))).recommend();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyProgressiveDeliveryAdvisor
                        .ProgressiveDeliveryState.COOLDOWN);
        assertThat(report.cooldownPassed()).isFalse();
        assertThat(report.cooldownUntil())
                .isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(report.readyToAdvance()).isFalse();
    }

    private RoutingPolicyProgressiveDeliveryAdvisor advisor(
            RoutingPolicyDeploymentService deployments,
            Instant now) {
        return new RoutingPolicyProgressiveDeliveryAdvisor(
                deployments,
                routingPolicy,
                registryService,
                qualityGuard,
                offPolicyEvaluator,
                true,
                true,
                List.of(0.05, 0.10, 0.25, 0.50),
                Duration.ofMinutes(30),
                Clock.fixed(now, ZoneOffset.UTC)
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

    private void deployCanary(
            RoutingPolicyDeploymentService deployments,
            double rate) {
        deployments.deploy(
                RoutingPolicyMode.CANARY,
                rate,
                ARTIFACT_VERSION,
                "test canary",
                new RoutingPolicyDeploymentService.PromotionEvidence(
                        true, 0, false)
        );
    }

    private TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status(
            RoutingPolicyDeployment deployment,
            boolean ready) {
        TrajectoryAwareRoutingPolicy.ModeStats stats =
                new TrajectoryAwareRoutingPolicy.ModeStats(
                        20, 20, 20, 0.8, 0.001, 500, 0.8);
        return new TrajectoryAwareRoutingPolicy.RoutingPolicyStatus(
                true,
                ready,
                deployment,
                8,
                40,
                stats,
                stats,
                2,
                0,
                20,
                NOW,
                ready ? "ready" : "collecting"
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

    private RoutingPolicyQualityGuard.QualityGuardReport guard(
            RoutingPolicyDeployment deployment,
            RoutingPolicyQualityGuard.GuardState state,
            int canarySamples,
            int controlSamples) {
        RoutingPolicyQualityGuard.CohortStats canary =
                new RoutingPolicyQualityGuard.CohortStats(
                        canarySamples,
                        canarySamples,
                        canarySamples,
                        0.8,
                        0.8,
                        1,
                        500,
                        0.001
                );
        RoutingPolicyQualityGuard.CohortStats control =
                new RoutingPolicyQualityGuard.CohortStats(
                        controlSamples,
                        controlSamples,
                        controlSamples,
                        0.8,
                        0.8,
                        1,
                        500,
                        0.001
                );
        return new RoutingPolicyQualityGuard.QualityGuardReport(
                true,
                state,
                deployment.version(),
                RoutingPolicyMode.CANARY,
                20,
                20,
                canary,
                control,
                0.05,
                0.05,
                0.05,
                1.5,
                1.5,
                List.of(),
                NOW,
                state.name()
        );
    }

    private RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport offPolicy(
            RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState state,
            boolean healthy) {
        return new RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport(
                state,
                ARTIFACT_VERSION,
                40,
                40,
                40,
                40,
                20,
                20,
                20,
                20,
                20,
                20,
                0.8,
                0.82,
                0.02,
                0.01,
                0,
                0.03,
                healthy,
                List.of(),
                NOW,
                state.name()
        );
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
