package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rl.model.AgentTrace;

public record RagVariantExecution(
        RagEvaluationVariant variant,
        String answer,
        long latencyMs,
        long totalTokens,
        double estimatedCostCny,
        boolean usageAvailable,
        String executionMode,
        String error,
        AgentTrace trace
) {

    public RagVariantExecution(RagEvaluationVariant variant, String answer, long latencyMs,
                               long totalTokens, double estimatedCostCny, boolean usageAvailable,
                               String executionMode, String error) {
        this(variant, answer, latencyMs, totalTokens, estimatedCostCny, usageAvailable,
                executionMode, error, null);
    }

    public boolean fellBackToSingle() {
        return "ADAPTIVE_MULTI_AGENT".equals(executionMode) && trace != null
                && trace.steps() != null && trace.steps().stream()
                .anyMatch(step -> "GENERATE".equals(step.phase()));
    }

    public boolean succeeded() {
        return error == null || error.isBlank();
    }
}
