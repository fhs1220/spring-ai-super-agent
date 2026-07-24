package com.fhs.aiagent.rl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileAgentTrajectoryRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsTrajectory() {
        FileAgentTrajectoryRepository repository = new FileAgentTrajectoryRepository(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        Instant now = Instant.now();
        AgentTrajectory trajectory = new AgentTrajectory(
                "trajectory-1",
                "chat-1",
                "agentic-rag-v1",
                "test-model",
                "问题",
                now,
                now,
                "COMPLETED",
                List.of(new AgentStep(
                        "step-1", AgentStepType.PLAN, now, 10, true,
                        Map.of("question", "问题"), Map.of("subQueries", List.of("查询")))),
                List.of("doc-1"),
                "答案",
                null,
                null,
                null,
                null,
                null
        );

        repository.save(trajectory);

        assertThat(repository.findById("trajectory-1")).contains(trajectory);
        assertThat(repository.findAll()).containsExactly(trajectory);
    }
}
