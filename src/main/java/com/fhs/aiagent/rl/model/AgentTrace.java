package com.fhs.aiagent.rl.model;

import java.util.List;

/**
 * 面向用户展示的轻量 Agent 执行轨迹。
 */
public record AgentTrace(
        long totalDurationMs,
        String executionMode,
        List<AgentTraceStep> steps,
        List<RagCitation> citations,
        AgentRunMetrics telemetry
) {
}
