package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;
import java.util.List;

/** One asynchronous Jev/Laya comparison, independent of the production route. */
public record SystemOneShadowSample(
        String sampleId,
        Instant capturedAt,
        String questionFingerprint,
        String question,
        String featureBucket,
        boolean authoritativeMultiAgent,
        ProviderObservation primary,
        ProviderObservation challenger,
        List<String> disagreementTypes,
        boolean reviewEligible,
        String sampledReason
) {

    public SystemOneShadowSample {
        sampleId = sampleId == null ? "" : sampleId;
        capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
        questionFingerprint = questionFingerprint == null ? "" : questionFingerprint;
        question = question == null ? "" : question;
        featureBucket = featureBucket == null ? "" : featureBucket;
        disagreementTypes = disagreementTypes == null ? List.of() : List.copyOf(disagreementTypes);
        sampledReason = sampledReason == null ? "" : sampledReason;
    }

    public record ProviderObservation(
            String status,
            boolean recommendedMultiAgent,
            double multiAgentProbability,
            boolean recommendedSafetyGuard,
            double safetyProbability,
            long latencyMs,
            String model,
            long inputTokens,
            long outputTokens,
            double estimatedCostUsd
    ) {

        public ProviderObservation {
            status = status == null ? "FAILED" : status;
            multiAgentProbability = clamp(multiAgentProbability);
            safetyProbability = clamp(safetyProbability);
            latencyMs = Math.max(0, latencyMs);
            model = model == null ? "" : model;
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            estimatedCostUsd = Math.max(0, estimatedCostUsd);
        }

        static ProviderObservation from(SystemOneRoutingAdvisor.RoutingAdvice advice) {
            return new ProviderObservation(
                    advice.status(), advice.recommendedMultiAgent(),
                    advice.multiAgentProbability(), advice.recommendedSafetyGuard(),
                    advice.safetyProbability(), advice.latencyMs(), advice.model(),
                    advice.inputTokens(), advice.outputTokens(), advice.estimatedCostUsd());
        }

        private static double clamp(double value) {
            return Math.max(0, Math.min(1, value));
        }
    }
}
