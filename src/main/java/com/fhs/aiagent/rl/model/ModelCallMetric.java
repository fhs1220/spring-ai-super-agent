package com.fhs.aiagent.rl.model;

public record ModelCallMetric(
        String stage,
        long durationMs,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean usageEstimated,
        boolean timedOut,
        String error
) {
}
