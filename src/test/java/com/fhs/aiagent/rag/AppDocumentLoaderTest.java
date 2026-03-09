package com.fhs.aiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppDocumentLoaderTest {

    @Resource
    private AppDocumentLoader loveAppDocumentLoader;
    @Test
    void loadMarkdowns() {
    }
}