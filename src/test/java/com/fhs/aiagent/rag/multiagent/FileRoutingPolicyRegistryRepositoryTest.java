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

    @Test
    void migratesSchemaV1ArtifactToGlobalFrozenRule() throws Exception {
        Path stateFile = temporaryDirectory.resolve("registry-v1.json");
        Files.writeString(stateFile, """
                {
                  "artifacts": [{
                    "version": "routing-policy-v1",
                    "status": "VALIDATED",
                    "algorithm": "trajectory-utility-global-policy-v1",
                    "upstreamModel": "qwen-test",
                    "parameters": {},
                    "trainingDataFingerprint": "abcdef",
                    "trainingSampleCount": 4,
                    "offlineEvaluation": {
                      "balancedEvidence": true,
                      "observedTrajectoryCount": 4,
                      "minimumSamplesPerMode": 2,
                      "singleAgent": {
                        "sampleCount": 2,
                        "successfulCount": 2,
                        "usageMeasuredSamples": 2,
                        "averageReward": 0.6,
                        "averageCostCny": 0.001,
                        "averageLatencyMs": 500,
                        "utility": 0.6
                      },
                      "multiAgent": {
                        "sampleCount": 2,
                        "successfulCount": 2,
                        "usageMeasuredSamples": 2,
                        "averageReward": 0.9,
                        "averageCostCny": 0.003,
                        "averageLatencyMs": 800,
                        "utility": 0.9
                      },
                      "multiAgentUtilityLift": 0.3,
                      "recommendedMode": "MULTI_AGENT",
                      "validationPassed": true,
                      "validationFailures": []
                    },
                    "parentVersion": "routing-policy-baseline-v1",
                    "createdAt": "2026-07-25T00:00:00Z",
                    "validationReason": "legacy validated"
                  }],
                  "updatedAt": "2026-07-25T00:00:00Z"
                }
                """);

        RoutingPolicyArtifact migrated = repository(stateFile)
                .load().orElseThrow().artifacts().getFirst();

        assertThat(migrated.schemaVersion()).isEqualTo(1);
        assertThat(migrated.globalRule().deployable()).isTrue();
        assertThat(migrated.globalRule().recommendedMode()).isEqualTo("MULTI_AGENT");
        assertThat(migrated.contextualRules()).isEmpty();
    }

    private RoutingPolicyArtifact artifact() {
        return new RoutingPolicyArtifact(
                2,
                "routing-policy-test",
                RoutingPolicyArtifactStatus.VALIDATED,
                "test-algorithm",
                "test-model",
                java.util.Map.of("lift", "0.03"),
                "abcdef",
                4,
                null,
                null,
                java.util.Map.of(),
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
