package com.fhs.aiagent.rl.alignment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * AI 自动评审的持久化预算、故障电路与最近一次运行状态。
 */
public record AlignmentAutomationState(
        boolean paused,
        boolean running,
        LocalDate budgetDate,
        int assessedToday,
        int consecutiveFailures,
        Instant cooldownUntil,
        Instant lastRunStartedAt,
        Instant lastRunCompletedAt,
        int lastAssessedCount,
        int lastIncompleteAssessmentCount,
        String lastTrigger,
        String lastError,
        String reason,
        long revision
) {
    public AlignmentAutomationState {
        budgetDate = budgetDate == null ? LocalDate.now() : budgetDate;
        assessedToday = Math.max(0, assessedToday);
        consecutiveFailures = Math.max(0, consecutiveFailures);
        lastAssessedCount = Math.max(0, lastAssessedCount);
        lastIncompleteAssessmentCount =
                Math.max(0, lastIncompleteAssessmentCount);
        lastTrigger = normalize(lastTrigger);
        lastError = normalize(lastError);
        reason = normalize(reason);
        revision = Math.max(0, revision);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
