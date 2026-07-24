package com.fhs.aiagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LocalVectorStoreCacheTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAtomicallyAndRestoresMatchingFingerprint() throws Exception {
        LocalVectorStoreCache cache = new LocalVectorStoreCache(true, temporaryDirectory);
        SimpleVectorStore sourceStore = mock(SimpleVectorStore.class);
        doAnswer(invocation -> {
            File target = invocation.getArgument(0);
            Files.writeString(target.toPath(), "{\"store\":{}}");
            return null;
        }).when(sourceStore).save(any(File.class));

        cache.save(sourceStore, "fingerprint-v1");

        SimpleVectorStore restoredStore = mock(SimpleVectorStore.class);
        assertThat(cache.restore(restoredStore, "fingerprint-v1")).isTrue();
        verify(restoredStore).load(
                temporaryDirectory.resolve("love-app-vector-store.json").toFile());
        assertThat(Files.readString(
                temporaryDirectory.resolve("love-app-vector-store.sha256")))
                .isEqualTo("fingerprint-v1");
    }

    @Test
    void rejectsCacheWhenKnowledgeFingerprintChanges() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("love-app-vector-store.json"),
                "{\"store\":{}}");
        Files.writeString(
                temporaryDirectory.resolve("love-app-vector-store.sha256"),
                "old-fingerprint");
        LocalVectorStoreCache cache = new LocalVectorStoreCache(true, temporaryDirectory);
        SimpleVectorStore vectorStore = mock(SimpleVectorStore.class);

        assertThat(cache.restore(vectorStore, "new-fingerprint")).isFalse();

        verify(vectorStore, never()).load(any(File.class));
    }
}
