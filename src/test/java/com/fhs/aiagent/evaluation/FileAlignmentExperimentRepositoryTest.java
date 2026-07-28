package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileAlignmentExperimentRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPersistsAndLoadsExperiment() {
        FileAlignmentExperimentRepository repository = repository();
        AlignmentExperiment experiment = experiment();

        repository.save(experiment);

        assertThat(repository.find(experiment.experimentId()))
                .contains(experiment);
    }

    @Test
    void quarantinesCorruptedExperimentFile() throws Exception {
        FileAlignmentExperimentRepository repository = repository();
        Path file = temporaryDirectory.resolve(
                experiment().experimentId() + ".json");
        Files.writeString(file, "{broken");

        assertThatThrownBy(() -> repository.find(experiment().experimentId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupted");
        assertThat(file).doesNotExist();
        assertThat(Path.of(file + ".corrupted")).isRegularFile();
    }

    private FileAlignmentExperimentRepository repository() {
        return new FileAlignmentExperimentRepository(
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.toString()
        );
    }

    private AlignmentExperiment experiment() {
        String id = "alignment-experiment-" + "a".repeat(16);
        Instant now = Instant.parse("2026-07-28T08:00:00Z");
        return new AlignmentExperiment(
                id,
                "a".repeat(64),
                RagAbEvaluationService.BENCHMARK_VERSION,
                "b".repeat(64),
                36,
                now,
                now,
                null,
                AlignmentExperiment.Status.PLANNED,
                List.of(),
                "",
                "",
                ""
        );
    }
}
