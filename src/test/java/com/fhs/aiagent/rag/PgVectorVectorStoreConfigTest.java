package com.fhs.aiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;

@SpringBootTest
class PgVectorVectorStoreConfigTest {
    @Resource(name = "pgVectorVectorStore")
    private VectorStore pgVectorVectorStore;
    @Test
    void pgVectorVectorStore() {
        List<Document> documents = List.of(
                new Document("这个项目有什么用？智能体能帮你解答大部分问题", Map.of("meta1", "meta1")),
                new Document("程序员房皓森想上岸"),
                new Document("梦逝是我的网名", Map.of("meta2", "meta2")));
        // 添加文档
        pgVectorVectorStore.add(documents);
        // 相似度查询
        List<Document> results = pgVectorVectorStore.similaritySearch(SearchRequest.builder().query("怎么学编程啊").topK(3).build());
        Assertions.assertNotNull(results);

    }
}