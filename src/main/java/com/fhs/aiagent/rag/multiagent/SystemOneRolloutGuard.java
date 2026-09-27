package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** File-backed cross-process decision ledger and operational stop for one release version. */
final class SystemOneRolloutGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final String releaseVersion;
    private final Path statePath;
    private final Path eventsPath;
    private final Path lockPath;
    private final int consecutiveFailureLimit;
    private final int minimumSelected;
    private final double failureRateLimit;

    static SystemOneRolloutGuard disabled() {
        return new SystemOneRolloutGuard();
    }

    private SystemOneRolloutGuard() {
        enabled = false;
        releaseVersion = "";
        statePath = eventsPath = lockPath = null;
        consecutiveFailureLimit = minimumSelected = 0;
        failureRateLimit = 0;
    }

    SystemOneRolloutGuard(Path directory, String releaseVersion,
                          int consecutiveFailureLimit, int minimumSelected,
                          double failureRateLimit) {
        if (directory == null || !directory.isAbsolute()
                || releaseVersion == null || releaseVersion.isBlank()
                || consecutiveFailureLimit < 1 || minimumSelected < 1
                || !Double.isFinite(failureRateLimit)
                || failureRateLimit <= 0 || failureRateLimit > 1) {
            throw new IllegalArgumentException("Invalid System One rollout guard configuration");
        }
        this.enabled = true;
        this.releaseVersion = releaseVersion;
        this.consecutiveFailureLimit = consecutiveFailureLimit;
        this.minimumSelected = minimumSelected;
        this.failureRateLimit = failureRateLimit;
        try {
            Files.createDirectories(directory);
            String suffix = HexFormat.of().formatHex(sha256(
                    releaseVersion.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            statePath = directory.resolve("state-" + suffix + ".json");
            eventsPath = directory.resolve("events-" + suffix + ".jsonl");
            lockPath = directory.resolve("lock-" + suffix + ".lck");
            // Check storage before a live deployment can start. No user text is stored.
            try (FileChannel ignored = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                readState();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("System One rollout storage is unavailable", exception);
        }
    }

    boolean isPaused() {
        if (!enabled) return false;
        try {
            if (!Files.isDirectory(statePath.getParent())
                    || !Files.isWritable(statePath.getParent())
                    || !Files.isRegularFile(lockPath)
                    || !Files.isWritable(lockPath)) {
                return true;
            }
            State state = readState();
            return state.paused || state.pendingEvent;
        } catch (IOException | RuntimeException exception) {
            // An unreadable safety state must not permit candidate execution.
            return true;
        }
    }

    synchronized boolean record(SystemOneRoutingDeployment.Mode mode, boolean selected,
                   String status, String model, long latencyMs) {
        if (!enabled) return true;
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = channel.lock()) {
            State previous = readState();
            if (previous.paused || previous.pendingEvent) return false;
            boolean failed = selected && ("ADVICE_FALLBACK".equals(status)
                    || "EXPERT_FALLBACK".equals(status));
            long selectedCount = previous.selected + (selected ? 1 : 0);
            long failureCount = previous.failures + (failed ? 1 : 0);
            int consecutive = !selected ? previous.consecutiveFailures
                    : failed ? previous.consecutiveFailures + 1 : 0;
            String reason = consecutive >= consecutiveFailureLimit
                    ? "CONSECUTIVE_FAILURES"
                    : selectedCount >= minimumSelected
                    && (double) failureCount / selectedCount > failureRateLimit
                    ? "FAILURE_RATE" : "";
            State next = new State(previous.total + 1, selectedCount, failureCount,
                    consecutive, previous.safetyFallbacks
                    + ("SAFETY_FALLBACK".equals(status) ? 1 : 0),
                    !reason.isEmpty(), reason, false);
            ObjectNode event = MAPPER.createObjectNode();
            event.put("timestamp", Instant.now().toString());
            event.put("releaseVersion", releaseVersion);
            event.put("mode", mode.name());
            event.put("selected", selected);
            event.put("status", status);
            event.put("model", model == null ? "" : model);
            event.put("decisionLatencyMs", Math.max(0, latencyMs));
            event.put("autoPaused", next.paused);
            writeState(new State(next.total, next.selected, next.failures,
                    next.consecutiveFailures, next.safetyFallbacks,
                    next.paused, next.pauseReason, true));
            Files.writeString(eventsPath, MAPPER.writeValueAsString(event) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writeState(next);
            return !next.paused;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private State readState() throws IOException {
        if (!Files.exists(statePath)) {
            if (Files.exists(eventsPath) && Files.size(eventsPath) > 0) {
                throw new IOException("System One rollout state is missing after events were recorded");
            }
            return new State(0, 0, 0, 0, 0, false, "", false);
        }
        JsonNode node = MAPPER.readTree(Files.readAllBytes(statePath));
        if (node == null || !releaseVersion.equals(node.path("releaseVersion").asText())
                || !node.path("total").isNumber() || !node.path("selected").isNumber()
                || !node.path("failures").isNumber()
                || !node.path("consecutiveFailures").isNumber()
                || !node.path("safetyFallbacks").isNumber()
                || !node.path("paused").isBoolean()
                || !node.path("pendingEvent").isBoolean()) {
            throw new IOException("Malformed System One rollout state");
        }
        State state = new State(node.path("total").asLong(), node.path("selected").asLong(),
                node.path("failures").asLong(), node.path("consecutiveFailures").asInt(),
                node.path("safetyFallbacks").asLong(), node.path("paused").asBoolean(),
                node.path("pauseReason").asText(""), node.path("pendingEvent").asBoolean());
        if (state.total < 0 || state.selected < 0 || state.failures < 0
                || state.selected > state.total || state.failures > state.selected
                || state.consecutiveFailures < 0 || state.safetyFallbacks < 0
                || state.safetyFallbacks > state.selected) {
            throw new IOException("Invalid System One rollout counters");
        }
        return state;
    }

    private void writeState(State state) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("releaseVersion", releaseVersion);
        node.put("updatedAt", Instant.now().toString());
        node.put("total", state.total);
        node.put("selected", state.selected);
        node.put("failures", state.failures);
        node.put("consecutiveFailures", state.consecutiveFailures);
        node.put("safetyFallbacks", state.safetyFallbacks);
        node.put("paused", state.paused);
        node.put("pauseReason", state.pauseReason);
        node.put("pendingEvent", state.pendingEvent);
        Path temporary = Files.createTempFile(statePath.getParent(), "system-one-state-", ".tmp");
        try {
            Files.write(temporary, MAPPER.writeValueAsBytes(node));
            Files.move(temporary, statePath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record State(long total, long selected, long failures, int consecutiveFailures,
                         long safetyFallbacks, boolean paused, String pauseReason,
                         boolean pendingEvent) { }
}
