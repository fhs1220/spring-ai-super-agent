package com.fhs.aiagent.rl.model;

import java.util.List;

public record AgentTraceStep(
        String phase,
        String title,
        String summary,
        long durationMs,
        boolean success,
        List<String> details
) {
}
