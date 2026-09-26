package com.fhs.aiagent.rag.multiagent;

import java.util.List;
import java.util.Map;

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
        String policyCandidateSource,
        String policyRolloutMode,
        boolean policyApplied,
        boolean policyCanarySelected,
        double policyBehaviorActionProbability,
        boolean policyExplorationEligible,
        double policyConfidence,
        int policyEvidenceSamples,
        String policyDeploymentVersion,
        String policyArtifactVersion,
        String systemOneMode,
        String systemOneStatus,
        boolean systemOneRecommendedMultiAgent,
        double systemOneMultiAgentProbability,
        Map<String, Double> systemOneDomainProbabilities,
        long systemOneLatencyMs,
        String systemOneModel
) {

    public MultiAgentDecision {
        systemOneMode = systemOneMode == null ? "OFF" : systemOneMode;
        systemOneStatus = systemOneStatus == null ? "DISABLED" : systemOneStatus;
        systemOneMultiAgentProbability = Math.max(
                0, Math.min(1, systemOneMultiAgentProbability));
        systemOneDomainProbabilities = systemOneDomainProbabilities == null
                ? Map.of()
                : Map.copyOf(systemOneDomainProbabilities);
        systemOneLatencyMs = Math.max(0, systemOneLatencyMs);
        systemOneModel = systemOneModel == null ? "" : systemOneModel;
    }
}
