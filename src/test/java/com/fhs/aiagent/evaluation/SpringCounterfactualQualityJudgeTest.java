package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.AgentRunCancelledException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringCounterfactualQualityJudgeTest {
    private static final String VALID = """
            {"scoreA":0.2,"scoreB":0.8,"confidence":0.9,"rationale":"B covers the missing steps"}
            """;

    @Test
    void recordsProviderMetadataUsageAndEstimatedCharge() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(VALID, true));
        var judge = new SpringCounterfactualQualityJudge(model, 2, 8);
        try {
            var result = judge.judge("question", "b", "single answer", "multi answer");

            assertThat(result.singleQuality()).isEqualTo(0.2);
            assertThat(result.multiQuality()).isEqualTo(0.8);
            assertThat(result.usage().measured()).isTrue();
            assertThat(result.usage().inputTokens()).isEqualTo(1000);
            assertThat(result.usage().outputTokens()).isEqualTo(250);
            assertThat(result.usage().estimatedCostCny()).isCloseTo(0.004, within(0.0000001));
            assertThat(result.usage().model()).isEqualTo("judge-model-version");
            assertThat(result.usage().responseId()).isEqualTo("response-123");
            assertThat(result.usage().promptFingerprint()).matches("[0-9a-f]{64}");
        } finally {
            judge.close();
        }
    }

    @Test
    void missingUsageOrUnpricedUsageRemainsUnknown() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(VALID, false), response(VALID, true));
        var priced = new SpringCounterfactualQualityJudge(model, 2, 8);
        var unpriced = new SpringCounterfactualQualityJudge(model);
        try {
            assertThat(priced.judge("question", "b", "single", "multi").usage().measured()).isFalse();
            assertThat(unpriced.judge("question", "b", "single", "multi").usage().measured()).isFalse();
        } finally {
            priced.close();
            unpriced.close();
        }
    }

    @Test
    void invalidStructuredScoresPreserveKnownChargeInFailure() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"scoreA\":-0.1,\"scoreB\":0.8,\"confidence\":0.9,\"rationale\":\"invalid\"}", true));
        var judge = new SpringCounterfactualQualityJudge(model, 2, 8);
        try {
            assertThatThrownBy(() -> judge.judge("question", "b", "single", "multi"))
                    .isInstanceOfSatisfying(SpringCounterfactualQualityJudge.JudgeResponseException.class,
                            failure -> {
                                assertThat(failure.usage().measured()).isTrue();
                                assertThat(failure.usage().estimatedCostCny()).isCloseTo(0.004, within(0.0000001));
                                assertThat(failure.usage().responseId()).isEqualTo("response-123");
                            });
        } finally {
            judge.close();
        }
    }

    @Test
    void mapsBlindScoresBackToExecutionOrderWhenAnswerLabelsSwap() {
        ChatModel model = mock(ChatModel.class);
        AtomicReference<String> userPrompt = new AtomicReference<>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            userPrompt.set(invocation.getArgument(0, Prompt.class).getUserMessage().getText());
            return response(VALID, true);
        });
        var judge = new SpringCounterfactualQualityJudge(model, 2, 8);
        try {
            var result = judge.judge("question", "a", "single answer", "multi answer");

            assertThat(userPrompt.get()).contains("<untrusted_answer_a>multi answer</untrusted_answer_a>",
                    "<untrusted_answer_b>single answer</untrusted_answer_b>");
            assertThat(result.singleQuality()).isEqualTo(0.8);
            assertThat(result.multiQuality()).isEqualTo(0.2);
        } finally {
            judge.close();
        }
    }

    @Test
    void timeoutReturnsPromptlyAndKeepsBusyUntilUnderlyingWorkerExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = blockingFirstCall(entered, release, calls);
        var judge = new SpringCounterfactualQualityJudge(model, 2, 8, 150);
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> judge.judge("question", "b", "single", "multi"))
                    .isInstanceOf(SpringCounterfactualQualityJudge.JudgeTimeoutException.class)
                    .hasMessageContaining("charge is unknown");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2000);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> judge.judge("next", "b", "single", "multi"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("has not exited");
            assertThat(calls).hasValue(1);

            release.countDown();
            var result = retryAfterWorkerExit(judge);
            assertThat(result.usage().measured()).isTrue();
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            judge.close();
        }
    }

    @Test
    void interruptionPreservesCancellationAndShutdownDoesNotWaitForStuckHttp() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callerDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        var judge = new SpringCounterfactualQualityJudge(blockingFirstCall(entered, release, calls), 2, 8, 10_000);
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                judge.judge("question", "b", "single", "multi");
            } catch (Throwable exception) {
                failure.set(exception);
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                callerDone.countDown();
            }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(callerDone.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isInstanceOf(AgentRunCancelledException.class);
            assertThat(callerInterrupted).isTrue();
            assertThatThrownBy(() -> judge.judge("next", "b", "single", "multi"))
                    .hasMessageContaining("has not exited");
            assertThat(calls).hasValue(1);

            long closeStarted = System.nanoTime();
            judge.close();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)).isLessThan(1000);
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(1000);
            judge.close();
        }
    }

    private ChatModel blockingFirstCall(CountDownLatch entered, CountDownLatch release, AtomicInteger calls) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                boolean finished = false;
                while (!finished) {
                    try {
                        release.await();
                        finished = true;
                    } catch (InterruptedException ignored) {
                        // Model an HTTP transport that ignores cancellation until it returns.
                    }
                }
            }
            return response(VALID, true);
        });
        return model;
    }

    private CounterfactualQualityJudge.PairJudgment retryAfterWorkerExit(
            SpringCounterfactualQualityJudge judge) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                return judge.judge("next", "b", "single", "multi");
            } catch (IllegalStateException busy) {
                if (!busy.getMessage().contains("has not exited") || System.nanoTime() >= deadline) throw busy;
                Thread.sleep(5);
            }
        }
    }

    private ChatResponse response(String content, boolean withUsage) {
        var generations = List.of(new Generation(new AssistantMessage(content)));
        if (!withUsage) return new ChatResponse(generations);
        return new ChatResponse(generations, ChatResponseMetadata.builder()
                .id("response-123").model("judge-model-version")
                .usage(new DefaultUsage(1000, 250)).build());
    }
}
