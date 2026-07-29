package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.AgentRunCancelledException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RagAbEvaluationJobService {

    private static final int MAX_RETAINED_RUNS = 100;

    private final RagAbEvaluationService evaluationService;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public RagAbEvaluationJobService(RagAbEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    public RunSnapshot start(int maximumCases) {
        String runId = "rag-ab-" + UUID.randomUUID();
        RunState state = new RunState(runId, Math.max(0, maximumCases));
        runs.put(runId, state);
        Future<?> future = executor.submit(() -> execute(state));
        state.attach(future);
        trimCompletedRuns();
        return state.snapshot();
    }

    public RunSnapshot get(String runId) {
        RunState state = runs.get(runId);
        if (state == null) {
            throw new NoSuchElementException("Evaluation run not found: " + runId);
        }
        return state.snapshot();
    }

    public List<RunSnapshot> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return runs.values().stream()
                .map(RunState::snapshot)
                .sorted(Comparator.comparing(RunSnapshot::startedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    public RunSnapshot cancel(String runId) {
        RunState state = runs.get(runId);
        if (state == null) {
            throw new NoSuchElementException("Evaluation run not found: " + runId);
        }
        state.cancel();
        return state.snapshot();
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(RunState::cancel);
        executor.shutdownNow();
    }

    private void execute(RunState state) {
        if (!state.begin()) {
            return;
        }
        try {
            RagAbReport report = evaluationService.evaluate(
                    state.runId,
                    state.maximumCases,
                    state.cancelRequested::get,
                    state::updateProgress
            );
            state.complete(report);
        } catch (RuntimeException exception) {
            state.fail(exception, AgentRunCancelledException.isCancellation(exception));
        }
    }

    private void trimCompletedRuns() {
        if (runs.size() <= MAX_RETAINED_RUNS) {
            return;
        }
        runs.values().stream()
                .filter(state -> state.completedAt != null)
                .sorted(Comparator.comparing(state -> state.completedAt))
                .limit(runs.size() - MAX_RETAINED_RUNS)
                .map(state -> state.runId)
                .toList()
                .forEach(runs::remove);
    }

    public record RunSnapshot(
            String runId,
            String status,
            Instant startedAt,
            Instant completedAt,
            int completedCases,
            int totalCases,
            String currentCaseId,
            String error,
            RagAbReport report
    ) {
    }

    private static final class RunState {

        private final String runId;

        private final int maximumCases;

        private final Instant startedAt = Instant.now();

        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        private volatile String status = "QUEUED";

        private volatile Instant completedAt;

        private volatile int completedCases;

        private volatile int totalCases;

        private volatile String currentCaseId = "";

        private volatile String error = "";

        private volatile RagAbReport report;

        private volatile Future<?> future;

        private RunState(String runId, int maximumCases) {
            this.runId = runId;
            this.maximumCases = maximumCases;
        }

        private synchronized void attach(Future<?> future) {
            this.future = future;
            if (cancelRequested.get()) {
                future.cancel(true);
            }
        }

        private synchronized boolean begin() {
            if (cancelRequested.get() || isTerminal()) {
                if (!isTerminal()) {
                    status = "CANCELLED";
                    completedAt = Instant.now();
                }
                return false;
            }
            status = "RUNNING";
            return true;
        }

        private synchronized void complete(RagAbReport completedReport) {
            if (cancelRequested.get() || "CANCELLED".equals(status)) {
                status = "CANCELLED";
            } else {
                report = completedReport;
                status = "COMPLETED";
            }
            completedAt = Instant.now();
        }

        private synchronized void fail(
                RuntimeException exception,
                boolean cancellationException) {
            if (cancelRequested.get()
                    || "CANCELLED".equals(status)
                    || cancellationException) {
                status = "CANCELLED";
            } else {
                status = "FAILED";
                error = exception.getClass().getSimpleName() + ": "
                        + exception.getMessage();
            }
            completedAt = Instant.now();
        }

        private synchronized void updateProgress(RagAbEvaluationService.Progress progress) {
            if (isTerminal()) {
                return;
            }
            this.completedCases = progress.completedCases();
            this.totalCases = progress.totalCases();
            this.currentCaseId = progress.currentCaseId();
        }

        private synchronized void cancel() {
            if (!isTerminal()) {
                cancelRequested.set(true);
                status = "CANCELLED";
                completedAt = Instant.now();
                Future<?> current = future;
                if (current != null) {
                    current.cancel(true);
                }
            }
        }

        private boolean isTerminal() {
            return "COMPLETED".equals(status)
                    || "FAILED".equals(status)
                    || "CANCELLED".equals(status);
        }

        private RunSnapshot snapshot() {
            return new RunSnapshot(
                    runId,
                    status,
                    startedAt,
                    completedAt,
                    completedCases,
                    totalCases,
                    currentCaseId,
                    error,
                    report
            );
        }
    }
}
