package com.fhs.aiagent.rl.alignment;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlignmentAutomationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void enforcesSharedDailyBudgetForManualBatches() {
        AiJudgePanelService panel = mock(AiJudgePanelService.class);
        when(panel.workload()).thenReturn(
                new AiJudgePanelService.AssessmentWorkload(10, 0, 10));
        when(panel.assessPending(2)).thenReturn(batch(2, 0));
        InMemoryStateRepository states = new InMemoryStateRepository();
        AlignmentAutomationService service = new AlignmentAutomationService(
                panel,
                states,
                false,
                10,
                2,
                3,
                Duration.ofMinutes(30),
                FIXED_CLOCK
        );

        AlignmentAutomationService.AutomationRunResult first =
                service.runManual(10);
        AlignmentAutomationService.AutomationRunResult second =
                service.runManual(10);

        assertThat(first.attempted()).isTrue();
        assertThat(first.completed()).isTrue();
        assertThat(first.status().remainingToday()).isZero();
        assertThat(first.status().estimatedJudgeCallsToday()).isEqualTo(8);
        assertThat(second.attempted()).isFalse();
        assertThat(second.reason()).contains("额度");
        assertThat(second.status().mode()).isEqualTo("DAILY_LIMIT");
        verify(panel).assessPending(2);
        assertThat(states.state).isNotNull();
    }

    @Test
    void opensPersistentCooldownAfterConsecutiveBatchFailures() {
        AiJudgePanelService panel = mock(AiJudgePanelService.class);
        when(panel.workload()).thenReturn(
                new AiJudgePanelService.AssessmentWorkload(10, 0, 10));
        when(panel.assessPending(1))
                .thenThrow(new IllegalStateException("judge unavailable"));
        AlignmentAutomationService service = new AlignmentAutomationService(
                panel,
                new InMemoryStateRepository(),
                true,
                10,
                50,
                2,
                Duration.ofMinutes(30),
                FIXED_CLOCK
        );

        AlignmentAutomationService.AutomationRunResult first =
                service.runManual(1);
        AlignmentAutomationService.AutomationRunResult second =
                service.runManual(1);
        AlignmentAutomationService.AutomationRunResult blocked =
                service.runManual(1);

        assertThat(first.status().control().consecutiveFailures()).isEqualTo(1);
        assertThat(second.status().control().consecutiveFailures()).isEqualTo(2);
        assertThat(second.status().mode()).isEqualTo("COOLDOWN");
        assertThat(second.status().control().cooldownUntil())
                .isEqualTo(Instant.parse("2026-07-28T12:30:00Z"));
        assertThat(blocked.attempted()).isFalse();
        assertThat(blocked.reason()).contains("冷却期");
    }

    @Test
    void recoversAnInterruptedPersistedBatchAsFailure() {
        InMemoryStateRepository states = new InMemoryStateRepository();
        states.state = new AlignmentAutomationState(
                false,
                true,
                java.time.LocalDate.parse("2026-07-28"),
                3,
                0,
                null,
                Instant.parse("2026-07-28T11:59:00Z"),
                null,
                0,
                0,
                "SCHEDULED",
                "",
                "batch started",
                4
        );
        AiJudgePanelService panel = mock(AiJudgePanelService.class);
        when(panel.workload()).thenReturn(
                new AiJudgePanelService.AssessmentWorkload(5, 3, 2));

        AlignmentAutomationService service = new AlignmentAutomationService(
                panel,
                states,
                true,
                10,
                50,
                3,
                Duration.ofMinutes(30),
                FIXED_CLOCK
        );

        AlignmentAutomationService.AutomationStatus status = service.status();

        assertThat(status.control().running()).isFalse();
        assertThat(status.control().consecutiveFailures()).isEqualTo(1);
        assertThat(status.control().lastError()).contains("服务重启中断");
        assertThat(states.state.revision()).isEqualTo(5);
    }

    private AiJudgePanelService.BatchAssessmentResult batch(
            int count,
            int incomplete) {
        return new AiJudgePanelService.BatchAssessmentResult(
                count,
                count - incomplete,
                incomplete,
                0,
                incomplete,
                List.of()
        );
    }

    private static class InMemoryStateRepository
            implements AlignmentAutomationStateRepository {

        private AlignmentAutomationState state;

        @Override
        public Optional<AlignmentAutomationState> load() {
            return Optional.ofNullable(state);
        }

        @Override
        public AlignmentAutomationState save(
                AlignmentAutomationState next) {
            state = next;
            return next;
        }
    }
}
