package com.fhs.aiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppVectorStoreConfigTest {

    @Test
    void skipsDocumentEnrichmentWhenCacheIsValid() {
        AppDocumentLoader documentLoader = mock(AppDocumentLoader.class);
        MyKeywordEnricher keywordEnricher = mock(MyKeywordEnricher.class);
        LocalVectorStoreCache cache = mock(LocalVectorStoreCache.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(documentLoader.fingerprintMarkdowns()).thenReturn("knowledge");
        when(cache.restore(any(SimpleVectorStore.class), eq("v1:knowledge"))).thenReturn(true);

        VectorStore vectorStore = new AppVectorStoreConfig(
                documentLoader, keywordEnricher, cache, "v1")
                .loveAppVectorStore(embeddingModel);

        assertThat(vectorStore).isInstanceOf(SimpleVectorStore.class);
        verify(documentLoader, never()).loadMarkdowns();
        verify(keywordEnricher, never()).enrichDocuments(any());
        verify(cache, never()).save(any(), any());
    }

    @Test
    void buildsAndSavesIndexWhenCacheMisses() {
        AppDocumentLoader documentLoader = mock(AppDocumentLoader.class);
        MyKeywordEnricher keywordEnricher = mock(MyKeywordEnricher.class);
        LocalVectorStoreCache cache = mock(LocalVectorStoreCache.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(documentLoader.fingerprintMarkdowns()).thenReturn("knowledge");
        Document document = new Document("doc-1", "知识库内容", Map.of("filename", "测试.md"));
        when(documentLoader.loadMarkdowns()).thenReturn(List.of(document));
        when(keywordEnricher.enrichDocuments(List.of(document))).thenReturn(List.of(document));
        when(embeddingModel.embed(document)).thenReturn(new float[]{1.0f, 0.0f});
        when(embeddingModel.embed(
                any(List.class),
                any(EmbeddingOptions.class),
                any(BatchingStrategy.class)))
                .thenReturn(List.of(new float[]{1.0f, 0.0f}));

        new AppVectorStoreConfig(documentLoader, keywordEnricher, cache, "v2")
                .loveAppVectorStore(embeddingModel);

        verify(documentLoader).loadMarkdowns();
        verify(keywordEnricher).enrichDocuments(List.of(document));
        verify(cache).save(any(SimpleVectorStore.class), eq("v2:knowledge"));
    }
}
