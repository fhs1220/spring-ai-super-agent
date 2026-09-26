package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemOneCounterfactualLabelRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsLabelsInTheirSplitAndReturnsNewestFirst() {
        FileSystemOneCounterfactualLabelRepository repository = repository();
        repository.save(label("label-old", "DEVELOPMENT", "2026-09-25T00:00:00Z"));
        repository.save(label("label-new", "HOLDOUT", "2026-09-26T00:00:00Z"));

        assertThat(repository.findBySampleId("label-old")).isPresent();
        assertThat(repository.findAll())
                .extracting(SystemOneCounterfactualLabel::sampleId)
                .containsExactly("label-new", "label-old");
        assertThat(temporaryDirectory.resolve("development/label-old.json")).exists();
        assertThat(temporaryDirectory.resolve("holdout/label-new.json")).exists();
    }

    @Test
    void rejectsUnsafeSampleId() {
        assertThatThrownBy(() -> repository().save(
                label("../escape", "DEVELOPMENT", "2026-09-26T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownSplit() {
        assertThatThrownBy(() -> repository().save(
                label("safe-id", "../escape", "2026-09-26T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split");
    }

    private FileSystemOneCounterfactualLabelRepository repository() {
        return new FileSystemOneCounterfactualLabelRepository(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
    }

    private SystemOneCounterfactualLabel label(String id, String split, String at) {
        return new SystemOneCounterfactualLabel(
                id, "run", Instant.parse(at), split, "COMPLETED",
                "a".repeat(64), List.of(), null, null,
                0.9, "reason", "judge-v1", 0.7, 0.8, 0.1,
                true, false, false, "");
    }
}
