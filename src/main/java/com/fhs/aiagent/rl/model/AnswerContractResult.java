package com.fhs.aiagent.rl.model;

import java.util.List;

/** Deterministic check of the selected final answer, not semantic or safety approval. */
public record AnswerContractResult(String version, boolean passed, List<String> missingRequirements) {
    public AnswerContractResult {
        missingRequirements = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
    }
}
