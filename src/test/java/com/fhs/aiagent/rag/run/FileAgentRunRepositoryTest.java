package com.fhs.aiagent.rag.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.AgentProgressEvent;
import com.fhs.aiagent.rag.AnswerVerificationContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileAgentRunRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsRunSnapshot() {
        FileAgentRunRepository repository = new FileAgentRunRepository(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        Instant now = Instant.now();
        DurableAgentRun run = new DurableAgentRun(
                "durable-run-001",
                "测试问题",
                "chat-1",
                new AnswerVerificationContract(
                        List.of("独立"),
                        List.of("推荐课程"),
                        140,
                        1600,
                        true,
                        false,
                        false,
                        3),
                AgentRunStatus.RUNNING,
                1,
                List.of(new AgentProgressEvent(
                        "PLAN", "COMPLETED", "规划", "完成", List.of("查询 1"), 12, now)),
                null,
                "",
                now,
                now
        );

        repository.save(run);

        assertThat(repository.findById(run.runId())).contains(run);
        assertThat(repository.findAll()).containsExactly(run);
    }
}
