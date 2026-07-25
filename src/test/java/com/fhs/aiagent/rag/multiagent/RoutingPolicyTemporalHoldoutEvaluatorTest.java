package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyTemporalHoldoutEvaluatorTest {

    private static final Instant START =
            Instant.parse("2026-07-25T00:00:00Z");

    private final RoutingPolicyTemporalHoldoutEvaluator evaluator =
            new RoutingPolicyTemporalHoldoutEvaluator(
                    0.5,
                    2,
                    0,
                    0,
                    0,
                    0.02,
                    60_000
            );

    @Test
    void splitsByEventTimeAndValidatesOnUnseenNewerTrajectories() {
        List<AgentTrajectory> trajectories = List.of(
                trajectory("train-single-1", 1, single(), 0.55, "HOUSEHOLD"),
                trajectory("train-multi-1", 2, multi(), 0.85, "HOUSEHOLD"),
                trajectory("train-single-2", 3, single(), 0.55, "HOUSEHOLD"),
                trajectory("train-multi-2", 4, multi(), 0.85, "HOUSEHOLD"),
                trajectory("holdout-single-1", 5, single(), 0.50, "HOUSEHOLD"),
                trajectory("holdout-multi-1", 6, multi(), 0.90, "HOUSEHOLD"),
                trajectory("holdout-single-2", 7, single(), 0.50, "HOUSEHOLD"),
                trajectory("holdout-multi-2", 8, multi(), 0.90, "HOUSEHOLD")
        );

        RoutingPolicyTemporalHoldoutEvaluator.TemporalDatasetSplit split =
                evaluator.split(trajectories.reversed());
        RoutingPolicyTemporalHoldoutEvaluator.TemporalValidationOutcome outcome =
                evaluator.evaluate(
                        split,
                        "validation-fingerprint",
                        multiRule(),
                        Map.of("HOUSEHOLD", multiRule())
                );

        assertThat(split.training())
                .extracting(AgentTrajectory::trajectoryId)
                .containsExactly(
                        "train-single-1",
                        "train-multi-1",
                        "train-single-2",
                        "train-multi-2"
                );
        assertThat(split.validation())
                .extracting(AgentTrajectory::trajectoryId)
                .containsExactly(
                        "holdout-single-1",
                        "holdout-multi-1",
                        "holdout-single-2",
                        "holdout-multi-2"
                );
        assertThat(outcome.report().validationPassed()).isTrue();
        assertThat(outcome.report().rules().get("GLOBAL").lowerConfidenceBound())
                .isEqualTo(0.4);
        assertThat(outcome.globalRule().deployable()).isTrue();
        assertThat(outcome.contextualRules()).containsOnlyKeys("HOUSEHOLD");
    }

    @Test
    void rejectsRuleWhenNewerWindowReversesTrainingLift() {
        List<AgentTrajectory> trajectories = List.of(
                trajectory("train-single-1", 1, single(), 0.55, "HOUSEHOLD"),
                trajectory("train-multi-1", 2, multi(), 0.85, "HOUSEHOLD"),
                trajectory("train-single-2", 3, single(), 0.55, "HOUSEHOLD"),
                trajectory("train-multi-2", 4, multi(), 0.85, "HOUSEHOLD"),
                trajectory("holdout-single-1", 5, single(), 0.85, "HOUSEHOLD"),
                trajectory("holdout-multi-1", 6, multi(), 0.45, "HOUSEHOLD"),
                trajectory("holdout-single-2", 7, single(), 0.85, "HOUSEHOLD"),
                trajectory("holdout-multi-2", 8, multi(), 0.45, "HOUSEHOLD")
        );

        RoutingPolicyTemporalHoldoutEvaluator.TemporalValidationOutcome outcome =
                evaluator.evaluate(
                        evaluator.split(trajectories),
                        "validation-fingerprint",
                        multiRule(),
                        Map.of()
                );

        assertThat(outcome.report().validationPassed()).isFalse();
        assertThat(outcome.report().rules().get("GLOBAL").passed()).isFalse();
        assertThat(outcome.report().rules().get("GLOBAL").lowerConfidenceBound())
                .isEqualTo(-0.4);
        assertThat(outcome.globalRule().deployable()).isFalse();
        assertThat(outcome.report().validationFailures().getFirst())
                .contains("95% utility-lift lower bound");
    }

    @Test
    void rejectsUnbalancedValidationEvidence() {
        List<AgentTrajectory> trajectories = List.of(
                trajectory("train-single", 1, single(), 0.55, "HOUSEHOLD"),
                trajectory("train-multi", 2, multi(), 0.85, "HOUSEHOLD"),
                trajectory("holdout-single-1", 3, single(), 0.50, "HOUSEHOLD"),
                trajectory("holdout-single-2", 4, single(), 0.50, "HOUSEHOLD")
        );

        RoutingPolicyTemporalHoldoutEvaluator.TemporalValidationOutcome outcome =
                evaluator.evaluate(
                        evaluator.split(trajectories),
                        "validation-fingerprint",
                        multiRule(),
                        Map.of()
                );

        assertThat(outcome.report().validationPassed()).isFalse();
        assertThat(outcome.report().validationFailures().getFirst())
                .contains("evidence below 2 per mode");
    }

    private RoutingPolicyArtifact.DecisionRule multiRule() {
        RoutingPolicyArtifact.ModeEvaluation single =
                new RoutingPolicyArtifact.ModeEvaluation(
                        2, 2, 2, 0.55, 0, 0, 0.55);
        RoutingPolicyArtifact.ModeEvaluation multi =
                new RoutingPolicyArtifact.ModeEvaluation(
                        2, 2, 2, 0.85, 0, 0, 0.85);
        return new RoutingPolicyArtifact.DecisionRule(
                true,
                AdaptiveMultiAgentOrchestrator.MULTI_MODE,
                0.8,
                2,
                0.3,
                single,
                multi,
                "training lift"
        );
    }

    private String single() {
        return AdaptiveMultiAgentOrchestrator.SINGLE_MODE;
    }

    private String multi() {
        return AdaptiveMultiAgentOrchestrator.MULTI_MODE;
    }

    private AgentTrajectory trajectory(
            String id,
            int sequence,
            String mode,
            double reward,
            String featureBucket) {
        Instant eventTime = START.plusSeconds(sequence);
        AgentStep route = new AgentStep(
                id + "-route",
                AgentStepType.ROUTE,
                eventTime,
                1,
                true,
                Map.of(),
                Map.of("mode", mode, "featureBucket", featureBucket)
        );
        RewardBreakdown breakdown = new RewardBreakdown(
                reward, reward, reward, reward, reward, reward, reward, 0.5);
        AgentRunMetrics telemetry = new AgentRunMetrics(
                "test-model", 1, 0, 0, 0, false, 0, 0, List.of());
        return new AgentTrajectory(
                id,
                "chat-" + id,
                "policy",
                "model",
                "question",
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
}
