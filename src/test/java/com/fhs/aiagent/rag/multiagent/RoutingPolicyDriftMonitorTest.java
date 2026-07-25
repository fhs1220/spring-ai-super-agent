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

class RoutingPolicyDriftMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ARTIFACT_VERSION = "routing-policy-drift-test";

    @Test
    void keepsActivePolicyWhenDistributionAndQualityRemainStable() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        RoutingPolicyDeploymentService deployments = activeDeployment();
        String activeVersion = deployments.current().version();
        saveWindow(
                trajectories, "reference", RoutingPolicyMode.CANARY,
                "canary-deployment", "HOUSEHOLD", 0.90, 0.90, 500, 0.004);
        saveWindow(
                trajectories, "active", RoutingPolicyMode.ACTIVE,
                activeVersion, "HOUSEHOLD", 0.88, 0.88, 550, 0.005);

        RoutingPolicyDriftMonitor.DriftReport report =
                monitor(trajectories, deployments, 2).evaluateAndMaybeRollback();

        assertThat(report.state())
                .isEqualTo(RoutingPolicyDriftMonitor.DriftState.HEALTHY);
        assertThat(report.featureJsDivergence()).isZero();
        assertThat(report.reference().sampleCount()).isEqualTo(2);
        assertThat(report.active().sampleCount()).isEqualTo(2);
        assertThat(report.violations()).isEmpty();
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.ACTIVE);
    }

    @Test
    void rollsBackOnlyAfterConsecutiveDriftViolations() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        RoutingPolicyDeploymentService deployments = activeDeployment();
        String activeVersion = deployments.current().version();
        saveWindow(
                trajectories, "reference", RoutingPolicyMode.CANARY,
                "canary-deployment", "HOUSEHOLD", 0.90, 0.90, 500, 0.004);
        saveWindow(
                trajectories, "active", RoutingPolicyMode.ACTIVE,
                activeVersion, "FINANCE", 0.60, 0.60, 2_000, 0.020);
        RoutingPolicyDriftMonitor monitor = monitor(trajectories, deployments, 2);

        RoutingPolicyDriftMonitor.DriftReport first =
                monitor.evaluateAndMaybeRollback();
        assertThat(first.state())
                .isEqualTo(RoutingPolicyDriftMonitor.DriftState.DRIFTED);
        assertThat(first.consecutiveViolationCount()).isEqualTo(1);
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.ACTIVE);

        RoutingPolicyDriftMonitor.DriftReport second =
                monitor.evaluateAndMaybeRollback();

        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThat(second.state())
                .isEqualTo(RoutingPolicyDriftMonitor.DriftState.ROLLED_BACK);
        assertThat(second.featureJsDivergence()).isEqualTo(1);
        assertThat(second.violations())
                .anyMatch(value -> value.contains("JS"))
                .anyMatch(value -> value.contains("平均奖励"))
                .anyMatch(value -> value.contains("平均延迟"));
        assertThat(deployments.current().reason())
                .startsWith("automatic active drift rollback");
        assertThat(monitor.status().state())
                .isEqualTo(RoutingPolicyDriftMonitor.DriftState.ROLLED_BACK);
    }

    @Test
    void waitsForReferenceAndActiveWindows() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        RoutingPolicyDeploymentService deployments = activeDeployment();
        trajectories.save(trajectory(
                "reference-1",
                RoutingPolicyMode.CANARY,
                "canary-deployment",
                "HOUSEHOLD",
                0.9,
                0.9,
                500,
                0.004
        ));
        trajectories.save(trajectory(
                "active-1",
                RoutingPolicyMode.ACTIVE,
                deployments.current().version(),
                "HOUSEHOLD",
                0.9,
                0.9,
                500,
                0.004
        ));

        RoutingPolicyDriftMonitor.DriftReport report =
                monitor(trajectories, deployments, 2).evaluateAndMaybeRollback();

        assertThat(report.state())
                .isEqualTo(RoutingPolicyDriftMonitor.DriftState.COLLECTING);
        assertThat(deployments.current().mode()).isEqualTo(RoutingPolicyMode.ACTIVE);
    }

    private RoutingPolicyDeploymentService activeDeployment() {
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
                ARTIFACT_VERSION,
                "drift canary",
                new RoutingPolicyDeploymentService.PromotionEvidence(
                        true, 0, false, false)
        );
        service.deploy(
                RoutingPolicyMode.ACTIVE,
                null,
                ARTIFACT_VERSION,
                "drift active",
                new RoutingPolicyDeploymentService.PromotionEvidence(
                        true, 2, true, true)
        );
        return service;
    }

    private RoutingPolicyDriftMonitor monitor(
            InMemoryAgentTrajectoryRepository trajectories,
            RoutingPolicyDeploymentService deployments,
            int consecutiveViolationsRequired) {
        return new RoutingPolicyDriftMonitor(
                trajectories,
                deployments,
                true,
                2,
                2,
                100,
                consecutiveViolationsRequired,
                0.20,
                0.05,
                0.05,
                0.05,
                1.5,
                1.5,
                CLOCK
        );
    }

    private void saveWindow(
            InMemoryAgentTrajectoryRepository repository,
            String prefix,
            RoutingPolicyMode rolloutMode,
            String deploymentVersion,
            String featureBucket,
            double reward,
            double grounding,
            long latencyMs,
            double costCny) {
        repository.save(trajectory(
                prefix + "-1",
                rolloutMode,
                deploymentVersion,
                featureBucket,
                reward,
                grounding,
                latencyMs,
                costCny
        ));
        repository.save(trajectory(
                prefix + "-2",
                rolloutMode,
                deploymentVersion,
                featureBucket,
                reward,
                grounding,
                latencyMs,
                costCny
        ));
    }

    private AgentTrajectory trajectory(
            String id,
            RoutingPolicyMode rolloutMode,
            String deploymentVersion,
            String featureBucket,
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
                        "mode", AdaptiveMultiAgentOrchestrator.MULTI_MODE,
                        "featureBucket", featureBucket,
                        "policyRolloutMode", rolloutMode.name(),
                        "policyDeploymentVersion", deploymentVersion,
                        "policyArtifactVersion", ARTIFACT_VERSION,
                        "policyCanarySelected",
                        rolloutMode == RoutingPolicyMode.CANARY
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
                "test-model", 1, 0, 0, 0, false, costCny, 0, List.of());
        return new AgentTrajectory(
                id,
                "chat-" + id,
                "test-policy",
                "test-model",
                "question",
                NOW,
                NOW,
                "COMPLETED",
                List.of(route, generation),
                List.of(),
                "answer",
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
