package com.fhs.aiagent.rag;

import com.fhs.aiagent.rl.model.AgentRunMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentTelemetryCollectorTest {

    @Test
    void usesModelReportedTokenUsageAndCalculatesCost() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(20);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("回答"))),
                metadata
        );
        AgentTelemetryCollector collector = new AgentTelemetryCollector("qwen-turbo", 0.3, 0.6);

        String answer = collector.captureContent("GENERATE", "提示词", () -> response);
        AgentRunMetrics metrics = collector.snapshot();

        assertThat(answer).isEqualTo("回答");
        assertThat(metrics.modelCallCount()).isEqualTo(1);
        assertThat(metrics.promptTokens()).isEqualTo(100);
        assertThat(metrics.completionTokens()).isEqualTo(20);
        assertThat(metrics.totalTokens()).isEqualTo(120);
        assertThat(metrics.usageEstimated()).isFalse();
        assertThat(metrics.estimatedCostCny()).isEqualTo(0.000042);
    }

    @Test
    void estimatesUsageWhenProviderMetadataIsMissing() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("这是一个回答"))));
        AgentTelemetryCollector collector = new AgentTelemetryCollector("test-model", 0.3, 0.6);

        collector.captureContent("GENERATE", "这是中文 prompt", () -> response);
        AgentRunMetrics metrics = collector.snapshot();

        assertThat(metrics.totalTokens()).isPositive();
        assertThat(metrics.usageEstimated()).isTrue();
    }

    @Test
    void recordsTimeoutBeforeRethrowingFailure() {
        AgentTelemetryCollector collector = new AgentTelemetryCollector("test-model", 0.3, 0.6);
        RuntimeException timeout = new RuntimeException(
                "provider timed out",
                new SocketTimeoutException("read timed out")
        );

        assertThatThrownBy(() -> collector.captureContent("VERIFY", "提示词", () -> {
            throw timeout;
        })).isSameAs(timeout);

        AgentRunMetrics metrics = collector.snapshot();
        assertThat(metrics.modelCallCount()).isEqualTo(1);
        assertThat(metrics.timeoutCount()).isEqualTo(1);
        assertThat(metrics.calls().getFirst().timedOut()).isTrue();
        assertThat(metrics.calls().getFirst().error()).contains("provider timed out");
    }

    @Test
    void enforcesAgentStageHardTimeout() {
        AgentTelemetryCollector collector = new AgentTelemetryCollector(
                "test-model", 0.3, 0.6, Duration.ofMillis(30));

        assertThatThrownBy(() -> collector.captureContent("PLAN", "提示词", () -> {
            try {
                Thread.sleep(1_000);
                return new ChatResponse(List.of());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("cancelled", exception);
            }
        })).hasMessageContaining("PLAN model call exceeded");

        AgentRunMetrics metrics = collector.snapshot();
        assertThat(metrics.timeoutCount()).isEqualTo(1);
        assertThat(metrics.calls().getFirst().timedOut()).isTrue();
    }

    @Test
    void recognizesLibrariesThatDropInterruptedExceptionCause() {
        RuntimeException interruptedWithoutCause =
                new RuntimeException("Thread interrupted while sleeping");

        assertThat(AgentRunCancelledException.isCancellation(interruptedWithoutCause)).isTrue();
    }
}
