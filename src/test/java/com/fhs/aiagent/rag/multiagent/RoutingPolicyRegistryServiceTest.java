package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.InMemoryAgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingPolicyRegistryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void registersContentAddressedValidatedArtifactOnlyOnce() {
        InMemoryAgentTrajectoryRepository trajectories = trajectories();
        MemoryRegistryRepository repository = new MemoryRegistryRepository();
        RoutingPolicyRegistryService service = service(repository, trajectories);
        service.initialize();

        RoutingPolicyRegistryState first = service.reconcileNow(status(0.60, 0.90));
        RoutingPolicyRegistryState duplicate = service.reconcileNow(status(0.60, 0.90));

        assertThat(first.artifacts()).hasSize(2);
        assertThat(duplicate.artifacts()).hasSize(2);
        RoutingPolicyArtifact artifact = first.artifacts().getFirst();
        assertThat(artifact.status()).isEqualTo(RoutingPolicyArtifactStatus.VALIDATED);
        assertThat(artifact.schemaVersion()).isEqualTo(3);
        assertThat(artifact.version()).startsWith("routing-policy-");
        assertThat(artifact.trainingDataFingerprint()).hasSize(64);
        assertThat(artifact.trainingSampleCount()).isEqualTo(4);
        assertThat(artifact.temporalHoldout().validationSampleCount()).isEqualTo(4);
        assertThat(artifact.temporalHoldout().validationPassed()).isTrue();
        assertThat(artifact.temporalHoldout().rules().get("GLOBAL").passed()).isTrue();
        assertThat(artifact.parentVersion())
                .isEqualTo(RoutingPolicyRegistryService.BASELINE_VERSION);
        assertThat(artifact.offlineEvaluation().recommendedMode())
                .isEqualTo(AdaptiveMultiAgentOrchestrator.MULTI_MODE);
        assertThat(artifact.globalRule().deployable()).isTrue();
        assertThat(artifact.globalRule().recommendedMode())
                .isEqualTo(AdaptiveMultiAgentOrchestrator.MULTI_MODE);
        assertThat(artifact.contextualRules()).isEmpty();
        assertThat(service.latestValidatedCandidate()).isEqualTo(artifact);
    }

    @Test
    void freezesOnlyDeployableContextualRules() {
        InMemoryAgentTrajectoryRepository trajectories = trajectories();
        RoutingPolicyRegistryService service = service(
                new MemoryRegistryRepository(), trajectories);
        service.initialize();
        TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status =
                status(0.75, 0.76);
        TrajectoryAwareRoutingPolicy.ModeStats single = status.singleAgent();
        TrajectoryAwareRoutingPolicy.ModeStats multi =
                new TrajectoryAwareRoutingPolicy.ModeStats(
                        2, 2, 2, 0.90, 0.003, 800, 0.90);
        TrajectoryAwareRoutingPolicy.LearnedRule contextual =
                new TrajectoryAwareRoutingPolicy.LearnedRule(
                        true,
                        true,
                        true,
                        0.88,
                        2,
                        0.15,
                        single,
                        multi,
                        "contextual evidence"
                );
        TrajectoryAwareRoutingPolicy.LearnedRule insufficient =
                new TrajectoryAwareRoutingPolicy.LearnedRule(
                        false,
                        false,
                        false,
                        0,
                        1,
                        -0.20,
                        single,
                        multi,
                        "insufficient evidence"
                );
        TrajectoryAwareRoutingPolicy.LearnedPolicySnapshot snapshot =
                new TrajectoryAwareRoutingPolicy.LearnedPolicySnapshot(
                        new TrajectoryAwareRoutingPolicy.LearnedRule(
                                true,
                                false,
                                false,
                                0.1,
                                2,
                                0.01,
                                single,
                                status.multiAgent(),
                                "global tie"
                        ),
                        Map.of(
                                "HOUSEHOLD", contextual,
                                "LOW_EVIDENCE", insufficient
                        ),
                        4,
                        2,
                        NOW
                );

        RoutingPolicyArtifact artifact = service.reconcileNow(status, snapshot)
                .artifacts().getFirst();

        assertThat(artifact.status()).isEqualTo(RoutingPolicyArtifactStatus.VALIDATED);
        assertThat(artifact.globalRule().deployable()).isFalse();
        assertThat(artifact.contextualRules()).containsOnlyKeys("HOUSEHOLD");
        assertThat(artifact.contextualRules().get("HOUSEHOLD").confidence())
                .isEqualTo(0.88);
        assertThat(artifact.offlineEvaluation().recommendedMode())
                .isEqualTo("DETERMINISTIC");

        TrajectoryAwareRoutingPolicy.LearnedRule changedRule =
                new TrajectoryAwareRoutingPolicy.LearnedRule(
                        true,
                        true,
                        true,
                        0.89,
                        2,
                        0.15,
                        single,
                        multi,
                        "contextual evidence"
                );
        RoutingPolicyArtifact changed = service.reconcileNow(
                status,
                new TrajectoryAwareRoutingPolicy.LearnedPolicySnapshot(
                        snapshot.global(),
                        Map.of("HOUSEHOLD", changedRule),
                        4,
                        2,
                        NOW
                )
        ).artifacts().getFirst();
        assertThat(changed.version()).isNotEqualTo(artifact.version());
    }

    @Test
    void recordsRejectedArtifactWhenUtilityLiftIsTooSmall() {
        InMemoryAgentTrajectoryRepository trajectories = trajectories();
        RoutingPolicyRegistryService service = service(
                new MemoryRegistryRepository(), trajectories);
        service.initialize();

        RoutingPolicyRegistryState state = service.reconcileNow(status(0.80, 0.81));

        assertThat(state.artifacts().getFirst().status())
                .isEqualTo(RoutingPolicyArtifactStatus.REJECTED);
        assertThat(state.artifacts().getFirst().validationReason())
                .contains("utility lift");
        assertThatThrownBy(service::latestValidatedCandidate)
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void normalizesPersistedBuiltInBaselineToSchemaV3() {
        MemoryRegistryRepository repository = new MemoryRegistryRepository();
        repository.save(new RoutingPolicyRegistryState(
                List.of(new RoutingPolicyArtifact(
                        1,
                        RoutingPolicyRegistryService.BASELINE_VERSION,
                        RoutingPolicyArtifactStatus.BASELINE,
                        "deterministic-complexity-router-v1",
                        "qwen-test",
                        Map.of(),
                        "baseline",
                        0,
                        RoutingPolicyArtifact.OfflineEvaluation.empty(),
                        null,
                        Map.of(),
                        "",
                        NOW,
                        "legacy baseline"
                )),
                NOW
        ));
        RoutingPolicyRegistryService service = service(
                repository, new InMemoryAgentTrajectoryRepository());

        service.initialize();

        assertThat(service.state().artifacts().getFirst().schemaVersion()).isEqualTo(3);
        assertThat(service.state().artifacts().getFirst().status())
                .isEqualTo(RoutingPolicyArtifactStatus.BASELINE);
    }

    @Test
    void refusesLegacyLearnedArtifactWithoutTemporalHoldout() {
        MemoryRegistryRepository repository = new MemoryRegistryRepository();
        RoutingPolicyArtifact legacy = new RoutingPolicyArtifact(
                2,
                "routing-policy-legacy",
                RoutingPolicyArtifactStatus.VALIDATED,
                "trajectory-utility-contextual-policy-v2",
                "qwen-test",
                Map.of(),
                "legacy-fingerprint",
                20,
                new RoutingPolicyArtifact.OfflineEvaluation(
                        true,
                        20,
                        2,
                        new RoutingPolicyArtifact.ModeEvaluation(
                                10, 10, 10, 0.6, 0, 0, 0.6),
                        new RoutingPolicyArtifact.ModeEvaluation(
                                10, 10, 10, 0.9, 0, 0, 0.9),
                        0.3,
                        AdaptiveMultiAgentOrchestrator.MULTI_MODE,
                        true,
                        List.of()
                ),
                new RoutingPolicyArtifact.DecisionRule(
                        true,
                        AdaptiveMultiAgentOrchestrator.MULTI_MODE,
                        0.9,
                        10,
                        0.3,
                        null,
                        null,
                        "legacy"
                ),
                Map.of(),
                RoutingPolicyRegistryService.BASELINE_VERSION,
                NOW,
                "legacy validated"
        );
        repository.save(new RoutingPolicyRegistryState(
                List.of(legacy),
                NOW
        ));
        RoutingPolicyRegistryService service = service(
                repository, new InMemoryAgentTrajectoryRepository());
        service.initialize();

        assertThatThrownBy(() -> service.requireDeployable(legacy.version()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("temporal holdout");
        assertThatThrownBy(service::latestValidatedCandidate)
                .isInstanceOf(NoSuchElementException.class);
    }

    private RoutingPolicyRegistryService service(
            RoutingPolicyRegistryRepository repository,
            InMemoryAgentTrajectoryRepository trajectories) {
        return new RoutingPolicyRegistryService(
                repository,
                trajectories,
                "trajectory-utility-contextual-policy-v2",
                "qwen-test",
                Map.of("minimumUtilityLift", "0.03"),
                0.03,
                10,
                CLOCK
        );
    }

    private TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status(
            double singleUtility,
            double multiUtility) {
        TrajectoryAwareRoutingPolicy.ModeStats single =
                new TrajectoryAwareRoutingPolicy.ModeStats(
                        2, 2, 2, singleUtility, 0.001, 500, singleUtility);
        TrajectoryAwareRoutingPolicy.ModeStats multi =
                new TrajectoryAwareRoutingPolicy.ModeStats(
                        2, 2, 2, multiUtility, 0.003, 800, multiUtility);
        return new TrajectoryAwareRoutingPolicy.RoutingPolicyStatus(
                true,
                true,
                new RoutingPolicyDeployment(
                        "routing-test",
                        RoutingPolicyRegistryService.BASELINE_VERSION,
                        RoutingPolicyMode.SHADOW,
                        0.1,
                        NOW,
                        "test"
                ),
                2,
                4,
                single,
                multi,
                1,
                0,
                2,
                NOW,
                "ready"
        );
    }

    private InMemoryAgentTrajectoryRepository trajectories() {
        InMemoryAgentTrajectoryRepository repository =
                new InMemoryAgentTrajectoryRepository();
        repository.save(trajectory(
                "single-1", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.60));
        repository.save(trajectory(
                "single-2", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.60));
        repository.save(trajectory(
                "single-3", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.60));
        repository.save(trajectory(
                "single-4", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.60));
        repository.save(trajectory(
                "multi-1", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90));
        repository.save(trajectory(
                "multi-2", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90));
        repository.save(trajectory(
                "multi-3", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90));
        repository.save(trajectory(
                "multi-4", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90));
        return repository;
    }

    private AgentTrajectory trajectory(String id, String mode, double reward) {
        int sequence = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
        Instant eventTime = NOW.plusSeconds(sequence);
        AgentStep route = new AgentStep(
                id + "-route",
                AgentStepType.ROUTE,
                eventTime,
                1,
                true,
                Map.of(),
                Map.of("mode", mode, "featureBucket", "HOUSEHOLD")
        );
        RewardBreakdown breakdown = new RewardBreakdown(
                reward, reward, reward, reward, reward, reward, reward, 0.5);
        AgentRunMetrics telemetry = new AgentRunMetrics(
                "test-model", 1, 0, 0, 0, false, 0.001, 0, List.of());
        return new AgentTrajectory(
                id,
                "chat-" + id,
                "policy",
                "model",
                "sensitive question that must not enter the registry",
                eventTime,
                eventTime,
                "COMPLETED",
                List.of(route),
                List.of(),
                "answer",
                breakdown,
                null,
                null,
                telemetry,
                null
        );
    }

    private static final class MemoryRegistryRepository
            implements RoutingPolicyRegistryRepository {

        private RoutingPolicyRegistryState state;

        @Override
        public Optional<RoutingPolicyRegistryState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public RoutingPolicyRegistryState save(RoutingPolicyRegistryState state) {
            this.state = state;
            return state;
        }
    }
}
