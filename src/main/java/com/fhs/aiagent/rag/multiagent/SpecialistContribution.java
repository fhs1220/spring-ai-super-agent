package com.fhs.aiagent.rag.multiagent;

import java.util.List;

/**
 * 专业 Agent 写入共享黑板的结构化贡献。
 */
public record SpecialistContribution(
        String agentId,
        String agentName,
        AgentDomain domain,
        boolean success,
        List<String> findings,
        List<String> recommendations,
        List<Integer> citedSources,
        String uncertainty,
        double confidence,
        double processReward,
        long durationMs,
        String error
) {
}
