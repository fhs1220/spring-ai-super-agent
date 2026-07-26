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
public class FileProgressiveDeliveryAutomationRepository
        implements ProgressiveDeliveryAutomationRepository {

    private static final Logger log = LoggerFactory.getLogger(
            FileProgressiveDeliveryAutomationRepository.class);

    private final ObjectMapper objectMapper;

    private final Path stateFile;

    public FileProgressiveDeliveryAutomationRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rag.routing-policy.progressive-delivery"
                    + ".automation-state-file:"
                    + "tmp/routing-policy/progressive-delivery.json}")
            String stateFile) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(stateFile).toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<ProgressiveDeliveryAutomationState> load() {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    stateFile.toFile(),
                    ProgressiveDeliveryAutomationState.class
            ));
        } catch (IOException exception) {
            log.warn("渐进式自动发布状态不可读，将备份后重建: {}",
                    stateFile, exception);
            backupCorruptedFile();
            return Optional.empty();
        }
    }

    @Override
    public synchronized ProgressiveDeliveryAutomationState save(
            ProgressiveDeliveryAutomationState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporary = Files.createTempFile(
                    stateFile.getParent(),
                    "progressive-delivery-",
                    ".tmp"
            );
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), state);
            moveAtomically(temporary, stateFile);
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist progressive delivery state", exception);
        }
    }

    private void backupCorruptedFile() {
        Path backup = stateFile.resolveSibling(
                stateFile.getFileName() + ".corrupted");
        try {
            Files.move(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已把损坏的渐进式发布状态备份到 {}", backup);
        } catch (IOException exception) {
            log.warn("备份损坏的渐进式发布状态失败: {}", backup, exception);
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
