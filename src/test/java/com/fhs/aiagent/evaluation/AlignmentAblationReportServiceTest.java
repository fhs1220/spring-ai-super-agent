package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlignmentAblationReportServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsComparableFourArmReportWithRealDeltasAndReleaseGate()
            throws Exception {
        RagAbEvaluationJobService jobs = mock(RagAbEvaluationJobService.class);
        stub(jobs, "run-baseline", report("run-baseline", "fingerprint", 0.72, 0.70, 1.0));
        stub(jobs, "run-rlvr", report("run-rlvr", "fingerprint", 0.75, 0.74, 1.05));
        stub(jobs, "run-rlaif", report("run-rlaif", "fingerprint", 0.78, 0.78, 1.12));
        stub(jobs, "run-full", report("run-full", "fingerprint", 0.80, 0.82, 1.20));
        AlignmentAblationReportService service = new AlignmentAblationReportService(
                jobs,
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.toString(),
                0.02,
                1.25,
                2_000,
                0.95,
                30,
                0.03
        );

        AlignmentAblationReport result = service.create(inputs());

        assertThat(result.arms()).hasSize(4);
        assertThat(result.fullQualityDeltaVsBaseline()).isEqualTo(0.08);
        assertThat(result.fullPassRateDeltaVsBaseline()).isEqualTo(0.12);
        assertThat(result.fullCostRatioVsBaseline()).isEqualTo(1.2);
        assertThat(result.statisticalGatePassed()).isTrue();
        assertThat(result.releaseGatePassed()).isTrue();
        AlignmentAblationReport.PairedQualityComparison paired =
                result.arms().stream()
                        .filter(arm -> arm.arm()
                                == AlignmentAblationReport.ExperimentArm
                                .FULL_TRAJECTORY_GUIDED)
                        .findFirst()
                        .orElseThrow()
                        .pairedQualityVsBaseline();
        assertThat(paired.sampleCount()).isEqualTo(36);
        assertThat(paired.meanDelta()).isEqualTo(0.08);
        assertThat(paired.lowerConfidenceBound()).isEqualTo(0.08);
        assertThat(paired.upperConfidenceBound()).isEqualTo(0.08);
        assertThat(paired.wins()).isEqualTo(36);
        assertThat(paired.nonInferiorityPassed()).isTrue();
        assertThat(Path.of(result.reportPath())).isRegularFile();
        assertThat(Files.readString(Path.of(result.markdownReportPath())))
                .contains("FULL_TRAJECTORY_GUIDED", "0.08", "95% CI", "PASS");
        assertThat(service.get(result.reportId())).isEqualTo(result);
        assertThat(service.create(inputs())).isEqualTo(result);
    }

    @Test
    void rejectsAblationRunsThatDoNotUseIdenticalBenchmark() {
        RagAbEvaluationJobService jobs = mock(RagAbEvaluationJobService.class);
        stub(jobs, "run-baseline", report("run-baseline", "fingerprint-a", 0.72, 0.7, 1));
        stub(jobs, "run-rlvr", report("run-rlvr", "fingerprint-a", 0.75, 0.74, 1.05));
        stub(jobs, "run-rlaif", report("run-rlaif", "fingerprint-b", 0.78, 0.78, 1.1));
        stub(jobs, "run-full", report("run-full", "fingerprint-a", 0.80, 0.82, 1.2));
        AlignmentAblationReportService service = new AlignmentAblationReportService(
                jobs,
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.toString(),
                0.02,
                1.25,
                2_000,
                0.95,
                30,
                0.03
        );

        assertThatThrownBy(() -> service.create(inputs()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatible benchmarks");
    }

    @Test
    void smokeRunsCannotPassTheStatisticalReleaseGate() {
        RagAbEvaluationJobService jobs = mock(RagAbEvaluationJobService.class);
        stub(jobs, "run-baseline", report(
                "run-baseline", "fingerprint", 0.72, 0.70, 1.0, 2));
        stub(jobs, "run-rlvr", report(
                "run-rlvr", "fingerprint", 0.75, 0.74, 1.05, 2));
        stub(jobs, "run-rlaif", report(
                "run-rlaif", "fingerprint", 0.78, 0.78, 1.12, 2));
        stub(jobs, "run-full", report(
                "run-full", "fingerprint", 0.80, 0.82, 1.20, 2));
        AlignmentAblationReportService service = new AlignmentAblationReportService(
                jobs,
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.toString(),
                0.02,
                1.25,
                2_000,
                0.95,
                30,
                0.03
        );

        AlignmentAblationReport result = service.create(inputs());

        assertThat(result.statisticalGatePassed()).isFalse();
        assertThat(result.releaseGatePassed()).isFalse();
        assertThat(result.gateFailures())
                .anyMatch(failure -> failure.contains("低于统计门槛 30"));
    }

    private List<AlignmentAblationReport.ArmInput> inputs() {
        return List.of(
                new AlignmentAblationReport.ArmInput(
                        AlignmentAblationReport.ExperimentArm.BASELINE_STATIC_REWARD,
                        "run-baseline",
                        "model-baseline"
                ),
                new AlignmentAblationReport.ArmInput(
                        AlignmentAblationReport.ExperimentArm.RLVR_ONLY,
                        "run-rlvr",
                        "model-rlvr"
                ),
                new AlignmentAblationReport.ArmInput(
                        AlignmentAblationReport.ExperimentArm.RLVR_RLAIF,
                        "run-rlaif",
                        "model-rlaif"
                ),
                new AlignmentAblationReport.ArmInput(
                        AlignmentAblationReport.ExperimentArm.FULL_TRAJECTORY_GUIDED,
                        "run-full",
                        "model-full"
                )
        );
    }

    private void stub(RagAbEvaluationJobService jobs,
                      String runId,
                      RagAbReport report) {
        when(jobs.get(runId)).thenReturn(new RagAbEvaluationJobService.RunSnapshot(
                runId,
                "COMPLETED",
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"),
                report.caseCount(),
                report.caseCount(),
                "",
                "",
                report
        ));
    }

    private RagAbReport report(String runId,
                               String fingerprint,
                               double quality,
                               double passRate,
                               double cost) {
        return report(runId, fingerprint, quality, passRate, cost, 36);
    }

    private RagAbReport report(String runId,
                               String fingerprint,
                               double quality,
                               double passRate,
                               double cost,
                               int caseCount) {
        RagAbReport.VariantSummary summary = new RagAbReport.VariantSummary(
                RagEvaluationVariant.AGENTIC_RAG_V5,
                quality,
                passRate,
                1000,
                10_000,
                cost,
                caseCount,
                0
        );
        List<RagAbReport.CaseComparison> cases = IntStream.range(0, caseCount)
                .mapToObj(index -> comparison(
                        "case-%02d".formatted(index),
                        quality,
                        cost / Math.max(1, caseCount)
                ))
                .toList();
        return new RagAbReport(
                runId,
                RagAbEvaluationService.BENCHMARK_VERSION,
                fingerprint,
                new RagAbReport.RuntimeIdentity(
                        runId.replace("run-", "model-"),
                        artifactFingerprint(runId),
                        "b".repeat(64),
                        "reward-v2",
                        "test"
                ),
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T01:00:00Z"),
                caseCount,
                summary,
                summary,
                summary,
                summary,
                20,
                10,
                6,
                0.9,
                true,
                1.0,
                true,
                List.of(),
                List.of(),
                cases,
                "",
                ""
        );
    }

    private String artifactFingerprint(String runId) {
        return switch (runId) {
            case "run-baseline" -> "a".repeat(64);
            case "run-rlvr" -> "b".repeat(64);
            case "run-rlaif" -> "c".repeat(64);
            case "run-full" -> "d".repeat(64);
            default -> "e".repeat(64);
        };
    }

    private RagAbReport.CaseComparison comparison(String caseId,
                                                  double quality,
                                                  double cost) {
        RagAbReport.VariantResult result = new RagAbReport.VariantResult(
                new RagVariantExecution(
                        RagEvaluationVariant.AGENTIC_RAG_V5,
                        "answer",
                        1_000,
                        100,
                        cost,
                        true,
                        "SINGLE_AGENT",
                        ""
                ),
                new RagAnswerScorer.Score(
                        quality,
                        quality,
                        1,
                        1,
                        1,
                        1,
                        true
                )
        );
        return new RagAbReport.CaseComparison(
                caseId,
                "question",
                List.of("test"),
                result,
                result,
                result,
                result,
                "TIE",
                0,
                false
        );
    }
}
