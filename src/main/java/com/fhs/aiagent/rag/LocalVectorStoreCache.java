package com.fhs.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * SimpleVectorStore 的本地持久化缓存。
 */
@Component
@Slf4j
public class LocalVectorStoreCache {

    private static final String INDEX_FILENAME = "love-app-vector-store.json";

    private static final String FINGERPRINT_FILENAME = "love-app-vector-store.sha256";

    private final boolean enabled;

    private final Path cacheDirectory;

    @Autowired
    public LocalVectorStoreCache(
            @Value("${agent.rag.vector-cache-enabled:true}") boolean enabled,
            @Value("${agent.rag.vector-cache-directory:tmp/rag/vector-store}") String cacheDirectory) {
        this(enabled, Path.of(cacheDirectory));
    }

    LocalVectorStoreCache(boolean enabled, Path cacheDirectory) {
        this.enabled = enabled;
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
    }

    public boolean restore(SimpleVectorStore vectorStore, String fingerprint) {
        if (!enabled) {
            return false;
        }
        Path indexPath = cacheDirectory.resolve(INDEX_FILENAME);
        Path fingerprintPath = cacheDirectory.resolve(FINGERPRINT_FILENAME);
        if (!Files.isRegularFile(indexPath) || !Files.isRegularFile(fingerprintPath)) {
            return false;
        }
        try {
            String cachedFingerprint = Files.readString(fingerprintPath, StandardCharsets.UTF_8).trim();
            if (!fingerprint.equals(cachedFingerprint)) {
                log.info("[AgenticRAG][向量缓存] 知识库指纹已变化，重新构建索引");
                return false;
            }
            vectorStore.load(indexPath.toFile());
            log.info("[AgenticRAG][向量缓存] 已加载本地索引: {}", indexPath);
            return true;
        } catch (IOException | RuntimeException exception) {
            log.warn("[AgenticRAG][向量缓存] 缓存读取失败，将重新构建: {}", exception.getMessage());
            return false;
        }
    }

    public void save(SimpleVectorStore vectorStore, String fingerprint) {
        if (!enabled) {
            return;
        }
        Path temporaryIndex = null;
        Path temporaryFingerprint = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporaryIndex = Files.createTempFile(cacheDirectory, "vector-store-", ".json.tmp");
            temporaryFingerprint = Files.createTempFile(cacheDirectory, "vector-store-", ".sha256.tmp");
            vectorStore.save(temporaryIndex.toFile());
            Files.writeString(
                    temporaryFingerprint,
                    fingerprint,
                    StandardCharsets.UTF_8
            );
            moveAtomically(temporaryIndex, cacheDirectory.resolve(INDEX_FILENAME));
            temporaryIndex = null;
            moveAtomically(temporaryFingerprint, cacheDirectory.resolve(FINGERPRINT_FILENAME));
            temporaryFingerprint = null;
            log.info("[AgenticRAG][向量缓存] 已保存本地索引: {}",
                    cacheDirectory.resolve(INDEX_FILENAME));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("本地向量缓存保存失败", exception);
        } finally {
            deleteTemporaryFile(temporaryIndex);
            deleteTemporaryFile(temporaryFingerprint);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.debug("临时向量缓存清理失败: {}", path, exception);
        }
    }
}
