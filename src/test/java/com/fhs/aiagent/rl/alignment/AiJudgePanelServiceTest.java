package com.fhs.aiagent.rl.alignment;

import com.fhs.aiagent.rl.InMemoryAgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AiJudgePanelServiceTest {

    @Test
    void automaticallyRunsAllJudgesAndPersistsAssessment() {
        InMemoryAgentTrajectoryRepository trajectories =
                new InMemoryAgentTrajectoryRepository();
        trajectories.save(trajectory());
        InMemoryAlignmentAssessmentRepository assessments =
                new InMemoryAlignmentAssessmentRepository();
        AtomicInteger calls = new AtomicInteger();
        AiJudgeClient judgeClient = (trajectory, dimension) -> {
            calls.incrementAndGet();
            return new AiJudgeScore(
                    "fake-" + dimension,
                    dimension,
                    0.9,
                    0.95,
                    "通过"
            );
        };
        AiJudgePanelService service = new AiJudgePanelService(
                trajectories,
                assessments,
                judgeClient,
                new AutomatedAlignmentEvaluator(4, 0.7, 0.72, 0.75, 0.35, 0.7)
        );

        AutomatedAlignmentAssessment result = service.assess("trajectory-1");

        assertThat(calls).hasValue(4);
        assertThat(result.approvedPositive()).isTrue();
        assertThat(assessments.findByTrajectoryId("trajectory-1")).contains(result);
        assertThat(service.metrics().autoApprovalRate()).isEqualTo(1.0);
    }

    private AgentTrajectory trajectory() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new AgentTrajectory(
                "trajectory-1",
                "chat-1",
                "agentic-rag-v5",
                "qwen-plus",
                "问题",
                now,
                now,
                "COMPLETED",
                List.of(),
                List.of("doc-1"),
                "具体且忠实的答案",
                new RewardBreakdown(0.9, 1, 1, 1, 1, 1, 1, 0.5),
                null,
                null,
                null,
                null
        );
    }
}
