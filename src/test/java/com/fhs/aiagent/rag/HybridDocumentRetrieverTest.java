package com.fhs.aiagent.rag;

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

class HybridDocumentRetrieverTest {

    @Test
    void expandsTopKForLongAndMultiIntentQueries() {
        HybridDocumentRetriever retriever = new HybridDocumentRetriever(
                mock(VectorStore.class), List.of(), 3, 6, 0.2);

        assertThat(retriever.dynamicTopK("家务分工")).isEqualTo(3);
        assertThat(retriever.dynamicTopK("如何改善夫妻之间长期存在的沟通冲突")).isEqualTo(4);
        assertThat(retriever.dynamicTopK(
                "我们需要同时解决孩子教育、家庭预算以及家务分配的问题，请分别给出建议"))
                .isEqualTo(6);
    }

    @Test
    void lexicalRecallCanRerankExactEvidenceAheadOfGenericVectorResult() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document genericVectorDocument = Document.builder()
                .id("vector-doc")
                .text("夫妻之间应该保持亲密交流。")
                .metadata(Map.of("filename", "通用.md"))
                .score(0.95)
                .build();
        Document exactLexicalDocument = new Document(
                "lexical-doc",
                "夫妻应共同协商家务分工，列出任务清单并定期轮换。",
                Map.of("filename", "家务.md"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(genericVectorDocument));
        HybridDocumentRetriever retriever = new HybridDocumentRetriever(
                vectorStore, List.of(exactLexicalDocument), 3, 6, 0.2);

        HybridDocumentRetriever.HybridSearchResult result = retriever.search("家务分工");

        assertThat(result.documents()).isNotEmpty();
        assertThat(result.documents().getFirst().getId()).isEqualTo("lexical-doc");
        assertThat(result.topK()).isEqualTo(3);
        assertThat(result.vectorCandidateCount()).isEqualTo(1);
        assertThat(result.lexicalCandidateCount()).isEqualTo(1);
        assertThat(result.strategy()).isEqualTo(HybridDocumentRetriever.STRATEGY);
    }

    @Test
    void fusesDuplicateVectorAndLexicalDocumentsOnce() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document vectorDocument = Document.builder()
                .id("vector-id")
                .text("争吵后先冷静，再倾听对方感受。")
                .metadata(Map.of("filename", "沟通.md"))
                .score(0.9)
                .build();
        Document lexicalCopy = new Document(
                "lexical-id",
                "争吵后先冷静，再倾听对方感受。",
                Map.of("filename", "沟通.md"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vectorDocument));
        HybridDocumentRetriever retriever = new HybridDocumentRetriever(
                vectorStore, List.of(lexicalCopy), 3, 6, 0.2);

        HybridDocumentRetriever.HybridSearchResult result = retriever.search("争吵冷静倾听");

        assertThat(result.documents()).hasSize(1);
        assertThat(result.fusedCandidateCount()).isEqualTo(1);
    }
}
