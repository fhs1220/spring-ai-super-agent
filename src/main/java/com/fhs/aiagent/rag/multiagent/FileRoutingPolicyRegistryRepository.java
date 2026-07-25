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
import java.util.Optional;

@Repository
public class FileRoutingPolicyRegistryRepository
        implements RoutingPolicyRegistryRepository {

    private static final Logger log =
            LoggerFactory.getLogger(FileRoutingPolicyRegistryRepository.class);

    private final ObjectMapper objectMapper;

    private final Path stateFile;

    public FileRoutingPolicyRegistryRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rag.routing-policy.registry.state-file:"
                    + "tmp/routing-policy/registry.json}") String stateFile) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(stateFile).toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<RoutingPolicyRegistryState> load() {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    stateFile.toFile(), RoutingPolicyRegistryState.class));
        } catch (IOException exception) {
            log.warn("路由策略注册表不可读，将备份后重建: {}", stateFile, exception);
            backupCorruptedFile();
            return Optional.empty();
        }
    }

    @Override
    public synchronized RoutingPolicyRegistryState save(
            RoutingPolicyRegistryState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporary = Files.createTempFile(
                    stateFile.getParent(), "routing-policy-registry-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), state);
            moveAtomically(temporary, stateFile);
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist routing policy registry", exception);
        }
    }

    private void backupCorruptedFile() {
        Path backup = stateFile.resolveSibling(
                stateFile.getFileName() + ".corrupted");
        try {
            Files.move(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已把损坏的路由策略注册表备份到 {}", backup);
        } catch (IOException exception) {
            log.warn("备份损坏的路由策略注册表失败: {}", backup, exception);
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
