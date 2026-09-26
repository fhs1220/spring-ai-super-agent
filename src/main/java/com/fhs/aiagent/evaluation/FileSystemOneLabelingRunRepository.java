package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Repository
public class FileSystemOneLabelingRunRepository implements SystemOneLabelingRunRepository {
    private final ObjectMapper objectMapper;
    private final Path directory;

    public FileSystemOneLabelingRunRepository(
            ObjectMapper objectMapper,
            @Value("${agent.evaluation.system-one.labeling.storage-directory:tmp/system-one-labels}")
            String storageDirectory) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(storageDirectory).toAbsolutePath().normalize().resolve("runs");
    }

    @Override
    public synchronized void save(SystemOneLabelingJobService.RunSnapshot snapshot) {
        if (snapshot.runId() == null || !snapshot.runId().matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid labeling runId");
        }
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "run-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            Path target = directory.resolve(snapshot.runId() + ".json");
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist labeling run", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Leftover .tmp files are ignored; preserve the original failure.
                }
            }
        }
    }

    @Override
    public synchronized List<SystemOneLabelingJobService.RunSnapshot> findAll() {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .sorted(Comparator.comparing(SystemOneLabelingJobService.RunSnapshot::startedAt))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read labeling runs", exception);
        }
    }

    private SystemOneLabelingJobService.RunSnapshot read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), SystemOneLabelingJobService.RunSnapshot.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read labeling run " + path.getFileName(), exception);
        }
    }
}
