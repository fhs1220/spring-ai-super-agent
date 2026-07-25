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

class RoutingPolicyQualityGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void keepsCanaryWhenQualityGuardIsHealthy() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        RoutingPolicyDeploymentService deployments = canaryDeployment();
        String version = deployments.current().version();
        saveCohort(trajectories, version, true, 0.90, 0.90, 500, 0.004);
        saveCohort(trajectories, version, false, 0.88, 0.88, 550, 0.005);

        RoutingPolicyQualityGuard.QualityGuardReport report =
                guard(trajectories, deployments).evaluateAndMaybeRollback();

        assertThat(report.state())
                .isEqualTo(RoutingPolicyQualityGuard.GuardState.HEALTHY);
        assertThat(report.healthyForPromotion()).isTrue();
        assertThat(report.canary().sampleCount()).isEqualTo(2);
        assertThat(report.control().sampleCount()).isEqualTo(2);
        assertThat(report.violations()).isEmpty();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.CANARY);
    }

    @Test
    void automaticallyRollsBackCanaryWhenQualityRegresses() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        RoutingPolicyDeploymentService deployments = canaryDeployment();
        String version = deployments.current().version();
        saveCohort(trajectories, version, true, 0.60, 0.60, 2_000, 0.020);
        saveCohort(trajectories, version, false, 0.90, 0.90, 500, 0.005);
        RoutingPolicyQualityGuard guard = guard(trajectories, deployments);

        RoutingPolicyQualityGuard.QualityGuardReport report =
                guard.evaluateAndMaybeRollback();

        assertThat(report.state())
                .isEqualTo(RoutingPolicyQualityGuard.GuardState.ROLLED_BACK);
        assertThat(report.violations())
                .anyMatch(violation -> violation.contains("平均奖励"))
                .anyMatch(violation -> violation.contains("忠实度"))
                .anyMatch(violation -> violation.contains("平均延迟"))
                .anyMatch(violation -> violation.contains("平均成本"));
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThat(deployments.current().reason())
                .startsWith("automatic quality rollback");
        assertThat(deployments.state().history().getFirst().mode())
                .isEqualTo(RoutingPolicyMode.CANARY);
        assertThat(guard.status().canary().sampleCount()).isEqualTo(2);
    }

    @Test
    void waitsForBothCanaryAndControlSamples() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        RoutingPolicyDeploymentService deployments = canaryDeployment();
        String version = deployments.current().version();
        trajectories.save(trajectory(
                "canary-1", version, true, 0.9, 0.9, 500, 0.004));
        trajectories.save(trajectory(
                "control-1", version, false, 0.9, 0.9, 500, 0.004));

        RoutingPolicyQualityGuard.QualityGuardReport report =
                guard(trajectories, deployments).evaluateAndMaybeRollback();

        assertThat(report.state())
                .isEqualTo(RoutingPolicyQualityGuard.GuardState.COLLECTING);
        assertThat(report.healthyForPromotion()).isFalse();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.CANARY);
    }

    private RoutingPolicyDeploymentService canaryDeployment() {
        RoutingPolicyDeploymentService service = new RoutingPolicyDeploymentService(
                new MemoryDeploymentRepository(),
                RoutingPolicyMode.SHADOW,
                0.5,
                2,
                10,
                CLOCK
        );
        service.initialize();
        service.deploy(
                RoutingPolicyMode.CANARY,
                0.5,
                "quality guard test",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 0, false)
        );
        return service;
    }

    private RoutingPolicyQualityGuard guard(
            InMemoryAgentTrajectoryRepository trajectories,
            RoutingPolicyDeploymentService deployments) {
        return new RoutingPolicyQualityGuard(
                trajectories,
                deployments,
                true,
                2,
                100,
                0.05,
                0.05,
                0.05,
                1.5,
                1.5,
                CLOCK
        );
    }

    private void saveCohort(InMemoryAgentTrajectoryRepository repository,
                            String deploymentVersion,
                            boolean canarySelected,
                            double reward,
                            double grounding,
                            long latencyMs,
                            double costCny) {
        String cohort = canarySelected ? "canary" : "control";
        repository.save(trajectory(
                cohort + "-1",
                deploymentVersion,
                canarySelected,
                reward,
                grounding,
                latencyMs,
                costCny
        ));
        repository.save(trajectory(
                cohort + "-2",
                deploymentVersion,
                canarySelected,
                reward,
                grounding,
                latencyMs,
                costCny
        ));
    }

    private AgentTrajectory trajectory(String id,
                                       String deploymentVersion,
                                       boolean canarySelected,
                                       double reward,
                                       double grounding,
                                       long latencyMs,
                                       double costCny) {
        AgentStep route = new AgentStep(
                id + "-route",
                AgentStepType.ROUTE,
                NOW,
                1,
                true,
                Map.of(),
                Map.of(
                        "mode", canarySelected
                                ? AdaptiveMultiAgentOrchestrator.MULTI_MODE
                                : AdaptiveMultiAgentOrchestrator.SINGLE_MODE,
                        "policyRolloutMode", RoutingPolicyMode.CANARY.name(),
                        "policyDeploymentVersion", deploymentVersion,
                        "policyCanarySelected", canarySelected
                )
        );
        AgentStep generation = new AgentStep(
                id + "-generate",
                AgentStepType.GENERATE,
                NOW,
                latencyMs,
                true,
                Map.of(),
                Map.of()
        );
        RewardBreakdown breakdown = new RewardBreakdown(
                reward,
                reward,
                grounding,
                reward,
                reward,
                reward,
                reward,
                0.5
        );
        AgentRunMetrics telemetry = new AgentRunMetrics(
                "test-model",
                1,
                0,
                0,
                0,
                false,
                costCny,
                0,
                List.of()
        );
        return new AgentTrajectory(
                id,
                "chat-" + id,
                "test-policy",
                "test-model",
                "测试问题",
                NOW,
                NOW,
                "COMPLETED",
                List.of(route, generation),
                List.of(),
                "测试答案",
                breakdown,
                null,
                null,
                telemetry,
                null
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
        public RoutingPolicyDeploymentState save(RoutingPolicyDeploymentState state) {
            this.state = state;
            return state;
        }
    }
}
