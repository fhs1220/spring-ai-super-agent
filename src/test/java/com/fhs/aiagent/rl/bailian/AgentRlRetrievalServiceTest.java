package com.fhs.aiagent.rl.bailian;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRlRetrievalServiceTest {

    @Test
    void returnsOnlyMinimalDocumentFields() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("doc-1", "共同协商家务分工。", Map.of(
                        "source", "已婚篇.md",
                        "private-field", "must-not-leak"
                ))
        ));
        AgentRlRetrievalService service = new AgentRlRetrievalService(vectorStore);

        AgentRlRetrievalService.RetrievalResult result =
                service.retrieve("家务冲突", 100, -1);

        assertThat(result.query()).isEqualTo("家务冲突");
        assertThat(result.documents()).containsExactly(
                new AgentRlRetrievalService.RetrievedDocument(
                        "doc-1", "已婚篇.md", "共同协商家务分工。")
        );
    }
}
