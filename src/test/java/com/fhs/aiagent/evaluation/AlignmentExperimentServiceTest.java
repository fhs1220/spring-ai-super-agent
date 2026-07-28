package com.fhs.aiagent.evaluation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlignmentExperimentServiceTest {

    private static final String BENCHMARK_FINGERPRINT = "f".repeat(64);

    private final RagAbEvaluationService evaluationService =
            mock(RagAbEvaluationService.class);

    private final RagAbEvaluationJobService jobService =
            mock(RagAbEvaluationJobService.class);

    private final AlignmentAblationReportService reportService =
            mock(AlignmentAblationReportService.class);

    private final InMemoryRepository repository = new InMemoryRepository();

    private final AlignmentExperimentService service =
            new AlignmentExperimentService(
                    evaluationService,
                    jobService,
                    reportService,
                    repository,
                    Clock.fixed(
                            Instant.parse("2026-07-28T08:00:00Z"),
                            ZoneOffset.UTC)
            );

    @Test
    void createsIdempotentPlanAttachesVerifiedEvidenceAndFinalizesOnce() {
        stubBenchmark();
        List<AlignmentExperiment.ArmPlan> plans = plans();
        AlignmentExperiment first = service.create(plans);
        AlignmentExperiment duplicate = service.create(plans);

        assertThat(duplicate).isEqualTo(first);
        assertThat(first.status()).isEqualTo(AlignmentExperiment.Status.PLANNED);
        assertThat(first.experimentFingerprint()).hasSize(64);

        for (int index = 0; index < plans.size(); index++) {
            AlignmentExperiment.ArmPlan plan = plans.get(index);
            String runId = "run-" + index;
            when(jobService.get(runId)).thenReturn(snapshot(
                    runId, report(runId, plan)));
            service.attachEvidence(first.experimentId(), plan.arm(), runId);
        }
        AlignmentExperiment ready = service.get(first.experimentId());
        assertThat(ready.status())
                .isEqualTo(AlignmentExperiment.Status.READY_TO_FINALIZE);

        AlignmentAblationReport report = new AlignmentAblationReport(
                "alignment-ablation-1234567890abcdef",
                RagAbEvaluationService.BENCHMARK_VERSION,
                BENCHMARK_FINGERPRINT,
                36,
                Instant.parse("2026-07-28T08:00:00Z"),
                List.of(),
                0,
                0,
                1,
                true,
                true,
                true,
                true,
                List.of(),
                "/tmp/report.json",
                "/tmp/report.md"
        );
        when(reportService.createFromEvidence(anyList())).thenReturn(report);

        AlignmentExperiment completed =
                service.finalizeExperiment(first.experimentId());
        AlignmentExperiment repeated =
                service.finalizeExperiment(first.experimentId());

        assertThat(completed.status())
                .isEqualTo(AlignmentExperiment.Status.COMPLETED);
        assertThat(completed.ablationReportId())
                .isEqualTo("alignment-ablation-1234567890abcdef");
        assertThat(repeated).isEqualTo(completed);
    }

    @Test
    void rejectsDuplicateModelAssetsAndMismatchedEvidenceIdentity() {
        stubBenchmark();
        List<AlignmentExperiment.ArmPlan> duplicate = plans();
        AlignmentExperiment.ArmPlan first = duplicate.get(0);
        AlignmentExperiment.ArmPlan second = duplicate.get(1);
        duplicate = List.of(
                first,
                new AlignmentExperiment.ArmPlan(
                        second.arm(),
                        second.modelVersion(),
                        first.modelArtifactFingerprint(),
                        second.trainingConfigFingerprint(),
                        second.rewardSchemaVersion(),
                        second.sourceDeployment()
                ),
                duplicate.get(2),
                duplicate.get(3)
        );
        List<AlignmentExperiment.ArmPlan> invalidPlans = duplicate;
        assertThatThrownBy(() -> service.create(invalidPlans))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct model artifact");

        AlignmentExperiment experiment = service.create(plans());
        AlignmentExperiment.ArmPlan planned = plans().get(0);
        AlignmentExperiment.ArmPlan different =
                new AlignmentExperiment.ArmPlan(
                        planned.arm(),
                        "different-model",
                        planned.modelArtifactFingerprint(),
                        planned.trainingConfigFingerprint(),
                        planned.rewardSchemaVersion(),
                        planned.sourceDeployment()
                );
        when(jobService.get("run-mismatch")).thenReturn(snapshot(
                "run-mismatch", report("run-mismatch", different)));

        assertThatThrownBy(() -> service.attachEvidence(
                experiment.experimentId(),
                planned.arm(),
                "run-mismatch"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime identity");
    }

    private void stubBenchmark() {
        when(evaluationService.benchmarkMetadata()).thenReturn(
                new RagAbEvaluationService.BenchmarkMetadata(
                        RagAbEvaluationService.BENCHMARK_VERSION,
                        BENCHMARK_FINGERPRINT,
                        36,
                        12,
                        24,
                        Map.of()
                ));
    }

    private List<AlignmentExperiment.ArmPlan> plans() {
        AlignmentAblationReport.ExperimentArm[] arms =
                AlignmentAblationReport.ExperimentArm.values();
        return java.util.stream.IntStream.range(0, arms.length)
                .mapToObj(index -> new AlignmentExperiment.ArmPlan(
                        arms[index],
                        "model-" + index,
                        Character.toString((char) ('a' + index)).repeat(64),
                        Character.toString((char) ('1' + index)).repeat(64),
                        "reward-v2",
                        "deployment-" + index
                ))
                .toList();
    }

    private RagAbEvaluationJobService.RunSnapshot snapshot(
            String runId,
            RagAbReport report) {
        return new RagAbEvaluationJobService.RunSnapshot(
                runId,
                "COMPLETED",
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"),
                36,
                36,
                "",
                "",
                report
        );
    }

    private RagAbReport report(
            String runId,
            AlignmentExperiment.ArmPlan plan) {
        RagAbReport.VariantSummary summary = new RagAbReport.VariantSummary(
                RagEvaluationVariant.AGENTIC_RAG_V5,
                0.8,
                0.8,
                100,
                100,
                0.01,
                36,
                0
        );
        return new RagAbReport(
                runId,
                RagAbEvaluationService.BENCHMARK_VERSION,
                BENCHMARK_FINGERPRINT,
                plan.runtimeIdentity(),
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"),
                36,
                summary,
                summary,
                summary,
                summary,
                0,
                36,
                0,
                1,
                true,
                1,
                true,
                List.of(),
                List.of(),
                List.of(),
                "",
                ""
        );
    }

    private static final class InMemoryRepository
            implements AlignmentExperimentRepository {

        private final Map<String, AlignmentExperiment> experiments =
                new LinkedHashMap<>();

        @Override
        public Optional<AlignmentExperiment> find(String experimentId) {
            return Optional.ofNullable(experiments.get(experimentId));
        }

        @Override
        public AlignmentExperiment save(AlignmentExperiment experiment) {
            experiments.put(experiment.experimentId(), experiment);
            return experiment;
        }
    }
}
