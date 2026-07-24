package com.fhs.aiagent.rl.model;

import java.time.Instant;
import java.util.Map;

public record AgentStep(
        String stepId,
        AgentStepType type,
        Instant startedAt,
        long durationMs,
        boolean success,
        Map<String, Object> input,
        Map<String, Object> output
) {
}
