package com.fhs.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 向量数据库配置（初始化基于内存的向量数据库 Bean）
 */
@Configuration
@Slf4j
public class AppVectorStoreConfig {

    private final AppDocumentLoader appDocumentLoader;

    private final MyKeywordEnricher myKeywordEnricher;

    private final LocalVectorStoreCache vectorStoreCache;

    private final String vectorCacheVersion;

    public AppVectorStoreConfig(
            AppDocumentLoader appDocumentLoader,
            MyKeywordEnricher myKeywordEnricher,
            LocalVectorStoreCache vectorStoreCache,
            @Value("${agent.rag.vector-cache-version:v1}") String vectorCacheVersion) {
        this.appDocumentLoader = appDocumentLoader;
        this.myKeywordEnricher = myKeywordEnricher;
        this.vectorStoreCache = vectorStoreCache;
        this.vectorCacheVersion = vectorCacheVersion;
    }

    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        String fingerprint = vectorCacheVersion + ":" + appDocumentLoader.fingerprintMarkdowns();
        if (vectorStoreCache.restore(simpleVectorStore, fingerprint)) {
            return simpleVectorStore;
        }

        log.info("[AgenticRAG][向量缓存] 未命中，开始构建知识库索引");
        // 加载文档
        List<Document> documentList = appDocumentLoader.loadMarkdowns();
        // 自主切分文档
        // List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);
        // 自动补充关键词元信息
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documentList);
        simpleVectorStore.add(enrichedDocuments);
        vectorStoreCache.save(simpleVectorStore, fingerprint);
        return simpleVectorStore;
    }
}
