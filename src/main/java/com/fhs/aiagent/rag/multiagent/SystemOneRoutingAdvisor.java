package com.fhs.aiagent.rag.multiagent;

import java.util.Map;

/**
 * Optional System One shadow advisor. Its result is observable but does not own routing.
 */
public interface SystemOneRoutingAdvisor {

    RoutingAdvice advise(String question);

    static SystemOneRoutingAdvisor disabled() {
        return ignored -> RoutingAdvice.disabled();
    }

    record RoutingAdvice(
            String mode,
            String status,
            boolean recommendedMultiAgent,
            double multiAgentProbability,
            boolean recommendedSafetyGuard,
            double safetyProbability,
            Map<AgentDomain, Double> domainProbabilities,
            long latencyMs,
            String model,
            long inputTokens,
            long outputTokens,
            double estimatedCostUsd
    ) {

        public RoutingAdvice(String mode, String status,
                             boolean recommendedMultiAgent,
                             double multiAgentProbability,
                             Map<AgentDomain, Double> domainProbabilities,
                             long latencyMs, String model) {
            this(mode, status, recommendedMultiAgent, multiAgentProbability,
                    safetyProbability(domainProbabilities) >= 0.5,
                    safetyProbability(domainProbabilities),
                    domainProbabilities, latencyMs, model, 0, 0, 0);
        }

        public RoutingAdvice(String mode, String status,
                             boolean recommendedMultiAgent,
                             double multiAgentProbability,
                             Map<AgentDomain, Double> domainProbabilities,
                             long latencyMs, String model,
                             long inputTokens, long outputTokens,
                             double estimatedCostUsd) {
            this(mode, status, recommendedMultiAgent, multiAgentProbability,
                    safetyProbability(domainProbabilities) >= 0.5,
                    safetyProbability(domainProbabilities),
                    domainProbabilities, latencyMs, model,
                    inputTokens, outputTokens, estimatedCostUsd);
        }

        public RoutingAdvice {
            mode = mode == null ? "OFF" : mode;
            status = status == null ? "DISABLED" : status;
            multiAgentProbability = clamp(multiAgentProbability);
            safetyProbability = clamp(safetyProbability);
            domainProbabilities = domainProbabilities == null
                    ? Map.of()
                    : Map.copyOf(domainProbabilities);
            latencyMs = Math.max(0, latencyMs);
            model = model == null ? "" : model;
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            estimatedCostUsd = Math.max(0, estimatedCostUsd);
        }

        static RoutingAdvice disabled() {
            return new RoutingAdvice(
                    "OFF", "DISABLED", false, 0,
                    false, 0, Map.of(), 0, "", 0, 0, 0);
        }

        private static double safetyProbability(Map<AgentDomain, Double> probabilities) {
            return probabilities == null
                    ? 0
                    : probabilities.getOrDefault(AgentDomain.SAFETY, 0.0);
        }

        private static double clamp(double value) {
            return Math.max(0, Math.min(1, value));
        }
    }
}
