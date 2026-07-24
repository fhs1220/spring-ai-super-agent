package com.fhs.aiagent.evaluation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RagAnswerScorer {

    private static final Pattern VALID_CITATION = Pattern.compile("\\[来源\\s*\\d+]");

    private static final List<String> FOLLOW_UP_PHRASES = List.of(
            "请你详细描述",
            "请详细描述",
            "请补充更多",
            "请提供更多",
            "以便我为你",
            "接下来，请你"
    );

    public Score score(RagEvaluationCase evaluationCase, RagVariantExecution execution) {
        if (execution == null || !execution.succeeded()
                || execution.answer() == null || execution.answer().isBlank()) {
            return new Score(0, 0, 0, 0, 0, 0, false);
        }

        String answer = execution.answer();
        String normalized = answer.toLowerCase(Locale.ROOT);
        double conceptCoverage = conceptCoverage(evaluationCase.requiredConcepts(), normalized);
        double directness = containsAny(normalized, FOLLOW_UP_PHRASES) ? 0 : 1;
        double citationQuality = evaluationCase.requireCitation()
                ? (VALID_CITATION.matcher(answer).find() ? 1 : 0)
                : 1;
        double forbiddenPhraseQuality = containsAny(
                normalized,
                evaluationCase.forbiddenPhrases().stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .toList())
                ? 0
                : 1;
        double lengthQuality = lengthQuality(
                answer.codePointCount(0, answer.length()),
                evaluationCase.minAnswerChars(),
                evaluationCase.maxAnswerChars()
        );
        boolean routeCorrect = evaluationCase.expectedExecutionMode() == null
                || evaluationCase.expectedExecutionMode().isBlank()
                || execution.variant() == RagEvaluationVariant.TRADITIONAL_RAG
                || evaluationCase.expectedExecutionMode().equals(execution.executionMode());
        double total = 0.5 * conceptCoverage
                + 0.15 * directness
                + 0.15 * citationQuality
                + 0.1 * forbiddenPhraseQuality
                + 0.1 * lengthQuality;
        return new Score(
                round(total),
                round(conceptCoverage),
                directness,
                citationQuality,
                forbiddenPhraseQuality,
                round(lengthQuality),
                routeCorrect
        );
    }

    private double conceptCoverage(List<String> concepts, String answer) {
        if (concepts == null || concepts.isEmpty()) {
            return 1;
        }
        long matched = concepts.stream()
                .filter(concept -> List.of(concept.toLowerCase(Locale.ROOT).split("\\|"))
                        .stream()
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .anyMatch(answer::contains))
                .count();
        return matched / (double) concepts.size();
    }

    private double lengthQuality(int length, int minimum, int maximum) {
        if (minimum <= 0 && maximum <= 0) {
            return 1;
        }
        if (minimum > 0 && length < minimum) {
            return Math.max(0, length / (double) minimum);
        }
        if (maximum > 0 && length > maximum) {
            return Math.max(0, maximum / (double) length);
        }
        return 1;
    }

    private boolean containsAny(String answer, List<String> phrases) {
        return phrases != null && phrases.stream()
                .filter(phrase -> phrase != null && !phrase.isBlank())
                .anyMatch(answer::contains);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Score(
            double total,
            double conceptCoverage,
            double directness,
            double citationQuality,
            double forbiddenPhraseQuality,
            double lengthQuality,
            boolean routeCorrect
    ) {
    }
}
