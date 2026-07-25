package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不包含用户问题和回答的不可变路由策略资产。
 */
public record RoutingPolicyArtifact(
        String version,
        RoutingPolicyArtifactStatus status,
        String algorithm,
        String upstreamModel,
        Map<String, String> parameters,
        String trainingDataFingerprint,
        int trainingSampleCount,
        OfflineEvaluation offlineEvaluation,
        String parentVersion,
        Instant createdAt,
        String validationReason
) {

    public RoutingPolicyArtifact {
        version = normalize(version);
        status = status == null ? RoutingPolicyArtifactStatus.REJECTED : status;
        algorithm = normalize(algorithm);
        upstreamModel = normalize(upstreamModel);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        trainingDataFingerprint = normalize(trainingDataFingerprint);
        trainingSampleCount = Math.max(0, trainingSampleCount);
        offlineEvaluation = offlineEvaluation == null
                ? OfflineEvaluation.empty()
                : offlineEvaluation;
        parentVersion = normalize(parentVersion);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        validationReason = normalize(validationReason);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record OfflineEvaluation(
            boolean balancedEvidence,
            int observedTrajectoryCount,
            int minimumSamplesPerMode,
            ModeEvaluation singleAgent,
            ModeEvaluation multiAgent,
            double multiAgentUtilityLift,
            String recommendedMode,
            boolean validationPassed,
            List<String> validationFailures
    ) {

        public OfflineEvaluation {
            observedTrajectoryCount = Math.max(0, observedTrajectoryCount);
            minimumSamplesPerMode = Math.max(0, minimumSamplesPerMode);
            singleAgent = singleAgent == null ? ModeEvaluation.empty() : singleAgent;
            multiAgent = multiAgent == null ? ModeEvaluation.empty() : multiAgent;
            recommendedMode = recommendedMode == null ? "" : recommendedMode;
            validationFailures = validationFailures == null
                    ? List.of()
                    : List.copyOf(validationFailures);
        }

        static OfflineEvaluation empty() {
            return new OfflineEvaluation(
                    false,
                    0,
                    0,
                    ModeEvaluation.empty(),
                    ModeEvaluation.empty(),
                    0,
                    "DETERMINISTIC",
                    false,
                    List.of("baseline artifact")
            );
        }
    }

    public record ModeEvaluation(
            int sampleCount,
            int successfulCount,
            int usageMeasuredSamples,
            double averageReward,
            double averageCostCny,
            double averageLatencyMs,
            double utility
    ) {

        static ModeEvaluation empty() {
            return new ModeEvaluation(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
