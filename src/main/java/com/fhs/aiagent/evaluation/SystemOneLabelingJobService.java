package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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
public class SystemOneLabelingJobService {

    private final SystemOneShadowSampleService sampleService;
    private final SystemOneCounterfactualLabelingService labelingService;
    private final SystemOneCounterfactualLabelRepository labelRepository;
    private final int configuredMaximumCases;
    private final double configuredMaximumCostCny;
    private final boolean exposeHoldoutLabels;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public SystemOneLabelingJobService(
            SystemOneShadowSampleService sampleService,
            SystemOneCounterfactualLabelingService labelingService,
            SystemOneCounterfactualLabelRepository labelRepository,
            @Value("${agent.evaluation.system-one.labeling.maximum-cases:20}")
            int configuredMaximumCases,
            @Value("${agent.evaluation.system-one.labeling.maximum-cost-cny:10}")
            double configuredMaximumCostCny,
            @Value("${agent.evaluation.system-one.labeling.expose-holdout-labels:false}")
            boolean exposeHoldoutLabels) {
        this.sampleService = sampleService;
        this.labelingService = labelingService;
        this.labelRepository = labelRepository;
        this.configuredMaximumCases = Math.max(1, configuredMaximumCases);
        this.configuredMaximumCostCny = Math.max(0, configuredMaximumCostCny);
        this.exposeHoldoutLabels = exposeHoldoutLabels;
    }

    public synchronized RunSnapshot start(
            Integer requestedCases, Double requestedMaximumCostCny) {
        boolean activeRun = runs.values().stream()
                .anyMatch(state -> state.isActive());
        if (activeRun) {
            throw new IllegalStateException("A System One labeling run is already active");
        }
        int maximumCases = requestedCases == null
                ? configuredMaximumCases
                : Math.max(1, Math.min(configuredMaximumCases, requestedCases));
        double maximumCostCny = requestedMaximumCostCny == null
                ? configuredMaximumCostCny
                : Math.max(0, Math.min(configuredMaximumCostCny, requestedMaximumCostCny));
        String runId = "system-one-label-" + UUID.randomUUID();
        RunState state = new RunState(runId, maximumCases, maximumCostCny);
        runs.put(runId, state);
        Future<?> future = executor.submit(() -> execute(state));
        state.attach(future);
        return state.snapshot();
    }

    public RunSnapshot get(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new NoSuchElementException("Labeling run not found: " + runId);
        return state.snapshot();
    }

    public RunSnapshot cancel(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new NoSuchElementException("Labeling run not found: " + runId);
        state.cancel();
        return state.snapshot();
    }

    public List<SystemOneCounterfactualLabel> labels(String split) {
        String normalized = split == null ? "" : split.trim().toUpperCase();
        if ((normalized.isBlank() || "HOLDOUT".equals(normalized))
                && !exposeHoldoutLabels) {
            throw new IllegalArgumentException(
                    "Holdout labels are sealed; request split=DEVELOPMENT");
        }
        return labelRepository.findAll().stream()
                .filter(label -> normalized.isBlank() || normalized.equals(label.split()))
                .toList();
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(RunState::cancel);
        executor.shutdownNow();
    }

    private void execute(RunState state) {
        if (!state.begin()) return;
        try {
            if (state.maximumCostCny <= 0) {
                state.totalCases = 0;
                state.complete(List.of(), true);
                return;
            }
            List<SystemOneShadowSample> selected = selectSamples(state.maximumCases);
            state.totalCases = selected.size();
            List<SystemOneCounterfactualLabel> created = new ArrayList<>();
            for (SystemOneShadowSample sample : selected) {
                if (state.cancelRequested.get()) {
                    state.cancel();
                    return;
                }
                state.currentSampleId = sample.sampleId();
                SystemOneCounterfactualLabel label = labelingService.label(sample, state.runId);
                created.add(label);
                state.completedCases = created.size();
                state.totalCostCny = roundCost(created.stream()
                        .mapToDouble(this::generationCost).sum());
                if (state.totalCostCny >= state.maximumCostCny) {
                    state.complete(created, true);
                    return;
                }
            }
            state.complete(created, false);
        } catch (RuntimeException exception) {
            state.fail(exception);
        }
    }

