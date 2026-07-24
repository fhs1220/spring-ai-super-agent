package com.fhs.aiagent.evaluation;

import java.util.List;

/**
 * 单条可版本化 A/B 基准样本。requiredConcepts 中使用 "|" 表示同义备选词。
 */
public record RagEvaluationCase(
        String id,
        String question,
        List<String> tags,
        List<String> requiredConcepts,
        List<String> forbiddenPhrases,
        int minAnswerChars,
        int maxAnswerChars,
        boolean requireCitation,
        String expectedExecutionMode
) {

    public RagEvaluationCase {
        tags = tags == null ? List.of() : List.copyOf(tags);
        requiredConcepts = requiredConcepts == null ? List.of() : List.copyOf(requiredConcepts);
        forbiddenPhrases = forbiddenPhrases == null ? List.of() : List.copyOf(forbiddenPhrases);
    }
}
