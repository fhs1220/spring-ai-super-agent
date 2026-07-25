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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyOffPolicyEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ARTIFACT_VERSION = "routing-policy-ope-test";

    @Test
    void reportsReadyWhenBalancedExplorationHasEnoughEffectiveSamples() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        saveMode(trajectories, "single", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.60);
        saveMode(trajectories, "multi", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.90);

        RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport report =
                evaluator(trajectories, artifact()).evaluate();

        assertThat(report.state())
                .isEqualTo(RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.READY);
        assertThat(report.singleActionSamples()).isEqualTo(2);
        assertThat(report.multiActionSamples()).isEqualTo(2);
        assertThat(report.targetMatchedSamples()).isEqualTo(2);
        assertThat(report.effectiveSampleSize()).isEqualTo(2);
        assertThat(report.behaviorAverageReward()).isEqualTo(0.75);
        assertThat(report.estimatedPolicyReward()).isEqualTo(0.90);
        assertThat(report.estimatedRewardLift()).isEqualTo(0.15);
        assertThat(report.rewardLiftLowerConfidenceBound()).isPositive();
        assertThat(report.healthyForPromotion()).isTrue();
    }

    @Test
    void rejectsPromotionWhenSnipsEstimateRegresses() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        saveMode(trajectories, "single", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.90);
        saveMode(trajectories, "multi", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.50);

        RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport report =
                evaluator(trajectories, artifact()).evaluate();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.REGRESSION);
        assertThat(report.healthyForPromotion()).isFalse();
        assertThat(report.blockers()).anyMatch(value -> value.contains("SNIPS"));
    }

    @Test
    void ignoresLegacyTrajectoriesWithoutBehaviorProbability() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        trajectories.save(trajectory(
                "legacy",
                AdaptiveMultiAgentOrchestrator.MULTI_MODE,
                0.9,
                false
        ));

        RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport report =
                evaluator(trajectories, artifact()).evaluate();

        assertThat(report.state()).isEqualTo(
                RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationState.COLLECTING);
        assertThat(report.probabilityLoggedTrajectoryCount()).isZero();
        assertThat(report.healthyForPromotion()).isFalse();
    }

    private RoutingPolicyOffPolicyEvaluator evaluator(
            InMemoryAgentTrajectoryRepository trajectories,
            RoutingPolicyArtifact artifact) {
        MemoryRegistryRepository registryRepository = new MemoryRegistryRepository();
        registryRepository.save(new RoutingPolicyRegistryState(
                List.of(artifact),
                NOW
        ));
        RoutingPolicyRegistryService registry = new RoutingPolicyRegistryService(
                registryRepository,
                trajectories,
                "trajectory-utility-contextual-policy-v2",
                "qwen-test",
                Map.of(),
                0.03,
                10,
                CLOCK
        );
        registry.initialize();
        RoutingPolicyDeploymentService deployments = new RoutingPolicyDeploymentService(
                new MemoryDeploymentRepository(),
                RoutingPolicyMode.SHADOW,
                0.5,
                2,
                10,
                CLOCK
        );
        deployments.initialize();
        deployments.deploy(
                RoutingPolicyMode.CANARY,
                0.5,
                artifact.version(),
                "ope test",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 0, false)
        );
        return new RoutingPolicyOffPolicyEvaluator(
                trajectories,
                registry,
                deployments,
                2,
                2,
                20,
                0.03,
                CLOCK
        );
    }

    private RoutingPolicyArtifact artifact() {
        RoutingPolicyArtifact.ModeEvaluation empty =
                RoutingPolicyArtifact.ModeEvaluation.empty();
        RoutingPolicyArtifact.DecisionRule global =
                new RoutingPolicyArtifact.DecisionRule(
                        true,
                        AdaptiveMultiAgentOrchestrator.MULTI_MODE,
                        0.8,
                        4,
                        0.2,
                        empty,
                        empty,
                        "test global rule"
                );
        return new RoutingPolicyArtifact(
                2,
                ARTIFACT_VERSION,
                RoutingPolicyArtifactStatus.VALIDATED,
                "test",
                "qwen-test",
                Map.of(),
                "fingerprint",
                4,
                null,
                global,
                Map.of(),
                RoutingPolicyRegistryService.BASELINE_VERSION,
                NOW,
                "validated"
        );
    }

    private void saveMode(InMemoryAgentTrajectoryRepository repository,
                          String prefix,
                          String mode,
                          double reward) {
        repository.save(trajectory(prefix + "-1", mode, reward, true));
        repository.save(trajectory(prefix + "-2", mode, reward, true));
    }

    private AgentTrajectory trajectory(
            String id,
            String mode,
            double reward,
            boolean probabilityLogged) {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("mode", mode);
        output.put("featureBucket", "GLOBAL");
        output.put("policyArtifactVersion", ARTIFACT_VERSION);
        output.put("policyExplorationEligible", true);
        if (probabilityLogged) {
            output.put("policyBehaviorActionProbability", 0.5);
        }
        AgentStep route = new AgentStep(
                id + "-route",
                AgentStepType.ROUTE,
                NOW,
                1,
                true,
                Map.of(),
                Map.copyOf(output)
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
                "question",
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

    private static final class MemoryDeploymentRepository
            implements RoutingPolicyDeploymentRepository {

        private RoutingPolicyDeploymentState state;

        @Override
        public Optional<RoutingPolicyDeploymentState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public RoutingPolicyDeploymentState save(RoutingPolicyDeploymentState state) {
            this.state = state;
            return state;
        }
    }
}
