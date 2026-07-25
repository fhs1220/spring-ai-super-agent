package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileRoutingPolicyDeploymentRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsDeploymentState() {
        Path stateFile = tempDir.resolve("deployment.json");
        RoutingPolicyDeploymentState state = new RoutingPolicyDeploymentState(
                new RoutingPolicyDeployment(
                        "routing-1",
                        RoutingPolicyRegistryService.BASELINE_VERSION,
                        RoutingPolicyMode.SHADOW,
                        0.1,
                        Instant.parse("2026-07-25T00:00:00Z"),
                        "initial"),
                List.of());

        repository(stateFile).save(state);

        assertThat(repository(stateFile).load()).contains(state);
    }

    @Test
    void recoversFromCorruptedStateFileWithoutFailing() throws Exception {
        Path stateFile = tempDir.resolve("deployment.json");
        Files.writeString(stateFile, "{not-json");

        Optional<RoutingPolicyDeploymentState> loaded = repository(stateFile).load();

        assertThat(loaded).isEmpty();
        assertThat(stateFile).doesNotExist();
        assertThat(tempDir.resolve("deployment.json.corrupted")).exists();
    }

    private FileRoutingPolicyDeploymentRepository repository(Path stateFile) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new FileRoutingPolicyDeploymentRepository(mapper, stateFile.toString());
    }
}
