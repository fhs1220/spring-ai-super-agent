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
        boolean systemOneRecommendedSafetyGuard,
        double systemOneSafetyProbability,
        Map<String, Double> systemOneDomainProbabilities,
        long systemOneLatencyMs,
        String systemOneModel,
        String systemOneSampleId,
        String systemOneDeploymentMode,
        String systemOneReleaseVersion,
        boolean systemOneCanarySelected,
        boolean systemOneApplied,
        String systemOneApplicationStatus
) {

    public MultiAgentDecision {
        systemOneMode = systemOneMode == null ? "OFF" : systemOneMode;
        systemOneStatus = systemOneStatus == null ? "DISABLED" : systemOneStatus;
        systemOneMultiAgentProbability = Math.max(
                0, Math.min(1, systemOneMultiAgentProbability));
        systemOneSafetyProbability = Math.max(
                0, Math.min(1, systemOneSafetyProbability));
        systemOneDomainProbabilities = systemOneDomainProbabilities == null
                ? Map.of()
                : Map.copyOf(systemOneDomainProbabilities);
        systemOneLatencyMs = Math.max(0, systemOneLatencyMs);
        systemOneModel = systemOneModel == null ? "" : systemOneModel;
        systemOneSampleId = systemOneSampleId == null ? "" : systemOneSampleId;
        systemOneDeploymentMode = systemOneDeploymentMode == null ? "OFF" : systemOneDeploymentMode;
        systemOneReleaseVersion = systemOneReleaseVersion == null ? "" : systemOneReleaseVersion;
        systemOneApplicationStatus = systemOneApplicationStatus == null ? "NOT_SELECTED" : systemOneApplicationStatus;
    }
}
