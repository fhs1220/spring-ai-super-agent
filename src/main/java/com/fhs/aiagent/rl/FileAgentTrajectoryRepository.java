package com.fhs.aiagent.rl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.model.AgentTrajectory;
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
public class FileAgentTrajectoryRepository implements AgentTrajectoryRepository {

    private final ObjectMapper objectMapper;

    private final Path storageDirectory;

    public FileAgentTrajectoryRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rl.storage-directory:tmp/agent-rl/trajectories}") String storageDirectory) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public synchronized AgentTrajectory save(AgentTrajectory trajectory) {
        try {
            Files.createDirectories(storageDirectory);
            Path target = trajectoryPath(trajectory.trajectoryId());
            Path temporary = Files.createTempFile(storageDirectory, trajectory.trajectoryId(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), trajectory);
            moveAtomically(temporary, target);
            return trajectory;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist agent trajectory " + trajectory.trajectoryId(), exception);
        }
    }

    @Override
    public synchronized Optional<AgentTrajectory> findById(String trajectoryId) {
        Path path = trajectoryPath(trajectoryId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), AgentTrajectory.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read agent trajectory " + trajectoryId, exception);
        }
    }

    @Override
    public synchronized List<AgentTrajectory> findAll() {
        if (!Files.isDirectory(storageDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readTrajectory)
                    .sorted(Comparator.comparing(AgentTrajectory::startedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list agent trajectories", exception);
        }
    }

    private AgentTrajectory readTrajectory(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), AgentTrajectory.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read agent trajectory " + path.getFileName(), exception);
        }
    }

    private Path trajectoryPath(String trajectoryId) {
        if (trajectoryId == null || !trajectoryId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid trajectoryId");
        }
        return storageDirectory.resolve(trajectoryId + ".json");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
