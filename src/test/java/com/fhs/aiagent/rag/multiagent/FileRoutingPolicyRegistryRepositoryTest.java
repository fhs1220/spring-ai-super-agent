package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileRoutingPolicyRegistryRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsRegistryState() {
        Path stateFile = temporaryDirectory.resolve("registry.json");
        RoutingPolicyRegistryState state = new RoutingPolicyRegistryState(
                List.of(artifact()),
                Instant.parse("2026-07-25T00:00:00Z")
        );

        repository(stateFile).save(state);

        assertThat(repository(stateFile).load()).contains(state);
    }

    @Test
    void backsUpCorruptedRegistryAndReturnsEmpty() throws Exception {
        Path stateFile = temporaryDirectory.resolve("registry.json");
        Files.writeString(stateFile, "{broken-json");

        assertThat(repository(stateFile).load()).isEmpty();
        assertThat(stateFile).doesNotExist();
        assertThat(temporaryDirectory.resolve("registry.json.corrupted")).exists();
    }

    private RoutingPolicyArtifact artifact() {
        return new RoutingPolicyArtifact(
                "routing-policy-test",
                RoutingPolicyArtifactStatus.VALIDATED,
                "test-algorithm",
                "test-model",
                java.util.Map.of("lift", "0.03"),
                "abcdef",
                4,
                null,
                RoutingPolicyRegistryService.BASELINE_VERSION,
                Instant.parse("2026-07-25T00:00:00Z"),
                "validated"
        );
    }

    private FileRoutingPolicyRegistryRepository repository(Path stateFile) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new FileRoutingPolicyRegistryRepository(
                mapper,
                stateFile.toString()
        );
    }
}
