package com.fhs.aiagent.rl.alignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FileAlignmentAutomationStateRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStateAndQuarantinesCorruption() throws Exception {
        Path stateFile = temporaryDirectory.resolve("automation.json");
        FileAlignmentAutomationStateRepository repository =
                new FileAlignmentAutomationStateRepository(
                        new ObjectMapper().findAndRegisterModules(),
                        stateFile.toString()
                );
        AlignmentAutomationState expected = new AlignmentAutomationState(
                true,
                false,
                LocalDate.parse("2026-07-28"),
                12,
                2,
                Instant.parse("2026-07-28T12:30:00Z"),
                Instant.parse("2026-07-28T12:00:00Z"),
                Instant.parse("2026-07-28T12:01:00Z"),
                4,
                1,
                "MANUAL",
                "partial panel",
                "operator pause",
                7
        );

        repository.save(expected);

        assertThat(repository.load()).contains(expected);

        Files.writeString(stateFile, "{not-json");

        assertThat(repository.load()).isEmpty();
        assertThat(stateFile.resolveSibling(
                "automation.json.corrupted")).isRegularFile();
    }
}
