package com.fhs.aiagent.rl.alignment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
public class AlignmentAutomationService {

    private final AiJudgePanelService panelService;

    private final AlignmentAutomationStateRepository stateRepository;

    private final boolean schedulerConfigured;

    private final int configuredBatchSize;

    private final int dailyTrajectoryLimit;

    private final int failureThreshold;

    private final Duration failureCooldown;

    private final Clock clock;

    private volatile AlignmentAutomationState state;

    @Autowired
    public AlignmentAutomationService(
            AiJudgePanelService panelService,
            AlignmentAutomationStateRepository stateRepository,
            @Value("${agent.rl.alignment.auto-evaluate-enabled:false}")
            boolean schedulerConfigured,
            @Value("${agent.rl.alignment.auto-evaluate-batch-size:10}")
            int configuredBatchSize,
            @Value("${agent.rl.alignment.daily-trajectory-limit:50}")
            int dailyTrajectoryLimit,
            @Value("${agent.rl.alignment.failure-threshold:3}")
            int failureThreshold,
            @Value("${agent.rl.alignment.failure-cooldown-minutes:30}")
            long failureCooldownMinutes) {
        this(
                panelService,
                stateRepository,
                schedulerConfigured,
                configuredBatchSize,
                dailyTrajectoryLimit,
                failureThreshold,
                Duration.ofMinutes(Math.max(1, failureCooldownMinutes)),
                Clock.systemUTC()
        );
    }

