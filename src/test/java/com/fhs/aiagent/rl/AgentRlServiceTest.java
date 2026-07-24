package com.fhs.aiagent.rl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRlServiceTest {

    @Test
    void feedbackUpdatesRewardMetricsAndExport() {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        AgentRewardCalculator calculator = new AgentRewardCalculator();
        AgentRlService service = new AgentRlService(
                repository, calculator, new ObjectMapper().findAndRegisterModules());
        AgentTrajectory trajectory = completedTrajectory();
        trajectory = trajectory.withRewardAndFeedback(calculator.calculate(trajectory), null, null);
        repository.save(trajectory);

        AgentTrajectory updated = service.submitFeedback("trajectory-1", 5, "回答有帮助");

        assertThat(updated.reward().total()).isEqualTo(1.0);
        assertThat(updated.userRating()).isEqualTo(5);
        assertThat(service.metrics().averageUserRating()).isEqualTo(5.0);
        assertThat(service.exportJsonLines(0.9)).contains("\"trajectoryId\":\"trajectory-1\"");
    }

    private AgentTrajectory completedTrajectory() {
        Instant now = Instant.now();
        return new AgentTrajectory(
                "trajectory-1",
                "chat-1",
                "agentic-rag-v1",
                "test-model",
                "问题",
                now,
                now,
                "COMPLETED",
                List.of(
                        step("retrieve", AgentStepType.RETRIEVE,
                                Map.of("newDocumentCount", 2)),
                        step("verify", AgentStepType.VERIFY,
                                Map.of("sufficient", true)),
                        step("review", AgentStepType.REVIEW,
                                Map.of("grounded", true, "revised", false))
                ),
                List.of("doc-1", "doc-2"),
                "答案",
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
