package com.fhs.aiagent.rl.alignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileAlignmentAssessmentRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAssessmentsAndQuarantinesCorruptStateWithoutBlockingStartup()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileAlignmentAssessmentRepository repository =
                new FileAlignmentAssessmentRepository(
                        objectMapper, temporaryDirectory.toString());
        AutomatedAlignmentAssessment assessment = assessment("trajectory-1");
        repository.save(assessment);

        assertThat(repository.findByTrajectoryId("trajectory-1")).contains(assessment);

        Files.writeString(temporaryDirectory.resolve("trajectory-corrupt.json"), "{broken");

        assertThat(repository.findAll()).containsExactly(assessment);
        try (var paths = Files.list(temporaryDirectory)) {
            assertThat(paths
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith(
                            "trajectory-corrupt.json.corrupt-"))).isTrue();
        }
    }

    private AutomatedAlignmentAssessment assessment(String trajectoryId) {
        return new AutomatedAlignmentAssessment(
                trajectoryId,
                "fingerprint",
                "policy-v1",
                Instant.parse("2026-07-28T00:00:00Z"),
                SupervisionLabel.PSEUDO_LABELED,
                TrainingDecision.POSITIVE,
                0.9,
                0.9,
                0.9,
                0.9,
                0.9,
                4,
                List.of(),
                List.of("通过"),
                false
        );
    }
}
