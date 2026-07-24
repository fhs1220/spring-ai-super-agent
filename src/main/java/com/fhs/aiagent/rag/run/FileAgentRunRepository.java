package com.fhs.aiagent.rag.run;

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
public class FileAgentRunRepository implements AgentRunRepository {

    private final ObjectMapper objectMapper;

    private final Path storageDirectory;

    public FileAgentRunRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rag.runtime.storage-directory:tmp/agent-runs}") String storageDirectory) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public synchronized DurableAgentRun save(DurableAgentRun run) {
        try {
            Files.createDirectories(storageDirectory);
            Path target = runPath(run.runId());
            Path temporary = Files.createTempFile(storageDirectory, run.runId(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), run);
            moveAtomically(temporary, target);
            return run;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist agent run " + run.runId(), exception);
        }
    }

    @Override
    public synchronized Optional<DurableAgentRun> findById(String runId) {
        Path path = runPath(runId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(read(path));
    }

    @Override
    public synchronized List<DurableAgentRun> findAll() {
        if (!Files.isDirectory(storageDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .sorted(Comparator.comparing(DurableAgentRun::updatedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list durable agent runs", exception);
        }
    }

    private DurableAgentRun read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), DurableAgentRun.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read agent run " + path.getFileName(), exception);
        }
    }

    private Path runPath(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new IllegalArgumentException("Invalid runId");
        }
        return storageDirectory.resolve(runId + ".json");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
