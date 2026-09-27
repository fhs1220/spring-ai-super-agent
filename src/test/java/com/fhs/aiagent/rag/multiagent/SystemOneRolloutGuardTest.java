package com.fhs.aiagent.rag.multiagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemOneRolloutGuardTest {

    @TempDir Path directory;

    @Test
    void pausesAcrossInstancesAfterConsecutiveFailuresEvenWithControlsBetweenThem() throws Exception {
        var first = new SystemOneRolloutGuard(directory, "release-1", 3, 20, 0.10);
        assertThat(first.record(SystemOneRoutingDeployment.Mode.CANARY,
                true, "ADVICE_FALLBACK", "", 100)).isTrue();
        assertThat(first.record(SystemOneRoutingDeployment.Mode.CANARY,
                false, "CONTROL", "", 0)).isTrue();
        assertThat(first.record(SystemOneRoutingDeployment.Mode.CANARY,
                true, "EXPERT_FALLBACK", "model", 110)).isTrue();
        assertThat(first.record(SystemOneRoutingDeployment.Mode.CANARY,
                true, "ADVICE_FALLBACK", "", 120)).isFalse();

        var second = new SystemOneRolloutGuard(directory, "release-1", 3, 20, 0.10);
        assertThat(second.isPaused()).isTrue();
        assertThat(Files.readString(singleFile("state-")))
                .contains("CONSECUTIVE_FAILURES", "\"selected\":3", "\"total\":4");
        assertThat(Files.readString(singleFile("events-")))
                .doesNotContain("用户原文", "question")
                .contains("ADVICE_FALLBACK", "EXPERT_FALLBACK");
    }

    @Test
    void pausesWhenFailureRateExceedsLimitAndFailsClosedOnMissingState() throws Exception {
        var guard = new SystemOneRolloutGuard(directory, "release-2", 10, 4, 0.25);
        assertThat(guard.record(SystemOneRoutingDeployment.Mode.ACTIVE,
                true, "ADVICE_FALLBACK", "", 10)).isTrue();
        assertThat(guard.record(SystemOneRoutingDeployment.Mode.ACTIVE,
                true, "APPLIED", "model", 10)).isTrue();
        assertThat(guard.record(SystemOneRoutingDeployment.Mode.ACTIVE,
                true, "APPLIED", "model", 10)).isTrue();
        assertThat(guard.record(SystemOneRoutingDeployment.Mode.ACTIVE,
                true, "ADVICE_FALLBACK", "", 10)).isFalse();
        assertThat(guard.isPaused()).isTrue();
        assertThat(Files.readString(singleFile("state-"))).contains("FAILURE_RATE");

        Files.delete(singleFile("state-"));
        assertThat(guard.isPaused()).isTrue();
    }

    @Test
    void incompleteLedgerWriteAndMissingLockFailClosed() throws Exception {
        var guard = new SystemOneRolloutGuard(directory, "release-3", 3, 20, 0.10);
        assertThat(guard.record(SystemOneRoutingDeployment.Mode.CANARY,
                true, "APPLIED", "model", 5)).isTrue();
        Path state = singleFile("state-");
        Files.writeString(state, Files.readString(state)
                .replace("\"pendingEvent\":false", "\"pendingEvent\":true"));
        assertThat(guard.isPaused()).isTrue();
        Files.delete(singleFile("lock-"));
        assertThat(guard.isPaused()).isTrue();
    }

    @Test
    void liveLedgerNeedsAnExplicitAbsoluteDirectory() {
        assertThatThrownBy(() -> new SystemOneRolloutGuard(
                Path.of("tmp/system-one-rollout"), "release-4", 3, 20, 0.10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path singleFile(String prefix) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst().orElseThrow();
        }
    }
}
