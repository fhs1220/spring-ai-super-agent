package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class AlignmentAblationReportService {

    private final RagAbEvaluationJobService evaluationJobService;

    private final ObjectMapper objectMapper;

    private final Path reportDirectory;

    private final double maximumQualityRegression;

    private final double maximumCostRatio;

    public AlignmentAblationReportService(
            RagAbEvaluationJobService evaluationJobService,
            ObjectMapper objectMapper,
            @Value("${agent.evaluation.report-directory:tmp/evaluation}")
            String reportDirectory,
            @Value("${agent.rl.alignment.ablation.maximum-quality-regression:0.02}")
            double maximumQualityRegression,
            @Value("${agent.rl.alignment.ablation.maximum-cost-ratio:1.25}")
            double maximumCostRatio) {
        this.evaluationJobService = evaluationJobService;
        this.objectMapper = objectMapper;
        this.reportDirectory = Path.of(reportDirectory)
                .toAbsolutePath()
                .normalize()
                .resolve("alignment-ablation");
        this.maximumQualityRegression = Math.max(0, maximumQualityRegression);
        this.maximumCostRatio = Math.max(0, maximumCostRatio);
    }

    public AlignmentAblationReport create(
            List<AlignmentAblationReport.ArmInput> inputs) {
        Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs =
                resolveRuns(inputs);
        ArmRun baseline = runs.get(
                AlignmentAblationReport.ExperimentArm.BASELINE_STATIC_REWARD);
        ArmRun full = runs.get(
                AlignmentAblationReport.ExperimentArm.FULL_TRAJECTORY_GUIDED);
        validateComparability(runs.values().stream().toList());

        boolean costComparable = runs.values().stream()
                .allMatch(run -> run.report().candidate().usageMeasuredCases()
                        == run.report().caseCount())
                && baseline.report().candidate().estimatedCostCny() > 0;
        List<AlignmentAblationReport.ArmMetrics> metrics =
                java.util.Arrays.stream(
                                AlignmentAblationReport.ExperimentArm.values())
                        .map(arm -> metrics(
                                runs.get(arm), baseline, costComparable))
                        .toList();
        double qualityDelta = round(
                full.report().candidate().averageScore()
                        - baseline.report().candidate().averageScore());
        double passRateDelta = round(
                full.report().candidate().passRate()
                        - baseline.report().candidate().passRate());
        double costRatio = costComparable
                ? ratio(
                        full.report().candidate().estimatedCostCny(),
                        baseline.report().candidate().estimatedCostCny())
                : 0;
        List<String> gateFailures = gateFailures(
                full.report(), qualityDelta, costComparable, costRatio);

        String reportId = "alignment-ablation-" + UUID.randomUUID();
        Path jsonPath = reportDirectory.resolve(reportId + ".json");
        Path markdownPath = reportDirectory.resolve(reportId + ".md");
        AlignmentAblationReport report = new AlignmentAblationReport(
                reportId,
                baseline.report().benchmarkVersion(),
                baseline.report().benchmarkFingerprint(),
                baseline.report().caseCount(),
                Instant.now(),
                metrics,
                qualityDelta,
                passRateDelta,
                costRatio,
                true,
                costComparable,
                gateFailures.isEmpty(),
                gateFailures,
                jsonPath.toString(),
                markdownPath.toString()
        );
        persist(report, jsonPath, markdownPath);
        return report;
    }

    public AlignmentAblationReport get(String reportId) {
        if (reportId == null || !reportId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Invalid reportId");
        }
        Path path = reportDirectory.resolve(reportId + ".json");
        if (!Files.exists(path)) {
            throw new NoSuchElementException(
                    "Alignment ablation report not found: " + reportId);
        }
        try {
            return objectMapper.readValue(path.toFile(), AlignmentAblationReport.class);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read alignment ablation report " + reportId,
                    exception);
        }
    }

    private Map<AlignmentAblationReport.ExperimentArm, ArmRun> resolveRuns(
            List<AlignmentAblationReport.ArmInput> inputs) {
        if (inputs == null
                || inputs.size() != AlignmentAblationReport.ExperimentArm.values().length) {
            throw new IllegalArgumentException(
                    "Exactly four distinct experiment arms are required");
        }
        Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs =
                new EnumMap<>(AlignmentAblationReport.ExperimentArm.class);
        for (AlignmentAblationReport.ArmInput input : inputs) {
            if (input == null || input.arm() == null
                    || !hasText(input.evaluationRunId())
                    || !hasText(input.modelVersion())) {
                throw new IllegalArgumentException(
                        "Each arm requires arm, evaluationRunId and modelVersion");
            }
            RagAbEvaluationJobService.RunSnapshot snapshot =
                    evaluationJobService.get(input.evaluationRunId());
            if (!"COMPLETED".equals(snapshot.status()) || snapshot.report() == null) {
                throw new IllegalArgumentException(
                        "Evaluation run is not completed: " + input.evaluationRunId());
            }
            if (runs.putIfAbsent(
                    input.arm(), new ArmRun(input, snapshot.report())) != null) {
                throw new IllegalArgumentException(
                        "Duplicate experiment arm: " + input.arm());
            }
        }
        if (runs.size() != AlignmentAblationReport.ExperimentArm.values().length) {
            throw new IllegalArgumentException("All four experiment arms are required");
        }
        return Map.copyOf(runs);
    }

    private void validateComparability(List<ArmRun> runs) {
        ArmRun first = runs.get(0);
        List<String> incompatible = runs.stream()
                .filter(run -> !first.report().benchmarkVersion()
                        .equals(run.report().benchmarkVersion())
                        || !first.report().benchmarkFingerprint()
                        .equals(run.report().benchmarkFingerprint())
                        || first.report().caseCount() != run.report().caseCount())
                .map(run -> run.input().arm().name())
                .toList();
        if (!incompatible.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ablation runs use incompatible benchmarks: " + incompatible);
        }
    }

    private AlignmentAblationReport.ArmMetrics metrics(
            ArmRun run,
            ArmRun baseline,
            boolean costComparable) {
        RagAbReport.VariantSummary candidate = run.report().candidate();
        RagAbReport.VariantSummary baselineCandidate = baseline.report().candidate();
        return new AlignmentAblationReport.ArmMetrics(
                run.input().arm(),
                run.input().evaluationRunId(),
                run.input().modelVersion(),
                candidate.averageScore(),
                candidate.passRate(),
                candidate.averageLatencyMs(),
                candidate.totalTokens(),
                candidate.estimatedCostCny(),
                run.report().routeAccuracy(),
                candidate.failureCount(),
                run.report().regressionGatePassed(),
                round(candidate.averageScore() - baselineCandidate.averageScore()),
                round(candidate.passRate() - baselineCandidate.passRate()),
                costComparable
                        ? ratio(
                                candidate.estimatedCostCny(),
                                baselineCandidate.estimatedCostCny())
                        : 0
        );
    }

    private List<String> gateFailures(RagAbReport full,
                                      double qualityDelta,
                                      boolean costComparable,
                                      double costRatio) {
        List<String> failures = new ArrayList<>();
        if (qualityDelta < -maximumQualityRegression) {
            failures.add("完整方案相对静态 Reward 基线的质量回退超过 "
                    + maximumQualityRegression);
        }
        if (!full.regressionGatePassed()) {
            failures.add("完整方案未通过原有固定基准发布门禁");
        }
        if (full.candidate().failureCount() > 0) {
            failures.add("完整方案存在执行失败样本");
        }
        if (costComparable && costRatio > maximumCostRatio) {
            failures.add("完整方案成本为基线 %.4f 倍，超过 %.4f"
                    .formatted(costRatio, maximumCostRatio));
        }
        return List.copyOf(failures);
    }

    private void persist(AlignmentAblationReport report,
                         Path jsonPath,
                         Path markdownPath) {
        try {
            Files.createDirectories(reportDirectory);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(jsonPath.toFile(), report);
            Files.writeString(
                    markdownPath,
                    renderMarkdown(report),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to persist alignment ablation report",
                    exception);
        }
    }

    private String renderMarkdown(AlignmentAblationReport report) {
        StringBuilder markdown = new StringBuilder()
                .append("# Human-light RLAIF 消融报告\n\n")
                .append("- Report: `").append(report.reportId()).append("`\n")
                .append("- Benchmark: `").append(report.benchmarkVersion())
                .append("` / `").append(report.benchmarkFingerprint()).append("`\n")
                .append("- Cases: ").append(report.caseCount()).append("\n")
                .append("- Release gate: **")
                .append(report.releaseGatePassed() ? "PASS" : "FAIL")
                .append("**\n\n")
                .append("| Arm | Model | Quality | Δ quality | Pass rate | Δ pass | Cost | Cost ratio | Failures |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        report.arms().forEach(arm -> markdown
                .append("| ").append(arm.arm())
                .append(" | ").append(arm.modelVersion())
                .append(" | ").append(arm.averageQuality())
                .append(" | ").append(arm.qualityDeltaVsBaseline())
                .append(" | ").append(arm.passRate())
                .append(" | ").append(arm.passRateDeltaVsBaseline())
                .append(" | ").append(arm.estimatedCostCny())
                .append(" | ").append(arm.costRatioVsBaseline())
                .append(" | ").append(arm.failureCount())
                .append(" |\n"));
        if (!report.gateFailures().isEmpty()) {
            markdown.append("\n## Gate failures\n\n");
            report.gateFailures().forEach(
                    failure -> markdown.append("- ").append(failure).append("\n"));
        }
        return markdown.toString();
    }

    private double ratio(double value, double baseline) {
        if (baseline <= 0) {
            throw new IllegalArgumentException(
                    "baseline must be positive for a comparable cost ratio");
        }
        return round(value / baseline);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record ArmRun(
            AlignmentAblationReport.ArmInput input,
            RagAbReport report
    ) {
    }
}
