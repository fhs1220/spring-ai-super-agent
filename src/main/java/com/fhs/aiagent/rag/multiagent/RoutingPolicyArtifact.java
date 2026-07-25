package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不包含用户问题和回答的不可变路由策略资产。
 */
public record RoutingPolicyArtifact(
        int schemaVersion,
        String version,
        RoutingPolicyArtifactStatus status,
        String algorithm,
        String upstreamModel,
        Map<String, String> parameters,
        String trainingDataFingerprint,
        int trainingSampleCount,
        OfflineEvaluation offlineEvaluation,
        DecisionRule globalRule,
        Map<String, DecisionRule> contextualRules,
        String parentVersion,
        Instant createdAt,
        String validationReason
) {

    public RoutingPolicyArtifact {
        schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
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
        globalRule = globalRule == null
                ? DecisionRule.fromLegacyEvaluation(offlineEvaluation)
                : globalRule;
        contextualRules = contextualRules == null
                ? Map.of()
                : Map.copyOf(contextualRules);
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

    /**
     * 可直接用于线上推理的冻结决策规则。资产只保存通过样本数与效用门禁的规则。
     */
    public record DecisionRule(
            boolean deployable,
            String recommendedMode,
            double confidence,
            int evidenceSamples,
            double utilityLift,
            ModeEvaluation singleAgent,
            ModeEvaluation multiAgent,
            String reason
    ) {

        public DecisionRule {
            recommendedMode = normalize(recommendedMode);
            confidence = Math.max(0, Math.min(1, confidence));
            evidenceSamples = Math.max(0, evidenceSamples);
            singleAgent = singleAgent == null ? ModeEvaluation.empty() : singleAgent;
            multiAgent = multiAgent == null ? ModeEvaluation.empty() : multiAgent;
            reason = normalize(reason);
        }

        static DecisionRule empty() {
            return new DecisionRule(
                    false,
                    "DETERMINISTIC",
                    0,
                    0,
                    0,
                    ModeEvaluation.empty(),
                    ModeEvaluation.empty(),
                    "no deployable frozen rule"
            );
        }

        static DecisionRule fromLegacyEvaluation(OfflineEvaluation evaluation) {
            if (evaluation == null || !evaluation.validationPassed()) {
                return empty();
            }
            int evidenceSamples = Math.min(
                    evaluation.singleAgent().sampleCount(),
                    evaluation.multiAgent().sampleCount()
            );
            return new DecisionRule(
                    true,
                    evaluation.recommendedMode(),
                    0,
                    evidenceSamples,
                    evaluation.multiAgentUtilityLift(),
                    evaluation.singleAgent(),
                    evaluation.multiAgent(),
                    "migrated from schema v1 global evaluation"
            );
        }
    }
}
