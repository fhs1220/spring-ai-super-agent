package com.fhs.aiagent.decision;

import java.util.Map;

/**
 * Minimal client contract for Jev-compatible System One decision servers.
 */
public interface SystemOneDecisionClient {

    SystemOneResult evaluate(Object state, Map<String, ?> questions);

    record SystemOneResult(
            String model,
            Map<String, SystemOneAnswer> answers,
            long inputTokens,
            long outputTokens
    ) {

        public SystemOneResult(String model, Map<String, SystemOneAnswer> answers) {
            this(model, answers, 0, 0);
        }

        public SystemOneResult {
            model = model == null ? "" : model;
            answers = answers == null ? Map.of() : Map.copyOf(answers);
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
        }
    }

    record SystemOneAnswer(
            String type,
            Double noul,
            String choice,
            Double score,
            Double confidence,
            Map<String, Double> probabilities
    ) {

        public SystemOneAnswer {
            type = type == null ? "" : type;
            choice = choice == null ? "" : choice;
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        }
    }
}
