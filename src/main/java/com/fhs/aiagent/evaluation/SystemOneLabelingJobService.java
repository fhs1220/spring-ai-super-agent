package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SystemOneLabelingJobService {
    private final SystemOneShadowSampleService sampleService;
    private final SystemOneCounterfactualLabelingService labelingService;
    private final SystemOneCounterfactualLabelRepository labelRepository;
    private final SystemOneLabelingRunRepository runRepository;
    private final int configuredMaximumCases;
    private final double configuredMaximumCostCny;
    private final boolean exposeHoldoutLabels;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    /** Compatibility constructor for embedded callers; Spring always uses durable job storage. */
    public SystemOneLabelingJobService(
            SystemOneShadowSampleService sampleService,
            SystemOneCounterfactualLabelingService labelingService,
            SystemOneCounterfactualLabelRepository labelRepository,
            int configuredMaximumCases, double configuredMaximumCostCny,
            boolean exposeHoldoutLabels) {
        this(sampleService, labelingService, labelRepository, new SystemOneLabelingRunRepository() {
            @Override public void save(RunSnapshot snapshot) { }
            @Override public List<RunSnapshot> findAll() { return List.of(); }
        }, configuredMaximumCases, configuredMaximumCostCny, exposeHoldoutLabels);
    }

    @Autowired
    public SystemOneLabelingJobService(
            SystemOneShadowSampleService sampleService,
            SystemOneCounterfactualLabelingService labelingService,
            SystemOneCounterfactualLabelRepository labelRepository,
            SystemOneLabelingRunRepository runRepository,
            @Value("${agent.evaluation.system-one.labeling.maximum-cases:20}") int configuredMaximumCases,
            @Value("${agent.evaluation.system-one.labeling.maximum-cost-cny:10}") double configuredMaximumCostCny,
            @Value("${agent.evaluation.system-one.labeling.expose-holdout-labels:false}") boolean exposeHoldoutLabels) {
        this.sampleService = sampleService;
        this.labelingService = labelingService;
        this.labelRepository = labelRepository;
        this.runRepository = runRepository;
        this.configuredMaximumCases = Math.max(1, configuredMaximumCases);
        this.configuredMaximumCostCny = validCostLimit(configuredMaximumCostCny);
        this.exposeHoldoutLabels = exposeHoldoutLabels;
        for (RunSnapshot snapshot : runRepository.findAll()) {
            RunState state = new RunState(snapshot);
            runs.put(state.runId, state);
            if (snapshot.completedAt() == null) {
                state.finish("INTERRUPTED", reconcileLabels(state.runId, List.of()), false,
                        "Process restarted; persisted label charges were reconciled");
            }
        }
    }

    public synchronized RunSnapshot start(Integer requestedCases, Double requestedMaximumCostCny) {
        if (runs.values().stream().anyMatch(RunState::isActive)) {
            throw new IllegalStateException("A System One labeling run is already active");
        }
        int maximumCases = requestedCases == null ? configuredMaximumCases
                : Math.max(1, Math.min(configuredMaximumCases, requestedCases));
        double maximumCostCny = requestedMaximumCostCny == null ? configuredMaximumCostCny
                : Math.min(configuredMaximumCostCny, validCostLimit(requestedMaximumCostCny));
        if (maximumCostCny > 0) labelingService.validateConfiguration();
        RunState state = new RunState("system-one-label-" + UUID.randomUUID(), maximumCases, maximumCostCny);
        state.persist();
        runs.put(state.runId, state);
        try {
            // Do not cancel the Future: finally must run even if cancellation precedes start.
            // Only actual worker exit releases the active-run lease.
            executor.execute(() -> execute(state));
        } catch (RuntimeException exception) {
            state.finish("FAILED", List.of(), false, exception.getClass().getSimpleName());
            throw exception;
        }
        return state.snapshot();
    }

    public RunSnapshot get(String runId) { return requireRun(runId).snapshot(); }

    public RunSnapshot cancel(String runId) {
        RunState state = requireRun(runId);
        state.requestCancel();
        return state.snapshot();
    }

    private RunState requireRun(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new NoSuchElementException("Labeling run not found: " + runId);
        return state;
    }

    public List<SystemOneCounterfactualLabel> labels(String split) {
        String normalized = split == null ? "" : split.trim().toUpperCase(Locale.ROOT);
        if ((normalized.isBlank() || "HOLDOUT".equals(normalized)) && !exposeHoldoutLabels) {
            throw new IllegalArgumentException("Holdout labels are sealed; request split=DEVELOPMENT");
        }
        return labelRepository.findAll().stream()
                .filter(label -> normalized.isBlank() || normalized.equals(label.split())).toList();
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(RunState::requestCancel);
        // Queued workers must still enter their cancellation/finalization path.
        executor.shutdown();
    }

    private void execute(RunState state) {
        List<SystemOneCounterfactualLabel> created = new ArrayList<>();
        String terminalStatus = "COMPLETED";
        String error = "";
        boolean thresholdReached = false;
        String inFlightSampleId = null;
        try {
            if (!state.begin()) return;
            if (state.maximumCostCny <= 0) {
                terminalStatus = "COMPLETED_BUDGET_LIMIT";
                thresholdReached = true;
                return;
            }
            List<SystemOneShadowSample> selected = selectSamples(state.maximumCases);
            state.setTotalCases(selected.size());
            for (SystemOneShadowSample sample : selected) {
                if (state.cancellationRequested()) return;
                inFlightSampleId = sample.sampleId();
                SystemOneCounterfactualLabel label = labelingService.label(sample, state.runId);
                created.add(label);
                inFlightSampleId = null;
                double cost = label.totalEstimatedCostCny();
                state.recordProgress(created.size(), Double.isFinite(cost) && cost >= 0 ? cost : 0);
                if (!label.costAccountingComplete() || !Double.isFinite(cost) || cost < 0) {
                    terminalStatus = "STOPPED_USAGE_UNKNOWN";
                    error = "An attempted model call has unknown usage; no further samples were started";
                    return;
                }
                if (state.totalCostCny() >= state.maximumCostCny) {
                    terminalStatus = "COMPLETED_BUDGET_LIMIT";
                    thresholdReached = true;
                    return;
                }
            }
        } catch (RuntimeException exception) {
            terminalStatus = "FAILED";
            // Exception messages may contain prompts or answers; do not expose them.
            error = exception.getClass().getSimpleName();
        } finally {
            try {
                List<SystemOneCounterfactualLabel> reconciled = reconcileLabels(state.runId, created);
                String pendingId = inFlightSampleId;
                boolean checkpointFound = pendingId == null || reconciled.stream()
                        .anyMatch(label -> pendingId.equals(label.sampleId())
                                && state.runId.equals(label.runId()));
                state.finish(terminalStatus, reconciled, thresholdReached, error, checkpointFound);
            } catch (RuntimeException reconciliationFailure) {
                state.finish("FAILED", created, thresholdReached,
                        "Label charge reconciliation unavailable: "
                                + reconciliationFailure.getClass().getSimpleName(), false);
            }
        }
    }

    private List<SystemOneCounterfactualLabel> reconcileLabels(
            String runId, List<SystemOneCounterfactualLabel> returned) {
        Map<String, SystemOneCounterfactualLabel> labels = new LinkedHashMap<>();
        returned.forEach(label -> labels.put(label.sampleId(), label));
        labelRepository.findAll().stream().filter(label -> runId.equals(label.runId()))
                .forEach(label -> labels.put(label.sampleId(), label));
        return labels.values().stream().sorted(Comparator.comparing(
                SystemOneCounterfactualLabel::sampleId)).toList();
    }

    private List<SystemOneShadowSample> selectSamples(int maximumCases) {
        Map<String, SystemOneShadowSample> sourceById = new LinkedHashMap<>();
        sampleService.recent(1000, false).forEach(sample -> sourceById.put(sample.sampleId(), sample));
        // Checkpoints outlive the bounded shadow pool. Resume their immutable source even
        // when the pool pruned the sample or a later observation replaced its metadata.
        labelRepository.findAll().stream().filter(this::resumable)
                .filter(label -> label.evidence() != null && label.evidence().observation() != null)
                .filter(label -> label.sampleId().equals(label.evidence().observation().sampleId()))
                .forEach(label -> sourceById.put(label.sampleId(), label.evidence().observation()));
        List<SystemOneShadowSample> candidates = sourceById.values().stream()
                .filter(sample -> !sample.question().isBlank())
                .filter(sample -> labelRepository.findBySampleId(sample.sampleId())
                        .map(this::resumable).orElse(true))
                .sorted(Comparator.comparing(SystemOneShadowSample::sampleId)).toList();
        List<SystemOneShadowSample> review = candidates.stream()
                .filter(SystemOneShadowSample::reviewEligible).toList();
        List<SystemOneShadowSample> controls = candidates.stream()
                .filter(sample -> !sample.reviewEligible()).toList();
        int controlTarget = controls.isEmpty() ? 0 : Math.max(1, maximumCases / 10);
        List<SystemOneShadowSample> selected = new ArrayList<>();
        selected.addAll(review.stream().limit(maximumCases - controlTarget).toList());
        selected.addAll(controls.stream().limit(controlTarget).toList());
        if (selected.size() < maximumCases) {
            candidates.stream().filter(sample -> !selected.contains(sample))
                    .limit(maximumCases - selected.size()).forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private boolean resumable(SystemOneCounterfactualLabel label) {
        return "FAILED".equals(label.status()) || "IN_PROGRESS".equals(label.status());
    }

    private static double validCostLimit(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Cost stopping threshold must be finite and non-negative");
        }
        return value;
    }

    /** Estimates include persisted attempts of resumed labels, not a provider-enforced cap. */
    public record RunSnapshot(String runId, String status, Instant startedAt, Instant completedAt,
                              int completedCases, int totalCases, String currentSampleId,
                              int maximumCases, double maximumCostCny, double totalCostCny,
                              String error, RunReport report, boolean costAccountingComplete) {
        public RunSnapshot(String runId, String status, Instant startedAt, Instant completedAt,
                           int completedCases, int totalCases, String currentSampleId,
                           int maximumCases, double maximumCostCny, double totalCostCny,
                           String error, RunReport report) {
            this(runId, status, startedAt, completedAt, completedCases, totalCases,
                    currentSampleId, maximumCases, maximumCostCny, totalCostCny, error, report, false);
        }
    }

    /** Outcome-dependent aggregates and sampleIds always contain development samples only. */
    public record RunReport(int labeledCases, long developmentCases, long holdoutCases,
                            long multiAgentPositiveCases, long humanReviewRequiredCases,
                            long failedOrSkippedCases, boolean budgetLimitReached,
                            boolean judgeCostMeasured, List<String> sampleIds) { }

    private final class RunState {
        private final String runId;
        private final int maximumCases;
        private final double maximumCostCny;
        private final Instant startedAt;
        private boolean cancelRequested;
        private String status;
        private Instant completedAt;
        private int completedCases;
        private int totalCases;
        private double totalCostCny;
        private boolean costAccountingComplete;
        private String error = "";
        private RunReport report;
        private Thread worker;

        private RunState(String runId, int maximumCases, double maximumCostCny) {
            this.runId = runId;
            this.maximumCases = maximumCases;
            this.maximumCostCny = maximumCostCny;
            this.startedAt = Instant.now();
            this.status = "QUEUED";
        }

        private RunState(RunSnapshot snapshot) {
            this.runId = snapshot.runId();
            this.maximumCases = snapshot.maximumCases();
            this.maximumCostCny = snapshot.maximumCostCny();
            this.startedAt = snapshot.startedAt();
            this.completedAt = snapshot.completedAt();
            this.completedCases = snapshot.completedCases();
            this.totalCases = snapshot.totalCases();
            this.totalCostCny = snapshot.totalCostCny();
            this.costAccountingComplete = snapshot.costAccountingComplete();
            this.report = snapshot.report();
            this.status = snapshot.status();
            this.error = snapshot.error();
            if (completedAt == null) {
                this.status = "INTERRUPTED";
                this.completedAt = Instant.now();
                this.error = "Process restarted; inspect persisted attempts before resuming";
            }
        }

        private synchronized boolean begin() {
            worker = Thread.currentThread();
            if (cancelRequested) return false;
            status = "RUNNING";
            persist();
            return true;
        }

        private synchronized void setTotalCases(int totalCases) {
            this.totalCases = totalCases;
            persist();
        }

        private synchronized void recordProgress(int completedCases, double addedCost) {
            this.completedCases = completedCases;
            this.totalCostCny = Math.round((this.totalCostCny + addedCost) * 100_000_000.0)
                    / 100_000_000.0;
            persist();
        }

        private synchronized double totalCostCny() { return totalCostCny; }
        private synchronized boolean cancellationRequested() { return cancelRequested; }

        private synchronized void requestCancel() {
            if (completedAt != null) return;
            cancelRequested = true;
            status = "CANCELLING";
            if (worker != null) worker.interrupt();
            persist();
        }

        private synchronized void finish(String terminalStatus,
                                         List<SystemOneCounterfactualLabel> labels,
                                         boolean thresholdReached, String error) {
            finish(terminalStatus, labels, thresholdReached, error, true);
        }

        private synchronized void finish(String terminalStatus,
                                         List<SystemOneCounterfactualLabel> labels,
                                         boolean thresholdReached, String error,
                                         boolean ledgerAvailable) {
            double reconciledCost = labels.stream().mapToDouble(SystemOneCounterfactualLabel::totalEstimatedCostCny)
                    .filter(cost -> Double.isFinite(cost) && cost >= 0).sum();
            boolean allKnown = labels.stream().allMatch(label -> label.costAccountingComplete()
                    && Double.isFinite(label.totalEstimatedCostCny()) && label.totalEstimatedCostCny() >= 0);
            this.costAccountingComplete = ledgerAvailable && allKnown
                    && reconciledCost + 0.00000001 >= this.totalCostCny
                    && labels.size() >= this.completedCases;
            this.totalCostCny = Math.round(Math.max(this.totalCostCny, reconciledCost) * 100_000_000.0)
                    / 100_000_000.0;
            this.completedCases = Math.max(this.completedCases, labels.size());
            List<SystemOneCounterfactualLabel> development = labels.stream()
                    .filter(label -> "DEVELOPMENT".equals(label.split())).toList();
            report = new RunReport(labels.size(), development.size(),
                    labels.stream().filter(label -> "HOLDOUT".equals(label.split())).count(),
                    development.stream().filter(label -> Boolean.TRUE.equals(label.expectedMultiAgent())).count(),
                    development.stream().filter(SystemOneCounterfactualLabel::humanReviewRequired).count(),
                    development.stream().filter(label -> !"COMPLETED".equals(label.status())
                            && !"REVIEW_REQUIRED".equals(label.status())).count(),
                    thresholdReached, !labels.isEmpty() && labels.stream()
                            .allMatch(SystemOneCounterfactualLabel::judgeCostMeasured),
                    development.stream().map(SystemOneCounterfactualLabel::sampleId).toList());
            this.status = cancelRequested ? "CANCELLED" : terminalStatus;
            this.error = cancelRequested ? "" : error;
            if (!costAccountingComplete) {
                this.error = (this.error.isBlank() ? "" : this.error + "; ")
                        + "Known charges only; total billing is incomplete";
            }
            this.worker = null;
            this.completedAt = Instant.now();
            persist();
        }

        private synchronized boolean isActive() { return completedAt == null; }
        private synchronized void persist() { runRepository.save(snapshot()); }

        private synchronized RunSnapshot snapshot() {
            // Live sample IDs can correlate sealed samples with result-dependent status.
            return new RunSnapshot(runId, status, startedAt, completedAt, completedCases,
                    totalCases, "", maximumCases, maximumCostCny, totalCostCny, error, report,
                    costAccountingComplete);
        }
    }
}
