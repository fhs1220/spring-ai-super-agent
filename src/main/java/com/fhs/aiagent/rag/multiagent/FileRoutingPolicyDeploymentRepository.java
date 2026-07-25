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
public class FileRoutingPolicyDeploymentRepository
        implements RoutingPolicyDeploymentRepository {

    private static final Logger log =
            LoggerFactory.getLogger(FileRoutingPolicyDeploymentRepository.class);

    private final ObjectMapper objectMapper;

    private final Path stateFile;

    public FileRoutingPolicyDeploymentRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rag.routing-policy.deployment-state-file:"
                    + "tmp/routing-policy/deployment.json}") String stateFile) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(stateFile).toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<RoutingPolicyDeploymentState> load() {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    stateFile.toFile(), RoutingPolicyDeploymentState.class));
        } catch (IOException exception) {
            // 状态文件损坏不应阻断应用启动：备份现场后重建初始状态。
            log.warn("路由发布状态文件不可读，将备份后重建初始状态: {}", stateFile, exception);
            backupCorruptedFile();
            return Optional.empty();
        }
    }

    private void backupCorruptedFile() {
        Path backup = stateFile.resolveSibling(
                stateFile.getFileName() + ".corrupted");
        try {
            Files.move(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已把损坏的路由发布状态文件备份到 {}", backup);
        } catch (IOException exception) {
            log.warn("备份损坏的路由发布状态文件失败: {}", backup, exception);
        }
    }

    @Override
    public synchronized RoutingPolicyDeploymentState save(
            RoutingPolicyDeploymentState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporary = Files.createTempFile(
                    stateFile.getParent(), "routing-policy-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), state);
            moveAtomically(temporary, stateFile);
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist routing policy deployment state", exception);
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
