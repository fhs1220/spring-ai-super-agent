package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Repository
public class FileAlignmentExperimentRepository
        implements AlignmentExperimentRepository {

    private static final Logger log = LoggerFactory.getLogger(
            FileAlignmentExperimentRepository.class);

    private final ObjectMapper objectMapper;

    private final Path directory;

    public FileAlignmentExperimentRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rl.alignment.experiment-directory:"
                    + "tmp/evaluation/alignment-experiments}")
            String directory) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<AlignmentExperiment> find(String experimentId) {
        validateId(experimentId);
        Path path = path(experimentId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    path.toFile(), AlignmentExperiment.class));
        } catch (IOException exception) {
            backupCorrupted(path);
            throw new IllegalStateException(
                    "Alignment experiment is corrupted: " + experimentId,
                    exception
            );
        }
    }

    @Override
    public synchronized AlignmentExperiment save(
            AlignmentExperiment experiment) {
        validateId(experiment.experimentId());
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(
                    directory, "alignment-experiment-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), experiment);
            moveAtomically(temporary, path(experiment.experimentId()));
            return experiment;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist alignment experiment", exception);
        }
    }

    private Path path(String experimentId) {
        return directory.resolve(experimentId + ".json");
    }

    private void validateId(String experimentId) {
        if (experimentId == null
                || !experimentId.matches("alignment-experiment-[a-f0-9]{16}")) {
            throw new IllegalArgumentException("Invalid experimentId");
        }
    }

    private void backupCorrupted(Path source) {
        Path backup = source.resolveSibling(
                source.getFileName() + ".corrupted");
        try {
            Files.move(source, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已把损坏的四臂实验清单备份到 {}", backup);
        } catch (IOException exception) {
            log.warn("备份损坏的四臂实验清单失败: {}", backup, exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