    private List<SystemOneShadowSample> selectSamples(int maximumCases) {
        List<SystemOneShadowSample> candidates = sampleService.recent(1000, false).stream()
                .filter(sample -> !sample.question().isBlank())
                .filter(sample -> labelRepository.findBySampleId(sample.sampleId()).isEmpty())
                .sorted(Comparator.comparing(SystemOneShadowSample::sampleId))
                .toList();
        List<SystemOneShadowSample> review = candidates.stream()
                .filter(SystemOneShadowSample::reviewEligible).toList();
        List<SystemOneShadowSample> controls = candidates.stream()
                .filter(sample -> !sample.reviewEligible()).toList();
        int controlTarget = controls.isEmpty() ? 0 : Math.max(1, maximumCases / 10);
        List<SystemOneShadowSample> selected = new ArrayList<>();
        selected.addAll(review.stream().limit(maximumCases - controlTarget).toList());
        selected.addAll(controls.stream().limit(controlTarget).toList());
        if (selected.size() < maximumCases) {
            candidates.stream()
                    .filter(sample -> !selected.contains(sample))
                    .limit(maximumCases - selected.size())
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private double generationCost(SystemOneCounterfactualLabel label) {
        double single = label.forcedSingle() == null ? 0
                : label.forcedSingle().estimatedCostCny();
        double multi = label.forcedMulti() == null ? 0
                : label.forcedMulti().estimatedCostCny();
        return single + multi;
    }

    private double roundCost(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    public record RunSnapshot(
            String runId,
            String status,
            Instant startedAt,
            Instant completedAt,
            int completedCases,
            int totalCases,
            String currentSampleId,
            int maximumCases,
            double maximumCostCny,
            double totalCostCny,
            String error,
            RunReport report
    ) {
    }

    public record RunReport(
            int labeledCases,
            long developmentCases,
            long holdoutCases,
            long multiAgentPositiveCases,
            long humanReviewRequiredCases,
            long failedOrSkippedCases,
            boolean budgetLimitReached,
            boolean judgeCostMeasured,
            List<String> sampleIds
    ) {
    }

    private static final class RunState {
        private final String runId;
        private final int maximumCases;
        private final double maximumCostCny;
        private final Instant startedAt = Instant.now();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile String status = "QUEUED";
        private volatile Instant completedAt;
        private volatile int completedCases;
        private volatile int totalCases;
        private volatile String currentSampleId = "";
        private volatile double totalCostCny;
        private volatile String error = "";
        private volatile RunReport report;
        private volatile Future<?> future;

        private RunState(String runId, int maximumCases, double maximumCostCny) {
            this.runId = runId;
            this.maximumCases = maximumCases;
            this.maximumCostCny = maximumCostCny;
        }

        private synchronized void attach(Future<?> future) {
            this.future = future;
            if (cancelRequested.get()) future.cancel(true);
        }

        private synchronized boolean begin() {
            if (cancelRequested.get()) return false;
            status = "RUNNING";
            return true;
        }

        private synchronized void complete(
                List<SystemOneCounterfactualLabel> labels, boolean budgetLimitReached) {
            if (cancelRequested.get()) {
                status = "CANCELLED";
                completedAt = Instant.now();
                currentSampleId = "";
                return;
            }
            status = budgetLimitReached ? "COMPLETED_BUDGET_LIMIT" : "COMPLETED";
            completedAt = Instant.now();
            currentSampleId = "";
            report = new RunReport(
                    labels.size(),
                    labels.stream().filter(label -> "DEVELOPMENT".equals(label.split())).count(),
                    labels.stream().filter(label -> "HOLDOUT".equals(label.split())).count(),
                    labels.stream().filter(label -> Boolean.TRUE.equals(
                            label.expectedMultiAgent())).count(),
                    labels.stream().filter(
                            SystemOneCounterfactualLabel::humanReviewRequired).count(),
                    labels.stream().filter(label -> !"COMPLETED".equals(label.status())
                            && !"REVIEW_REQUIRED".equals(label.status())).count(),
                    budgetLimitReached,
                    false,
                    labels.stream().map(SystemOneCounterfactualLabel::sampleId).toList());
        }

        private synchronized void fail(RuntimeException exception) {
            if (cancelRequested.get()) {
                status = "CANCELLED";
                completedAt = Instant.now();
                currentSampleId = "";
                return;
            }
            status = "FAILED";
            completedAt = Instant.now();
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }

        private synchronized void cancel() {
            if (completedAt != null) return;
            cancelRequested.set(true);
            status = "CANCELLED";
            completedAt = Instant.now();
            Future<?> current = future;
            if (current != null) current.cancel(true);
        }

        private boolean isActive() {
            return completedAt == null && ("QUEUED".equals(status) || "RUNNING".equals(status));
        }

        private synchronized RunSnapshot snapshot() {
            return new RunSnapshot(runId, status, startedAt, completedAt,
                    completedCases, totalCases, currentSampleId, maximumCases,
                    maximumCostCny, totalCostCny, error, report);
        }
    }
}
