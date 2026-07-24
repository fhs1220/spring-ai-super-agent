package com.fhs.aiagent.rl.model;

public record AgenticRagResult(
        String answer,
        String trajectoryId,
        RewardBreakdown reward,
        AgentTrace trace
) {
}
