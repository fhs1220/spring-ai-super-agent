package com.fhs.aiagent.rl.model;

public record RagCitation(
        int index,
        String documentId,
        String source,
        String excerpt
) {
}
