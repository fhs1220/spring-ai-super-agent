package com.fhs.aiagent.evaluation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

@Service
public class AlignmentExperimentService {

    private final RagAbEvaluationService evaluationService;

    private final RagAbEvaluationJobService evaluationJobService;

    private final AlignmentAblationReportService ablationReportService;

    private final AlignmentExperimentRepository repository;

    private final Clock clock;

    @Autowired
    public AlignmentExperimentService(
            RagAbEvaluationService evaluationService,
            RagAbEvaluationJobService evaluationJobService,
            AlignmentAblationReportService ablationReportService,
            AlignmentExperimentRepository repository) {
        this(
                evaluationService,
                evaluationJobService,
                ablationReportService,
                repository,
                Clock.systemUTC()
        );
    }

    AlignmentExperimentService(
            RagAbEvaluationService evaluationService,
            RagAbEvaluationJobService evaluationJobService,
            AlignmentAblationReportService ablationReportService,
            AlignmentExperimentRepository repository,
            Clock clock) {
        this.evaluationService = Objects.requireNonNull(evaluationService);
        this.evaluationJobService = Objects.requireNonNull(evaluationJobService);
        this.ablationReportService = Objects.requireNonNull(ablationReportService);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized AlignmentExperiment create(
            List<AlignmentExperiment.ArmPlan> requestedPlans) {
        List<AlignmentExperiment.ArmPlan> plans = validatePlans(requestedPlans);
        RagAbEvaluationService.BenchmarkMetadata benchmark =
                evaluationService.benchmarkMetadata();
        String fingerprint = experimentFingerprint(plans, benchmark);
        String experimentId = "alignment-experiment-"
                + fingerprint.substring(0, 16);
        return repository.find(experimentId).orElseGet(() -> {
            Instant now = clock.instant();
            List<AlignmentExperiment.ArmEvidence> arms = plans.stream()
                    .map(plan -> new AlignmentExperiment.ArmEvidence(
                            plan, "", null, null))
                    .toList();
            return repository.save(new AlignmentExperiment(
                    experimentId,
                    fingerprint,
                    benchmark.version(),
                    benchmark.fingerprint(),
                    benchmark.caseCount(),
                    now,
                    now,
                    null,
                    AlignmentExperiment.Status.PLANNED,
                    arms,
                    "",
                    "",
                    ""
            ));
        });
    }

    public AlignmentExperiment get(String experimentId) {
        return repository.find(experimentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Alignment experiment not found: " + experimentId));
    }

    public synchronized AlignmentExperiment attachEvidence(
            String experimentId,
            AlignmentAblationReport.ExperimentArm arm,
            String evaluationRunId) {
        if (arm == null || !hasText(evaluationRunId)) {
            throw new IllegalArgumentException(
                    "arm and evaluationRunId are required");
        }
        AlignmentExperiment experiment = get(experimentId);
        if (experiment.status() == AlignmentExperiment.Status.COMPLETED) {
            throw new IllegalStateException(
                    "Completed experiment cannot accept new evidence");
        }
        AlignmentExperiment.ArmEvidence target = experiment.arms().stream()
                .filter(evidence -> evidence.plan().arm() == arm)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Arm is not part of experiment: " + arm));
        if (target.attached()) {
            if (target.evaluationRunId().equals(evaluationRunId)) {
                return experiment;
            }
            throw new IllegalStateException(
                    "Experiment arm already has different evidence: " + arm);
        }
        if (experiment.arms().stream().anyMatch(evidence ->
                evaluationRunId.equals(evidence.evaluationRunId()))) {
            throw new IllegalArgumentException(
                    "The same evaluation run cannot be reused across arms");
        }

        RagAbEvaluationJobService.RunSnapshot snapshot =
                evaluationJobService.get(evaluationRunId);
        validateEvidence(experiment, target.plan(), snapshot);
        Instant now = clock.instant();
        List<AlignmentExperiment.ArmEvidence> arms = experiment.arms().stream()
                .map(evidence -> evidence.plan().arm() == arm
                        ? new AlignmentExperiment.ArmEvidence(
                                evidence.plan(),
                                evaluationRunId,
                                now,
                                snapshot.report())
                        : evidence)
                .toList();
        AlignmentExperiment.Status status = arms.stream()
                .allMatch(AlignmentExperiment.ArmEvidence::attached)
                ? AlignmentExperiment.Status.READY_TO_FINALIZE
                : AlignmentExperiment.Status.COLLECTING;
        return repository.save(copy(
                experiment, now, null, status, arms, null));
    }

    public synchronized AlignmentExperiment finalizeExperiment(
            String experimentId) {
        AlignmentExperiment experiment = get(experimentId);
        if (experiment.status() == AlignmentExperiment.Status.COMPLETED) {
            return experiment;
        }
        if (experiment.arms().stream()
                .anyMatch(evidence -> !evidence.attached())) {
            throw new IllegalStateException(
                    "All four experiment arms require evidence before finalization");
        }
        List<AlignmentAblationReportService.EvidenceInput> inputs =
                experiment.arms().stream()
                        .sorted(Comparator.comparing(
                                evidence -> evidence.plan().arm()))
                        .map(evidence ->
                                new AlignmentAblationReportService.EvidenceInput(
                                        new AlignmentAblationReport.ArmInput(
                                                evidence.plan().arm(),
                                                evidence.evaluationRunId(),
                                                evidence.plan().modelVersion()
                                        ),
                                        evidence.report()
                        ))
                        .toList();
        AlignmentAblationReport report =
                ablationReportService.createFromEvidence(inputs);
        Instant now = clock.instant();
        return repository.save(copy(
                experiment,
                now,
                now,
                AlignmentExperiment.Status.COMPLETED,
                experiment.arms(),
                report
        ));
    }

    private void validateEvidence(
            AlignmentExperiment experiment,
            AlignmentExperiment.ArmPlan plan,
            RagAbEvaluationJobService.RunSnapshot snapshot) {
        if (snapshot == null
                || !"COMPLETED".equals(snapshot.status())
                || snapshot.report() == null) {
            throw new IllegalArgumentException(
                    "Evaluation run is not completed: "
                            + (snapshot == null ? "" : snapshot.runId()));
        }
        RagAbReport report = snapshot.report();
        if (!experiment.benchmarkVersion().equals(report.benchmarkVersion())
                || !experiment.benchmarkFingerprint().equals(
                        report.benchmarkFingerprint())
                || experiment.benchmarkCaseCount() != report.caseCount()) {
            throw new IllegalArgumentException(
                    "Evaluation evidence uses an incompatible benchmark");
        }
        if (!plan.runtimeIdentity().equals(report.runtimeIdentity())) {
            throw new IllegalArgumentException(
                    "Evaluation runtime identity does not match the planned arm");
        }
    }

    private List<AlignmentExperiment.ArmPlan> validatePlans(
            List<AlignmentExperiment.ArmPlan> requestedPlans) {
        int armCount = AlignmentAblationReport.ExperimentArm.values().length;
        if (requestedPlans == null || requestedPlans.size() != armCount) {
            throw new IllegalArgumentException(
                    "Exactly four experiment arm plans are required");
        }
        Map<AlignmentAblationReport.ExperimentArm,
                AlignmentExperiment.ArmPlan> plans = new EnumMap<>(
                AlignmentAblationReport.ExperimentArm.class);
        Set<String> artifacts = new HashSet<>();
        for (AlignmentExperiment.ArmPlan plan : requestedPlans) {
            if (plan == null || plan.arm() == null
                    || !plan.runtimeIdentity().isVerifiable()) {
                throw new IllegalArgumentException(
                        "Each arm requires a verifiable model and training identity");
            }
            if (plans.putIfAbsent(plan.arm(), plan) != null) {
                throw new IllegalArgumentException(
                        "Duplicate experiment arm: " + plan.arm());
            }
            if (!artifacts.add(plan.modelArtifactFingerprint())) {
                throw new IllegalArgumentException(
                        "Each experiment arm must use a distinct model artifact");
            }
        }
        if (plans.size() != armCount) {
            throw new IllegalArgumentException("All four experiment arms are required");
        }
        return java.util.Arrays.stream(
                        AlignmentAblationReport.ExperimentArm.values())
                .map(plans::get)
                .toList();
    }

    private String experimentFingerprint(
            List<AlignmentExperiment.ArmPlan> plans,
            RagAbEvaluationService.BenchmarkMetadata benchmark) {
        StringBuilder canonical = new StringBuilder()
                .append(benchmark.version()).append('|')
                .append(benchmark.fingerprint()).append('|')
                .append(benchmark.caseCount()).append('\n');
        plans.forEach(plan -> canonical
                .append(plan.arm()).append('|')
                .append(plan.modelVersion()).append('|')
                .append(plan.modelArtifactFingerprint()).append('|')
                .append(plan.trainingConfigFingerprint()).append('|')
                .append(plan.rewardSchemaVersion()).append('|')
                .append(plan.sourceDeployment()).append('\n'));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AlignmentExperiment copy(
            AlignmentExperiment source,
            Instant updatedAt,
            Instant completedAt,
            AlignmentExperiment.Status status,
            List<AlignmentExperiment.ArmEvidence> arms,
            AlignmentAblationReport report) {
        return new AlignmentExperiment(
                source.experimentId(),
                source.experimentFingerprint(),
                source.benchmarkVersion(),
                source.benchmarkFingerprint(),
                source.benchmarkCaseCount(),
                source.createdAt(),
                updatedAt,
                completedAt,
                status,
                new ArrayList<>(arms),
                report == null
                        ? source.ablationReportId()
                        : report.reportId(),
                report == null
                        ? source.ablationReportPath()
                        : report.reportPath(),
                report == null
                        ? source.ablationMarkdownReportPath()
                        : report.markdownReportPath()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
