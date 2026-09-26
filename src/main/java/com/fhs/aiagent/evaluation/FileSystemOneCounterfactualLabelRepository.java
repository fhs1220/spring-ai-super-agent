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
        findBySampleId(label.sampleId()).ifPresent(previous -> {
            if (!previous.split().equals(label.split())) {
                throw new IllegalStateException("A persisted sample cannot change split");
            }
            if (label.reviews().size() < previous.reviews().size()
                    || !label.reviews().subList(0, previous.reviews().size()).equals(previous.reviews())) {
                throw new IllegalStateException("Review history is append-only");
            }
            if (!previous.reviews().isEmpty() && !java.util.Objects.equals(previous.evidence(), label.evidence())) {
                throw new IllegalStateException("Reviewed evidence is immutable");
            }
            validateEvidence(previous.evidence(), label.evidence());
        });
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
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getParent().equals(storageDirectory.resolve("development"))
                            || path.getParent().equals(storageDirectory.resolve("holdout")))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
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

    private void validateEvidence(SystemOneCounterfactualLabel.Evidence previous,
                                  SystemOneCounterfactualLabel.Evidence next) {
        if (previous == null) return;
        if (next == null || !java.util.Objects.equals(previous.question(), next.question())
                || !java.util.Objects.equals(previous.utilityPolicy(), next.utilityPolicy())
                || !previous.provenance().equals(next.provenance())
                || !java.util.Objects.equals(previous.observation(), next.observation())
                || next.attempts().size() < previous.attempts().size()) {
            throw new IllegalStateException("Checkpoint identity and attempts are immutable");
        }
        for (int i = 0; i < previous.attempts().size(); i++) {
            var oldAttempt = previous.attempts().get(i);
            var updated = next.attempts().get(i);
            if (oldAttempt.equals(updated)) continue;
            if (i != previous.attempts().size() - 1 || oldAttempt.finishedAt() != null
                    || !"PENDING".equals(oldAttempt.status()) || updated.finishedAt() == null
                    || !oldAttempt.attemptId().equals(updated.attemptId())
                    || !oldAttempt.stage().equals(updated.stage())
                    || !oldAttempt.startedAt().equals(updated.startedAt())) {
                throw new IllegalStateException("Completed stage attempts are immutable");
            }
        }
        if ((previous.single() != null && previous.single().succeeded()
                && !previous.single().equals(next.single()))
                || (previous.multi() != null && previous.multi().succeeded()
                && !previous.multi().equals(next.multi()))
                || (previous.judgment() != null && !previous.judgment().equals(next.judgment()))) {
            throw new IllegalStateException("Successful stage evidence is immutable");
        }
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
