package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.AgentRunCancelledException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.IntStream;

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

    @Test
    void cancelledTerminalStateCannotBeOverwrittenByWorkerStartup() {
        RagAbEvaluationService evaluationService = mock(RagAbEvaluationService.class);
        when(evaluationService.evaluate(
                anyString(),
                anyInt(),
                any(BooleanSupplier.class),
                any(Consumer.class)
        )).thenAnswer(invocation -> {
            BooleanSupplier cancelled = invocation.getArgument(2);
            while (!cancelled.getAsBoolean()) {
                Thread.onSpinWait();
            }
            throw new AgentRunCancelledException("cancelled");
        });
        RagAbEvaluationJobService jobService = new RagAbEvaluationJobService(evaluationService);

        List<RagAbEvaluationJobService.RunSnapshot> cancelled = IntStream.range(0, 50)
                .mapToObj(ignored -> jobService.start(2))
                .map(started -> jobService.cancel(started.runId()))
                .toList();

        assertThat(cancelled)
                .allMatch(snapshot -> "CANCELLED".equals(snapshot.status()))
                .allMatch(snapshot -> snapshot.completedAt() != null);
        assertThat(cancelled)
                .allMatch(snapshot -> "CANCELLED".equals(
                        jobService.get(snapshot.runId()).status()));
        jobService.shutdown();
    }
}
