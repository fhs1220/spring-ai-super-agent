package com.fhs.aiagent.rag.multiagent;

import java.util.List;

/**
 * 自适应路由结果。
 */
public record MultiAgentDecision(
        String mode,
        boolean multiAgent,
        double complexityScore,
        String reason,
        List<AgentDomain> selectedDomains,
        String featureBucket,
        String policySource,
        double policyConfidence,
        int policyEvidenceSamples
) {
}
