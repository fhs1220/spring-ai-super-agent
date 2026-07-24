package com.fhs.aiagent.rl;

import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRewardCalculatorTest {

    private final AgentRewardCalculator calculator = new AgentRewardCalculator();

    @Test
    void rewardsTaskCompletionSeparatelyFromEvidenceSufficiency() {
        RewardBreakdown reward = calculator.calculate(trajectory(
                Map.of("grounded", true, "taskCompleted", true, "revised", false)
        ));

        assertThat(reward.convergenceQuality()).isEqualTo(0.25);
        assertThat(reward.taskCompletionQuality()).isEqualTo(1.0);
        assertThat(reward.total()).isEqualTo(0.8375);
    }

    @Test
    void incompleteAnswerDoesNotReceiveTaskCompletionReward() {
        RewardBreakdown reward = calculator.calculate(trajectory(
                Map.of("grounded", true, "taskCompleted", false, "revised", false)
        ));

        assertThat(reward.taskCompletionQuality()).isZero();
        assertThat(reward.total()).isEqualTo(0.6375);
    }

    @Test
    void calculatesMultiAgentCollaborationRewardFromProcessSignals() {
        AgentTrajectory base = trajectory(
                Map.of("grounded", true, "taskCompleted", true, "revised", false));
        List<AgentStep> steps = new ArrayList<>(base.steps());
        steps.add(2, step(
                "specialists",
                AgentStepType.SPECIALIST,
                Map.of(
                        "averageProcessReward", 0.8,
                        "agentCount", 3,
                        "successfulAgents", 2
                )
        ));
        AgentTrajectory multiAgent = new AgentTrajectory(
                base.trajectoryId(),
                base.chatId(),
                "agentic-rag-v4",
                base.model(),
                base.question(),
                base.startedAt(),
                base.completedAt(),
                base.status(),
                List.copyOf(steps),
                base.retrievedDocumentIds(),
                base.finalAnswer(),
                null,
                null,
                null,
                null,
                null
        );

        RewardBreakdown reward = calculator.calculate(multiAgent);

        assertThat(reward.collaborationQuality()).isEqualTo(0.7533);
        assertThat(reward.total()).isLessThan(0.8375).isGreaterThan(0.75);
    }

    private AgentTrajectory trajectory(Map<String, Object> reviewOutput) {
        Instant now = Instant.now();
        return new AgentTrajectory(
                "trajectory-1",
                "chat-1",
                "agentic-rag-v2",
                "test-model",
                "请直接制定一周计划",
                now,
                now,
                "COMPLETED",
                List.of(
                        step("retrieve", AgentStepType.RETRIEVE, Map.of("newDocumentCount", 2)),
                        step("verify", AgentStepType.VERIFY, Map.of("sufficient", false)),
                        step("generate", AgentStepType.GENERATE, Map.of("answerLength", 100)),
                        step("review", AgentStepType.REVIEW, reviewOutput)
                ),
                List.of("doc-1", "doc-2"),
                "星期一到星期日的完整计划",
                null,
                null,
                null,
                null,
                null
        );
    }

    private AgentStep step(String id, AgentStepType type, Map<String, Object> output) {
        return new AgentStep(id, type, Instant.now(), 1, true, Map.of(), output);
    }
}
