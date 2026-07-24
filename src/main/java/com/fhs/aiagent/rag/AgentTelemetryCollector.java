package com.fhs.aiagent.rag;

import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.ModelCallMetric;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 收集单次 Agent 运行中每次模型调用的 Token、耗时和超时信息。
 */
public class AgentTelemetryCollector {

    private final String model;

    private final double inputPricePerMillionTokens;

    private final double outputPricePerMillionTokens;

    private final Duration modelCallTimeout;

    /**
     * 多 Agent 会并行调用模型，因此调用指标必须支持并发写入。
     */
    private final List<ModelCallMetric> calls = new CopyOnWriteArrayList<>();

    public AgentTelemetryCollector(String model,
                                   double inputPricePerMillionTokens,
                                   double outputPricePerMillionTokens) {
        this(model, inputPricePerMillionTokens, outputPricePerMillionTokens, Duration.ofSeconds(30));
    }

    AgentTelemetryCollector(String model,
                            double inputPricePerMillionTokens,
                            double outputPricePerMillionTokens,
                            Duration modelCallTimeout) {
        this.model = model;
        this.inputPricePerMillionTokens = inputPricePerMillionTokens;
        this.outputPricePerMillionTokens = outputPricePerMillionTokens;
        if (modelCallTimeout == null || modelCallTimeout.isZero() || modelCallTimeout.isNegative()) {
            throw new IllegalArgumentException("modelCallTimeout must be positive");
        }
        this.modelCallTimeout = modelCallTimeout;
    }

    public <T> T captureEntity(String stage,
                               String promptText,
                               Supplier<ResponseEntity<ChatResponse, T>> invocation) {
        long startedAt = System.nanoTime();
        try {
            ResponseEntity<ChatResponse, T> responseEntity = invokeWithTimeout(stage, invocation);
            recordSuccess(stage, promptText, responseEntity.response(), startedAt);
            return responseEntity.entity();
        } catch (RuntimeException exception) {
            recordFailure(stage, promptText, exception, startedAt);
            throw exception;
        }
    }

    public String captureContent(String stage,
                                 String promptText,
                                 Supplier<ChatResponse> invocation) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = invokeWithTimeout(stage, invocation);
            recordSuccess(stage, promptText, response, startedAt);
            return response == null
                    || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null
                    : response.getResult().getOutput().getText();
        } catch (RuntimeException exception) {
            recordFailure(stage, promptText, exception, startedAt);
            throw exception;
        }
    }

    public AgentRunMetrics snapshot() {
        int promptTokens = calls.stream().mapToInt(ModelCallMetric::promptTokens).sum();
        int completionTokens = calls.stream().mapToInt(ModelCallMetric::completionTokens).sum();
        boolean estimated = calls.stream().anyMatch(ModelCallMetric::usageEstimated);
        int timeoutCount = (int) calls.stream().filter(ModelCallMetric::timedOut).count();
        double cost = promptTokens * inputPricePerMillionTokens / 1_000_000.0
                + completionTokens * outputPricePerMillionTokens / 1_000_000.0;
        return new AgentRunMetrics(
                model,
                calls.size(),
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                estimated,
                round(cost),
                timeoutCount,
                List.copyOf(calls)
        );
    }

    private void recordSuccess(String stage,
                               String promptText,
                               ChatResponse response,
                               long startedAt) {
        String outputText = response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                ? ""
                : Objects.toString(response.getResult().getOutput().getText(), "");
        Usage usage = response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        Integer actualPromptTokens = usage == null ? null : usage.getPromptTokens();
        Integer actualCompletionTokens = usage == null ? null : usage.getCompletionTokens();
        boolean estimated = actualPromptTokens == null || actualCompletionTokens == null
                || actualPromptTokens + actualCompletionTokens <= 0;
        int promptTokens = estimated ? estimateTokens(promptText) : actualPromptTokens;
        int completionTokens = estimated ? estimateTokens(outputText) : actualCompletionTokens;
        calls.add(new ModelCallMetric(
                stage,
                elapsedMs(startedAt),
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                estimated,
                false,
                ""
        ));
    }

    private void recordFailure(String stage,
                               String promptText,
                               RuntimeException exception,
                               long startedAt) {
        boolean timedOut = isTimeout(exception);
        int promptTokens = estimateTokens(promptText);
        calls.add(new ModelCallMetric(
                stage,
                elapsedMs(startedAt),
                promptTokens,
                0,
                promptTokens,
                true,
                timedOut,
                exception.getClass().getSimpleName() + ": "
                        + Objects.toString(exception.getMessage(), "")
        ));
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            String message = Objects.toString(current.getMessage(), "").toLowerCase(Locale.ROOT);
            if (message.contains("timeout") || message.contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private <T> T invokeWithTimeout(String stage, Supplier<T> invocation) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(invocation::get);
        try {
            return future.get(modelCallTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AgentModelTimeoutException(
                    "%s model call exceeded %d ms".formatted(stage, modelCallTimeout.toMillis()),
                    exception
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException(stage + " model call interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(stage + " model call failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int hanTokens = 0;
        int otherCharacters = 0;
        for (int codePoint : text.codePoints().toArray()) {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                hanTokens++;
            } else if (!Character.isWhitespace(codePoint)) {
                otherCharacters++;
            }
        }
        return Math.max(1, hanTokens + (int) Math.ceil(otherCharacters / 4.0));
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private double round(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    private static final class AgentModelTimeoutException extends RuntimeException {

        private AgentModelTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
