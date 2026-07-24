package com.fhs.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 应用文档加载器
 */
@Component
@Slf4j
public class AppDocumentLoader {
    private final ResourcePatternResolver resourcePatternResolver;

    public AppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载多篇 Markdown 文档
     * @return
     */
    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = markdownResources();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                // 提取文档倒数第 3 和第 2 个字作为标签
                String status = filename.substring(filename.length() - 6, filename.length() - 4);
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        .withAdditionalMetadata("status", status)
                        .build();
                MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(markdownDocumentReader.get());
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }

    /**
     * 基于知识库文件名和原始内容生成稳定指纹，用于判断本地向量缓存是否过期。
     */
    public String fingerprintMarkdowns() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Resource resource : markdownResources()) {
                String filename = Objects.toString(resource.getFilename(), "");
                digest.update(filename.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream inputStream = resource.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        digest.update(buffer, 0, bytesRead);
                    }
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Markdown 知识库指纹计算失败", exception);
        }
    }

    private Resource[] markdownResources() throws IOException {
        Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
        Arrays.sort(resources, Comparator.comparing(
                resource -> Objects.toString(resource.getFilename(), "")));
        return resources;
    }
}
