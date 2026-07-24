package com.fhs.aiagent.rl.model;

import java.util.List;

public record AgentRunMetrics(
        String model,
        int modelCallCount,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean usageEstimated,
        double estimatedCostCny,
        int timeoutCount,
        List<ModelCallMetric> calls
) {
}
