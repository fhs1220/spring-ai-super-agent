package com.fhs.aiagent.rl.alignment;

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
public class FileAlignmentAutomationStateRepository
        implements AlignmentAutomationStateRepository {

    private static final Logger log = LoggerFactory.getLogger(
            FileAlignmentAutomationStateRepository.class);

    private final ObjectMapper objectMapper;

    private final Path stateFile;

    public FileAlignmentAutomationStateRepository(
            ObjectMapper objectMapper,
            @Value("${agent.rl.alignment.automation-state-file:"
                    + "tmp/agent-rl/alignment-automation.json}")
            String stateFile) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(stateFile).toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<AlignmentAutomationState> load() {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    stateFile.toFile(), AlignmentAutomationState.class));
        } catch (IOException exception) {
            log.warn("AI 评审自动化状态不可读，将备份后重建: {}",
                    stateFile, exception);
            backupCorruptedFile();
            return Optional.empty();
        }
    }

    @Override
    public synchronized AlignmentAutomationState save(
            AlignmentAutomationState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporary = Files.createTempFile(
                    stateFile.getParent(), "alignment-automation-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), state);
            moveAtomically(temporary, stateFile);
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist alignment automation state", exception);
        }
    }

    private void backupCorruptedFile() {
        Path backup = stateFile.resolveSibling(
                stateFile.getFileName() + ".corrupted");
        try {
            Files.move(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("已把损坏的 AI 评审自动化状态备份到 {}", backup);
        } catch (IOException exception) {
            log.warn("备份损坏的 AI 评审自动化状态失败: {}", backup, exception);
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
