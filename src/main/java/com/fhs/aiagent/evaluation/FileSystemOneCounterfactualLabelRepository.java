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
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public class FileSystemOneCounterfactualLabelRepository
        implements SystemOneCounterfactualLabelRepository {

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;

    public FileSystemOneCounterfactualLabelRepository(
            ObjectMapper objectMapper,
            @Value("${agent.evaluation.system-one.labeling.storage-directory:"
                    + "tmp/system-one-labels}") String storageDirectory) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public synchronized SystemOneCounterfactualLabel save(
            SystemOneCounterfactualLabel label) {
        validateId(label.sampleId());
        String splitDirectory = validateSplit(label.split());
        try {
            Path directory = storageDirectory.resolve(splitDirectory);
            Files.createDirectories(directory);
            Path target = directory.resolve(label.sampleId() + ".json");
            Path temporary = Files.createTempFile(directory, "label-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), label);
            moveAtomically(temporary, target);
            return label;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist counterfactual label " + label.sampleId(), exception);
        }
    }

    @Override
    public synchronized Optional<SystemOneCounterfactualLabel> findBySampleId(
            String sampleId) {
        validateId(sampleId);
        for (String split : List.of("development", "holdout")) {
            Path target = storageDirectory.resolve(split).resolve(sampleId + ".json");
            if (Files.isRegularFile(target)) return Optional.of(read(target));
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<SystemOneCounterfactualLabel> findAll() {
        if (!Files.isDirectory(storageDirectory)) return List.of();
        try (Stream<Path> paths = Files.walk(storageDirectory, 2)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .sorted(Comparator.comparing(
                            SystemOneCounterfactualLabel::labeledAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list counterfactual labels", exception);
        }
    }

    private SystemOneCounterfactualLabel read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), SystemOneCounterfactualLabel.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read counterfactual label " + path, exception);
        }
    }

    private void validateId(String sampleId) {
        if (sampleId == null || !sampleId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid sampleId");
        }
    }

    private String validateSplit(String split) {
        if ("DEVELOPMENT".equals(split)) return "development";
        if ("HOLDOUT".equals(split)) return "holdout";
        throw new IllegalArgumentException("Invalid label split");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
