package com.fhs.aiagent.rag.run;

import com.fhs.aiagent.rag.AgentProgressEvent;
import com.fhs.aiagent.rag.AnswerVerificationContract;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableAgentRunServiceTest {

    @Test
    void completesAndReplaysSameRunWithoutCreatingAnotherAttempt() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableAgentRunService service = new DurableAgentRunService(repository, 50);
        service.createOrReplay("durable-run-001", "问题", "chat-1");
        service.markRunning("durable-run-001");
        service.appendProgress("durable-run-001", progress("PLAN"));
        AgenticRagResult result = new AgenticRagResult("答案", "trajectory-1", null, null);

        service.complete("durable-run-001", result);
        DurableAgentRun replay = service.createOrReplay(
                "durable-run-001", "问题", "chat-1");

        assertThat(replay.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(replay.result()).isEqualTo(result);
        assertThat(replay.attempt()).isEqualTo(1);
        assertThat(replay.events()).hasSize(1);
        assertThat(service.cancel(replay.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThatThrownBy(() -> service.createOrReplay(
                "durable-run-001", "另一个问题", "chat-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void resumesFailedRunAsANewExplicitAttempt() {
        DurableAgentRunService service = new DurableAgentRunService(
                new InMemoryRepository(), 50);
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of("兴趣爱好"),
                List.of("推荐课程"),
                140,
                1600,
                true,
                false,
                false,
                3);
        service.createOrReplay(
                "durable-run-002", "问题", "chat-2", contract);
        service.markRunning("durable-run-002");
        service.fail("durable-run-002", "模型超时");

        DurableAgentRun resumed = service.resume("durable-run-002");

        assertThat(resumed.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(resumed.attempt()).isEqualTo(2);
        assertThat(resumed.verificationContract()).isEqualTo(contract);
        assertThat(resumed.error()).isEmpty();
        assertThat(resumed.events())
                .extracting(AgentProgressEvent::status)
                .containsExactly("RETRYING");
        assertThatThrownBy(() -> service.createOrReplay(
                "durable-run-002", "问题", "chat-2", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void marksInterruptedRunsAsRecoveryRequiredOnStartup() {
        InMemoryRepository repository = new InMemoryRepository();
        Instant now = Instant.now();
        repository.save(new DurableAgentRun(
                "durable-run-003",
                "问题",
                "chat-3",
                null,
                AgentRunStatus.RUNNING,
                1,
                List.of(progress("RETRIEVE")),
                null,
                "",
                now,
                now
        ));
        DurableAgentRunService service = new DurableAgentRunService(repository, 50);

        service.recoverInterruptedRuns();

        DurableAgentRun recovered = service.get("durable-run-003");
        assertThat(recovered.status()).isEqualTo(AgentRunStatus.RECOVERY_REQUIRED);
        assertThat(recovered.events())
                .extracting(AgentProgressEvent::status)
                .containsExactly("COMPLETED", "RECOVERY_REQUIRED");
        assertThat(service.resume(recovered.runId()).attempt()).isEqualTo(2);
    }

    private static AgentProgressEvent progress(String phase) {
        return new AgentProgressEvent(
                phase, "COMPLETED", phase, "完成", List.of(), 10, Instant.now());
    }

    private static final class InMemoryRepository implements AgentRunRepository {

        private final Map<String, DurableAgentRun> runs = new LinkedHashMap<>();

        @Override
        public DurableAgentRun save(DurableAgentRun run) {
            runs.put(run.runId(), run);
            return run;
        }

        @Override
        public Optional<DurableAgentRun> findById(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<DurableAgentRun> findAll() {
            return List.copyOf(runs.values());
        }
    }
}
