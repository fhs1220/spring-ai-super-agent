package com.fhs.aiagent.rl.alignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class FileAlignmentAssessmentRepository implements AlignmentAssessmentRepository {

    private static final Logger log =
            LoggerFactory.getLogger(FileAlignmentAssessmentRepository.class);

    private final ObjectMapper objectMapper;

    private final Path storageDirectory;

    public FileAlignmentAssessmentRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rl.alignment.storage-directory:tmp/agent-rl/alignment-assessments}")
            String storageDirectory) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create alignment assessment directory", exception);
        }
    }

    @Override
    public synchronized AutomatedAlignmentAssessment save(
            AutomatedAlignmentAssessment assessment) {
        validateTrajectoryId(assessment.trajectoryId());
        Path target = path(assessment.trajectoryId());
        try {
            Path temporary = Files.createTempFile(storageDirectory, assessment.trajectoryId(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), assessment);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return assessment;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist alignment assessment " + assessment.trajectoryId(),
                    exception);
        }
    }

    @Override
    public synchronized Optional<AutomatedAlignmentAssessment> findByTrajectoryId(
            String trajectoryId) {
        validateTrajectoryId(trajectoryId);
        Path target = path(trajectoryId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return readOrQuarantine(target);
    }

    @Override
    public synchronized List<AutomatedAlignmentAssessment> findAll() {
        if (!Files.exists(storageDirectory)) {
            return List.of();
        }
        List<AutomatedAlignmentAssessment> assessments = new ArrayList<>();
        try (var paths = Files.list(storageDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readOrQuarantine)
                    .flatMap(Optional::stream)
                    .forEach(assessments::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list alignment assessments", exception);
        }
        return assessments.stream()
                .sorted(Comparator.comparing(
                        AutomatedAlignmentAssessment::evaluatedAt,
                        Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public String namespace() {
        return storageDirectory.getFileName().toString();
    }

    private Optional<AutomatedAlignmentAssessment> readOrQuarantine(Path target) {
        try {
            return Optional.of(objectMapper.readValue(
                    target.toFile(), AutomatedAlignmentAssessment.class));
        } catch (IOException exception) {
            Path backup = target.resolveSibling(
                    target.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
            try {
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException backupFailure) {
                exception.addSuppressed(backupFailure);
            }
            log.warn("Corrupt alignment assessment was quarantined as {}: {}",
                    backup.getFileName(), exception.getMessage());
            return Optional.empty();
        }
    }

    private Path path(String trajectoryId) {
        return storageDirectory.resolve(trajectoryId + ".json");
    }

    private void validateTrajectoryId(String trajectoryId) {
        if (trajectoryId == null || !trajectoryId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid trajectoryId");
        }
    }
}