    AlignmentAutomationService(
            AiJudgePanelService panelService,
            AlignmentAutomationStateRepository stateRepository,
            boolean schedulerConfigured,
            int configuredBatchSize,
            int dailyTrajectoryLimit,
            int failureThreshold,
            Duration failureCooldown,
            Clock clock) {
        this.panelService = Objects.requireNonNull(panelService, "panelService");
        this.stateRepository = Objects.requireNonNull(
                stateRepository, "stateRepository");
        this.schedulerConfigured = schedulerConfigured;
        this.configuredBatchSize = Math.max(
                1, Math.min(configuredBatchSize, 100));
        this.dailyTrajectoryLimit = Math.max(1, dailyTrajectoryLimit);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.failureCooldown = Objects.requireNonNull(
                failureCooldown, "failureCooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = recoverState(stateRepository.load().orElseGet(
                this::initialState));
    }

    public synchronized AutomationRunResult runScheduled() {
        if (!schedulerConfigured) {
            return skipped("SCHEDULED", "自动评分调度开关未启用");
        }
        return run("SCHEDULED", configuredBatchSize);
    }

    public synchronized AutomationRunResult runManual(int requestedLimit) {
        return run(
                "MANUAL",
                Math.max(1, Math.min(requestedLimit, configuredBatchSize))
        );
    }

    public synchronized AutomationStatus status() {
        state = resetDailyBudgetIfNeeded(requireState());
        return statusFor(state);
    }

    public synchronized AutomationStatus updateControl(
            boolean paused,
            boolean resetFailureCircuit,
            String reason) {
        AlignmentAutomationState current = resetDailyBudgetIfNeeded(
                requireState());
        AlignmentAutomationState next = new AlignmentAutomationState(
                paused,
                false,
                current.budgetDate(),
                current.assessedToday(),
                resetFailureCircuit ? 0 : current.consecutiveFailures(),
                resetFailureCircuit ? null : current.cooldownUntil(),
                current.lastRunStartedAt(),
                current.lastRunCompletedAt(),
                current.lastAssessedCount(),
                current.lastIncompleteAssessmentCount(),
                current.lastTrigger(),
                resetFailureCircuit ? "" : current.lastError(),
                normalizeReason(reason, paused ? "manual pause" : "manual resume"),
                current.revision() + 1
        );
        state = stateRepository.save(next);
        return statusFor(state);
    }

    private AutomationRunResult run(String trigger, int requestedLimit) {
        AlignmentAutomationState current = resetDailyBudgetIfNeeded(
                requireState());
        AutomationStatus currentStatus = statusFor(current);
        if (current.paused()) {
            return skipped(trigger, "自动评分已暂停");
        }
        if (isCoolingDown(current)) {
            return skipped(trigger, "连续失败保护处于冷却期");
        }
        int remainingBudget = Math.max(
                0, dailyTrajectoryLimit - current.assessedToday());
        if (remainingBudget == 0) {
            return skipped(trigger, "今日自动评分额度已用完");
        }
        if (currentStatus.pendingTrajectoryCount() == 0) {
            return skipped(trigger, "没有待评分轨迹");
        }

        int effectiveLimit = Math.min(
                Math.min(requestedLimit, remainingBudget),
                (int) Math.min(Integer.MAX_VALUE,
                        currentStatus.pendingTrajectoryCount())
        );
        Instant startedAt = clock.instant();
        state = stateRepository.save(new AlignmentAutomationState(
                current.paused(),
                true,
                current.budgetDate(),
                current.assessedToday(),
                current.consecutiveFailures(),
                current.cooldownUntil(),
                startedAt,
                current.lastRunCompletedAt(),
                current.lastAssessedCount(),
                current.lastIncompleteAssessmentCount(),
                trigger,
                "",
                "batch started",
                current.revision() + 1
        ));
        try {
            AiJudgePanelService.BatchAssessmentResult batch =
                    panelService.assessPending(effectiveLimit);
            boolean completeFailure = batch.assessedCount() > 0
                    && batch.incompleteJudgeCount() == batch.assessedCount();
            int failures = completeFailure
                    ? current.consecutiveFailures() + 1
                    : 0;
            Instant cooldownUntil = failures >= failureThreshold
                    ? clock.instant().plus(failureCooldown)
                    : null;
            String error = completeFailure
                    ? "本批次所有轨迹的 Judge 面板均不完整"
                    : batch.incompleteJudgeCount() > 0
                    ? "本批次存在不完整 Judge 面板"
                    : "";
            AlignmentAutomationState completed = new AlignmentAutomationState(
                    current.paused(),
                    false,
                    current.budgetDate(),
                    current.assessedToday() + batch.assessedCount(),
                    failures,
                    cooldownUntil,
                    startedAt,
                    clock.instant(),
                    batch.assessedCount(),
                    (int) batch.incompleteJudgeCount(),
                    trigger,
                    error,
                    completeFailure ? "judge panel failure" : "batch completed",
                    state.revision() + 1
            );
            state = stateRepository.save(completed);
            return new AutomationRunResult(
                    true,
                    batch.assessedCount() > 0,
                    batch,
                    statusFor(state),
                    error.isBlank() ? "评分批次执行完成" : error
            );
        } catch (RuntimeException exception) {
            int failures = current.consecutiveFailures() + 1;
            Instant cooldownUntil = failures >= failureThreshold
                    ? clock.instant().plus(failureCooldown)
                    : null;
            state = stateRepository.save(new AlignmentAutomationState(
                    current.paused(),
                    false,
                    current.budgetDate(),
                    current.assessedToday(),
                    failures,
                    cooldownUntil,
                    startedAt,
                    clock.instant(),
                    0,
                    0,
                    trigger,
                    normalizeReason(exception.getMessage(),
                            exception.getClass().getSimpleName()),
                    "batch failed",
                    state.revision() + 1
            ));
            return new AutomationRunResult(
                    true,
                    false,
                    emptyBatch(),
                    statusFor(state),
                    state.lastError()
            );
        }
    }

    private AutomationRunResult skipped(String trigger, String reason) {
        return new AutomationRunResult(
                false,
                false,
                emptyBatch(),
                statusFor(resetDailyBudgetIfNeeded(requireState())),
                reason
        );
    }

    private AiJudgePanelService.BatchAssessmentResult emptyBatch() {
        return new AiJudgePanelService.BatchAssessmentResult(
                0, 0, 0, 0, 0, List.of());
    }

    private AlignmentAutomationState recoverState(
            AlignmentAutomationState loaded) {
        AlignmentAutomationState recovered = resetDailyBudgetIfNeeded(loaded);
        if (!recovered.running()) {
            return recovered;
        }
        AlignmentAutomationState interrupted = new AlignmentAutomationState(
                recovered.paused(),
                false,
                recovered.budgetDate(),
                recovered.assessedToday(),
                recovered.consecutiveFailures() + 1,
                recovered.cooldownUntil(),
                recovered.lastRunStartedAt(),
                clock.instant(),
                0,
                0,
                recovered.lastTrigger(),
                "服务重启中断了上一次评分批次",
                "recovered interrupted batch",
                recovered.revision() + 1
        );
        return stateRepository.save(interrupted);
    }

    private AlignmentAutomationState resetDailyBudgetIfNeeded(
            AlignmentAutomationState current) {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(current.budgetDate())) {
            return current;
        }
        AlignmentAutomationState reset = new AlignmentAutomationState(
                current.paused(),
                current.running(),
                today,
                0,
                current.consecutiveFailures(),
                current.cooldownUntil(),
                current.lastRunStartedAt(),
                current.lastRunCompletedAt(),
                current.lastAssessedCount(),
                current.lastIncompleteAssessmentCount(),
                current.lastTrigger(),
                current.lastError(),
                "daily budget reset",
                current.revision() + 1
        );
        state = stateRepository.save(reset);
        return reset;
    }

    private AutomationStatus statusFor(AlignmentAutomationState current) {
        AiJudgePanelService.AssessmentWorkload workload = panelService.workload();
        int remaining = Math.max(
                0, dailyTrajectoryLimit - current.assessedToday());
        String mode;
        if (current.running()) {
            mode = "RUNNING";
        } else if (current.paused()) {
            mode = "PAUSED";
        } else if (isCoolingDown(current)) {
            mode = "COOLDOWN";
        } else if (remaining == 0) {
            mode = "DAILY_LIMIT";
        } else if (workload.pendingTrajectoryCount() == 0) {
            mode = "IDLE";
        } else if (!schedulerConfigured) {
            mode = "MANUAL_ONLY";
        } else {
            mode = "READY";
        }
        return new AutomationStatus(
                mode,
                schedulerConfigured,
                current,
                configuredBatchSize,
                dailyTrajectoryLimit,
                remaining,
                (long) current.assessedToday()
                        * AiJudgeDimension.values().length,
                failureThreshold,
                failureCooldown.toMinutes(),
                workload.completedTrajectoryCount(),
                workload.assessmentCount(),
                workload.pendingTrajectoryCount(),
                clock.instant()
        );
    }

    private boolean isCoolingDown(AlignmentAutomationState current) {
        return current.cooldownUntil() != null
                && current.cooldownUntil().isAfter(clock.instant());
    }

    private AlignmentAutomationState initialState() {
        return new AlignmentAutomationState(
                false,
                false,
                LocalDate.now(clock),
                0,
                0,
                null,
                null,
                null,
                0,
                0,
                "",
                "",
                "configured default",
                0
        );
    }

    private AlignmentAutomationState requireState() {
        AlignmentAutomationState current = state;
        if (current == null) {
            throw new IllegalStateException(
                    "Alignment automation state is not initialized");
        }
        return current;
    }

    private String normalizeReason(String value, String fallback) {
        String normalized = Objects.toString(value, "").trim();
        if (normalized.isBlank()) {
            normalized = fallback;
        }
        return normalized.length() <= 500
                ? normalized
                : normalized.substring(0, 500);
    }

    public record AutomationStatus(
            String mode,
            boolean schedulerConfigured,
            AlignmentAutomationState control,
            int batchSize,
            int dailyTrajectoryLimit,
            int remainingToday,
            long estimatedJudgeCallsToday,
            int failureThreshold,
            long failureCooldownMinutes,
            long completedTrajectoryCount,
            long assessmentCount,
            long pendingTrajectoryCount,
            Instant evaluatedAt
    ) {
    }

    public record AutomationRunResult(
            boolean attempted,
            boolean completed,
            AiJudgePanelService.BatchAssessmentResult batch,
            AutomationStatus status,
            String reason
    ) {
    }
}
