package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileProgressiveDeliveryAutomationRepositoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void persistsAndReloadsControlAndAuditHistory() {
        Path stateFile = tempDirectory.resolve("automation.json");
        FileProgressiveDeliveryAutomationRepository repository =
                repository(stateFile);
        ProgressiveDeliveryAutomationState.ExecutionAudit audit =
                new ProgressiveDeliveryAutomationState.ExecutionAudit(
                        "execution-1",
                        "test",
                        "deployment-1",
                        "deployment-2",
                        RoutingPolicyMode.SHADOW,
                        0,
                        RoutingPolicyMode.CANARY,
                        0.05,
                        "artifact-1",
                        ProgressiveDeliveryAutomationState.ExecutionOutcome.APPLIED,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        "applied"
                );
        ProgressiveDeliveryAutomationState expected =
                new ProgressiveDeliveryAutomationState(
                        true,
                        false,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        "approved",
                        List.of(audit)
                );

        repository.save(expected);

        assertThat(repository(stateFile).load()).contains(expected);
    }

    @Test
    void backsUpCorruptedStateAndAllowsSafeRebuild() throws Exception {
        Path stateFile = tempDirectory.resolve("automation.json");
        Files.writeString(stateFile, "{not-json");
        FileProgressiveDeliveryAutomationRepository repository =
                repository(stateFile);

        assertThat(repository.load()).isEmpty();
        assertThat(Files.exists(
                tempDirectory.resolve("automation.json.corrupted"))).isTrue();
        assertThat(Files.exists(stateFile)).isFalse();

        ProgressiveDeliveryAutomationState rebuilt =
                new ProgressiveDeliveryAutomationState(
                        false,
                        false,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        "safe default",
                        List.of()
                );
        repository.save(rebuilt);

        assertThat(repository.load()).contains(rebuilt);
    }

    private FileProgressiveDeliveryAutomationRepository repository(
            Path stateFile) {
        return new FileProgressiveDeliveryAutomationRepository(
                new ObjectMapper().findAndRegisterModules(),
                stateFile.toString()
        );
    }
}
