package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.InMemoryAgentTrajectoryRepository;
import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrajectoryAwareRoutingPolicyTest {

    private static final String BUCKET = "HOUSEHOLD+PARENTING|structured=true|long=false";

    @Test
    void keepsDeterministicDecisionDuringColdStart() {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        repository.save(trajectory("multi-1", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.9, 0.003, 500));
        repository.save(trajectory("multi-2", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.88, 0.003, 500));
        TrajectoryAwareRoutingPolicy policy = policy(repository, 0.05, 0.05);

        TrajectoryAwareRoutingPolicy.RoutingPolicyDecision decision = policy.decide(
                new TrajectoryAwareRoutingPolicy.RoutingContext(BUCKET, false, false));

        assertThat(decision.multiAgent()).isFalse();
        assertThat(decision.source()).isEqualTo("COLD_START");
        assertThat(policy.status().ready()).isFalse();
    }

    @Test
    void overridesStaticRouteWhenMultiAgentHasEnoughNetUtilityEvidence() {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        saveMode(repository, "single", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.68, 0.001, 300);
        saveMode(repository, "multi", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.91, 0.004, 600);
        TrajectoryAwareRoutingPolicy policy = policy(repository, 0.05, 0.05);

        TrajectoryAwareRoutingPolicy.RoutingPolicyDecision decision = policy.decide(
                new TrajectoryAwareRoutingPolicy.RoutingContext(BUCKET, false, false));

        assertThat(decision.multiAgent()).isTrue();
        assertThat(decision.source()).isEqualTo("LEARNED_CONTEXTUAL");
        assertThat(decision.evidenceSamples()).isEqualTo(2);
        assertThat(decision.confidence()).isPositive();
        assertThat(policy.status().ready()).isTrue();
    }

    @Test
    void choosesSingleAgentWhenSmallRewardLiftDoesNotCoverCostAndLatency() {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        saveMode(repository, "single", AdaptiveMultiAgentOrchestrator.SINGLE_MODE, 0.80, 0.001, 1_000);
        saveMode(repository, "multi", AdaptiveMultiAgentOrchestrator.MULTI_MODE, 0.82, 0.02, 60_000);
        TrajectoryAwareRoutingPolicy policy = policy(repository, 0.20, 0.20);

        TrajectoryAwareRoutingPolicy.RoutingPolicyDecision decision = policy.decide(
                new TrajectoryAwareRoutingPolicy.RoutingContext(BUCKET, true, false));

        assertThat(decision.multiAgent()).isFalse();
        assertThat(decision.source()).isEqualTo("LEARNED_CONTEXTUAL");
        assertThat(decision.reason()).contains("单 Agent");
    }

    @Test
    void safetyOverrideCannotBeDisabledByCostPolicy() {
        TrajectoryAwareRoutingPolicy policy = policy(
                new InMemoryAgentTrajectoryRepository(), 0.20, 0.20);

        TrajectoryAwareRoutingPolicy.RoutingPolicyDecision decision = policy.decide(
                new TrajectoryAwareRoutingPolicy.RoutingContext(
                        "SAFETY|structured=false|long=false", false, true));

        assertThat(decision.multiAgent()).isTrue();
        assertThat(decision.source()).isEqualTo("SAFETY_OVERRIDE");
        assertThat(decision.confidence()).isEqualTo(1);
    }

    @Test
    void fallsBackToDeterministicRouteWhenTrajectoryStorageIsUnavailable() {
        AgentTrajectoryRepository repository = mock(AgentTrajectoryRepository.class);
        when(repository.findAll()).thenThrow(new IllegalStateException("storage unavailable"));
        TrajectoryAwareRoutingPolicy policy = policy(repository, 0.05, 0.05);

        TrajectoryAwareRoutingPolicy.RoutingPolicyDecision decision = policy.decide(
                new TrajectoryAwareRoutingPolicy.RoutingContext(BUCKET, true, false));

        assertThat(decision.multiAgent()).isTrue();
        assertThat(decision.source()).isEqualTo("POLICY_FALLBACK");
        assertThat(policy.status().ready()).isFalse();
        assertThat(policy.status().reason()).contains("IllegalStateException");
    }

    private static void saveMode(InMemoryAgentTrajectoryRepository repository,
                                 String prefix,
                                 String mode,
                                 double reward,
                                 double cost,
                                 long latency) {
        repository.save(trajectory(prefix + "-1", mode, reward, cost, latency));
        repository.save(trajectory(prefix + "-2", mode, reward, cost, latency));
    }

    private static TrajectoryAwareRoutingPolicy policy(
            AgentTrajectoryRepository repository,
            double costWeight,
            double latencyWeight) {
        return new TrajectoryAwareRoutingPolicy(
                repository,
                true,
                2,
                100,
                0.03,
                0.72,
                costWeight,
                latencyWeight,
                0.02,
                60_000,
                Duration.ofSeconds(30),
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static AgentTrajectory trajectory(String id,
                                              String mode,
                                              double reward,
                                              double cost,
                                              long latency) {
        Instant now = Instant.parse("2026-07-25T00:00:00Z");
        AgentStep route = new AgentStep(
                id + "-route",
                AgentStepType.ROUTE,
                now,
                1,
                true,
                Map.of(),
                Map.of("mode", mode, "featureBucket", BUCKET)
        );
        AgentStep generation = new AgentStep(
                id + "-generate",
                AgentStepType.GENERATE,
                now,
                latency,
                true,
                Map.of(),
                Map.of()
        );
        RewardBreakdown breakdown = new RewardBreakdown(
                reward, reward, reward, reward, reward, reward, reward, 0.5);
        AgentRunMetrics telemetry = new AgentRunMetrics(
                "test-model", 1, 0, 0, 0, false, cost, 0, List.of());
        return new AgentTrajectory(
                id,
                "chat-" + id,
                "test-policy",
                "test-model",
                "测试问题",
                now,
                now,
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
}
