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
        assertThat(artifact.version()).startsWith("routing-policy-");
        assertThat(artifact.trainingDataFingerprint()).hasSize(64);
        assertThat(artifact.trainingSampleCount()).isEqualTo(4);
        assertThat(artifact.parentVersion())
                .isEqualTo(RoutingPolicyRegistryService.BASELINE_VERSION);
        assertThat(artifact.offlineEvaluation().recommendedMode())
                .isEqualTo(AdaptiveMultiAgentOrchestrator.MULTI_MODE);
        assertThat(service.latestValidatedCandidate()).isEqualTo(artifact);
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

    private RoutingPolicyRegistryService service(
            RoutingPolicyRegistryRepository repository,
            InMemoryAgentTrajectoryRepository trajectories) {
        return new RoutingPolicyRegistryService(
                repository,
                trajectories,
                "trajectory-utility-global-policy-v1",
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
                "multi-1", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90));
        repository.save(trajectory(
                "multi-2", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90));
        return repository;
    }

    private AgentTrajectory trajectory(String id, String mode, double reward) {
        AgentStep route = new AgentStep(
                id + "-route",
                AgentStepType.ROUTE,
                NOW,
                1,
                true,
                Map.of(),
                Map.of("mode", mode, "featureBucket", "GLOBAL")
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
                NOW,
                NOW,
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
