package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.AgentRunCancelledException;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagAbEvaluationJobServiceTest {

    @Test
    void cancellationImmediatelyReachesTerminalState() {
        RagAbEvaluationService evaluationService = mock(RagAbEvaluationService.class);
        when(evaluationService.evaluate(
                anyString(),
                anyInt(),
                any(BooleanSupplier.class),
                any(Consumer.class)
        )).thenAnswer(invocation -> {
            try {
                Thread.sleep(5_000);
                return null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AgentRunCancelledException("cancelled", exception);
            }
        });
        RagAbEvaluationJobService jobService = new RagAbEvaluationJobService(evaluationService);

        RagAbEvaluationJobService.RunSnapshot started = jobService.start(2);
        RagAbEvaluationJobService.RunSnapshot cancelled = jobService.cancel(started.runId());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.completedAt()).isNotNull();
        jobService.shutdown();
    }
}
