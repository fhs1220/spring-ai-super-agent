package com.fhs.aiagent.evaluation;

public record RagVariantExecution(
        RagEvaluationVariant variant,
        String answer,
        long latencyMs,
        long totalTokens,
        double estimatedCostCny,
        boolean usageAvailable,
        String executionMode,
        String error
) {

    public boolean succeeded() {
        return error == null || error.isBlank();
    }
}
