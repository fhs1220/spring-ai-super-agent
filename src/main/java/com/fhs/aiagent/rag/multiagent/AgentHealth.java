package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;

/**
 * 不暴露提示词或用户数据的专业 Agent 运行健康快照。
 */
public record AgentHealth(
        String agentId,
        String agentName,
        AgentDomain domain,
        String status,
        int consecutiveFailures,
        Instant openUntil
) {
}
