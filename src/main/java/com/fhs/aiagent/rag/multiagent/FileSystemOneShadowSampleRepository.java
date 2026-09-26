package com.fhs.aiagent.rag.multiagent;

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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Repository
public class FileSystemOneShadowSampleRepository implements SystemOneShadowSampleRepository {

    private static final Logger log = LoggerFactory.getLogger(
            FileSystemOneShadowSampleRepository.class);

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;
    private final int maximumSamples;

    public FileSystemOneShadowSampleRepository(
            ObjectMapper objectMapper,
            @Value("${agent.decision.system-one.comparison.storage-directory:"
                    + "tmp/system-one-shadow}") String storageDirectory,
            @Value("${agent.decision.system-one.comparison.maximum-samples:1000}")
            int maximumSamples) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.maximumSamples = Math.max(30, maximumSamples);
    }

    @Override
    public synchronized SystemOneShadowSample save(SystemOneShadowSample sample) {
        validateId(sample.sampleId());
        try {
            Files.createDirectories(storageDirectory);
            Path target = storageDirectory.resolve(sample.sampleId() + ".json");
            Path temporary = Files.createTempFile(storageDirectory, "shadow-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), sample);
            moveAtomically(temporary, target);
            prune();
            return sample;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist System One shadow sample " + sample.sampleId(), exception);
        }
    }

    @Override
    public synchronized List<SystemOneShadowSample> findRecent(int limit) {
        if (!Files.isDirectory(storageDirectory)) return List.of();
        int boundedLimit = Math.max(1, Math.min(maximumSamples, limit));
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(
                            SystemOneShadowSample::capturedAt).reversed())
                    .limit(boundedLimit)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list System One shadow samples", exception);
        }
    }

    private SystemOneShadowSample read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), SystemOneShadowSample.class);
        } catch (IOException exception) {
            log.warn("Skipping unreadable System One shadow sample: {}", path, exception);
            return null;
        }
    }

    private void prune() throws IOException {
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            List<Path> samples = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(this::modifiedAt).reversed())
                    .toList();
            for (int index = maximumSamples; index < samples.size(); index++) {
                Files.deleteIfExists(samples.get(index));
            }
        }
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private void validateId(String sampleId) {
        if (sampleId == null || !sampleId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid sampleId");
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
