package com.fhs.aiagent.rl.bailian;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 为百炼云端 Rollout 暴露最小化、只读的知识库检索能力。
 */
@Service
public class AgentRlRetrievalService {

    private static final int MAX_TOP_K = 10;

    private static final int MAX_DOCUMENT_LENGTH = 6000;

    private final VectorStore vectorStore;

    public AgentRlRetrievalService(@Qualifier("loveAppVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public RetrievalResult retrieve(String query, int topK, double similarityThreshold) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        int safeTopK = Math.max(1, Math.min(topK, MAX_TOP_K));
        double safeThreshold = Math.max(0, Math.min(similarityThreshold, 1));
        SearchRequest request = SearchRequest.builder()
                .query(query.trim())
                .topK(safeTopK)
                .similarityThreshold(safeThreshold)
                .build();
        List<Document> found = vectorStore.similaritySearch(request);
        List<RetrievedDocument> documents = found == null
                ? List.of()
                : found.stream()
                .filter(document -> document != null && document.getText() != null)
                .map(this::toRetrievedDocument)
                .toList();
        return new RetrievalResult(query.trim(), documents);
    }

    private RetrievedDocument toRetrievedDocument(Document document) {
        String text = document.getText();
        if (text.length() > MAX_DOCUMENT_LENGTH) {
            text = text.substring(0, MAX_DOCUMENT_LENGTH);
        }
        Map<String, Object> metadata = document.getMetadata();
        Object source = metadata == null ? null : metadata.get("source");
        return new RetrievedDocument(
                document.getId(),
                source == null ? "" : source.toString(),
                text
        );
    }

    public record RetrievalResult(String query, List<RetrievedDocument> documents) {
    }

    public record RetrievedDocument(String id, String source, String content) {
    }
}
