package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemOneShadowSampleRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReturnsNewestSamplesFirst() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FileSystemOneShadowSampleRepository repository =
                new FileSystemOneShadowSampleRepository(
                        objectMapper, temporaryDirectory.toString(), 30);
        repository.save(sample("shadow-first", "2026-09-25T00:00:00Z"));
        repository.save(sample("shadow-second", "2026-09-26T00:00:00Z"));

        assertThat(repository.findRecent(10))
                .extracting(SystemOneShadowSample::sampleId)
                .containsExactly("shadow-second", "shadow-first");
    }

    @Test
    void rejectsUnsafeSampleId() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FileSystemOneShadowSampleRepository repository =
                new FileSystemOneShadowSampleRepository(
                        objectMapper, temporaryDirectory.toString(), 30);

        assertThatThrownBy(() -> repository.save(sample("../escape", "2026-09-26T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SystemOneShadowSample sample(String id, String capturedAt) {
        SystemOneShadowSample.ProviderObservation observation =
                new SystemOneShadowSample.ProviderObservation(
                        "SUCCESS", false, 0.1, false, 0.1,
                        10, "test", 10, 1, 0);
        return new SystemOneShadowSample(
                id,
                Instant.parse(capturedAt),
                "a".repeat(64),
                "",
                "RELATIONSHIP",
                false,
                observation,
                observation,
                List.of(),
                false,
                "AGREEMENT_CONTROL"
        );
    }
}
