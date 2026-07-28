package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class AlignmentAblationReportService {

    private final RagAbEvaluationJobService evaluationJobService;

    private final ObjectMapper objectMapper;

    private final Path reportDirectory;

    private final double maximumQualityRegression;

    private final double maximumCostRatio;

    private final int bootstrapIterations;

    private final double bootstrapConfidenceLevel;

    private final int minimumPairedCases;

    private final double pairedWinDelta;

    private final PairedBootstrapAnalyzer pairedBootstrapAnalyzer =
            new PairedBootstrapAnalyzer();

    public AlignmentAblationReportService(
            RagAbEvaluationJobService evaluationJobService,
            ObjectMapper objectMapper,
            @Value("${agent.evaluation.report-directory:tmp/evaluation}")
            String reportDirectory,
            @Value("${agent.rl.alignment.ablation.maximum-quality-regression:0.02}")
            double maximumQualityRegression,
            @Value("${agent.rl.alignment.ablation.maximum-cost-ratio:1.25}")
            double maximumCostRatio,
            @Value("${agent.rl.alignment.ablation.bootstrap-iterations:10000}")
            int bootstrapIterations,
            @Value("${agent.rl.alignment.ablation.confidence-level:0.95}")
            double bootstrapConfidenceLevel,
            @Value("${agent.rl.alignment.ablation.minimum-paired-cases:30}")
            int minimumPairedCases,
            @Value("${agent.rl.alignment.ablation.paired-win-delta:0.03}")
            double pairedWinDelta) {
        this.evaluationJobService = evaluationJobService;
        this.objectMapper = objectMapper;
        this.reportDirectory = Path.of(reportDirectory)
                .toAbsolutePath()
                .normalize()
                .resolve("alignment-ablation");
        this.maximumQualityRegression = Math.max(0, maximumQualityRegression);
        this.maximumCostRatio = Math.max(0, maximumCostRatio);
        this.bootstrapIterations = Math.max(100, bootstrapIterations);
        this.bootstrapConfidenceLevel = Math.max(
                0.5, Math.min(0.999, bootstrapConfidenceLevel));
        this.minimumPairedCases = Math.max(2, minimumPairedCases);
        this.pairedWinDelta = Math.max(0, pairedWinDelta);
    }

    public AlignmentAblationReport create(
            List<AlignmentAblationReport.ArmInput> inputs) {
        Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs =
                resolveRuns(inputs);
        return createResolved(runs);
    }

    public AlignmentAblationReport createFromEvidence(
            List<EvidenceInput> evidenceInputs) {
        if (evidenceInputs == null
                || evidenceInputs.size()
                != AlignmentAblationReport.ExperimentArm.values().length) {
            throw new IllegalArgumentException(
                    "Exactly four distinct experiment arms are required");
        }
        Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs =
                new EnumMap<>(AlignmentAblationReport.ExperimentArm.class);
        for (EvidenceInput evidence : evidenceInputs) {
            if (evidence == null
                    || evidence.input() == null
                    || evidence.report() == null) {
                throw new IllegalArgumentException(
                        "Each experiment arm requires persisted evidence");
            }
            AlignmentAblationReport.ArmInput input = evidence.input();
            if (input.arm() == null
                    || !hasText(input.evaluationRunId())
                    || !hasText(input.modelVersion())
                    || !input.evaluationRunId().equals(
                            evidence.report().runId())) {
                throw new IllegalArgumentException(
                        "Persisted evidence does not match its arm input");
            }
            if (runs.putIfAbsent(
                    input.arm(), new ArmRun(input, evidence.report())) != null) {
                throw new IllegalArgumentException(
                        "Duplicate experiment arm: " + input.arm());
            }
        }
        if (runs.size() != AlignmentAblationReport.ExperimentArm.values().length) {
            throw new IllegalArgumentException("All four experiment arms are required");
        }
        validateRuntimeIdentities(runs);
        return createResolved(Map.copyOf(runs));
    }

    private AlignmentAblationReport createResolved(
            Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs) {
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
        AlignmentAblationReport.ArmMetrics fullMetrics = metrics.stream()
                .filter(metric -> metric.arm()
                        == AlignmentAblationReport.ExperimentArm
                        .FULL_TRAJECTORY_GUIDED)
                .findFirst()
                .orElseThrow();
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
                full.report(),
                fullMetrics.pairedQualityVsBaseline(),
                qualityDelta,
                costComparable,
                costRatio
        );
        boolean statisticalGatePassed =
                fullMetrics.pairedQualityVsBaseline().comparable()
                        && fullMetrics.pairedQualityVsBaseline().enoughSamples()
                        && fullMetrics.pairedQualityVsBaseline()
                        .nonInferiorityPassed();

        String reportId = reportId(runs);
        Path jsonPath = reportDirectory.resolve(reportId + ".json");
        Path markdownPath = reportDirectory.resolve(reportId + ".md");
        if (Files.isRegularFile(jsonPath)) {
            return get(reportId);
        }
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
                statisticalGatePassed,
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
        validateRuntimeIdentities(runs);
        return Map.copyOf(runs);
    }

    private void validateRuntimeIdentities(
            Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs) {
        Set<String> modelArtifacts = new HashSet<>();
        Set<String> evaluationRuns = new HashSet<>();
        for (ArmRun run : runs.values()) {
            RagAbReport.RuntimeIdentity identity =
                    run.report().runtimeIdentity();
            if (!identity.isVerifiable()) {
                throw new IllegalArgumentException(
                        "Evaluation run has no verifiable runtime identity: "
                                + run.input().evaluationRunId());
            }
            if (!identity.modelVersion().equals(run.input().modelVersion())) {
                throw new IllegalArgumentException(
                        "Declared modelVersion does not match evaluation evidence: "
                                + run.input().arm());
            }
            if (!modelArtifacts.add(
                    identity.modelArtifactFingerprint().toLowerCase())) {
                throw new IllegalArgumentException(
                        "Each experiment arm must use a distinct model artifact");
            }
            if (!evaluationRuns.add(run.input().evaluationRunId())) {
                throw new IllegalArgumentException(
                        "The same evaluation run cannot be reused across arms");
            }
        }
    }

    private String reportId(
            Map<AlignmentAblationReport.ExperimentArm, ArmRun> runs) {
        StringBuilder canonical = new StringBuilder();
        java.util.Arrays.stream(AlignmentAblationReport.ExperimentArm.values())
                .forEach(arm -> {
                    ArmRun run = runs.get(arm);
                    canonical.append(arm.name()).append('|')
                            .append(run.input().evaluationRunId()).append('|')
                            .append(run.input().modelVersion()).append('|')
                            .append(run.report().benchmarkFingerprint()).append('\n');
                });
        try {
            String fingerprint = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.toString().getBytes(StandardCharsets.UTF_8)));
            return "alignment-ablation-" + fingerprint.substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
                        : 0,
                pairedComparison(run, baseline)
        );
    }

    private List<String> gateFailures(RagAbReport full,
                                      AlignmentAblationReport.PairedQualityComparison
                                              paired,
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
        if (!paired.comparable()) {
            failures.add("完整方案缺少可配对的逐样本统计证据："
                    + paired.unavailableReason());
        } else if (!paired.enoughSamples()) {
            failures.add("完整方案配对样本数 %d 低于统计门槛 %d"
                    .formatted(paired.sampleCount(), minimumPairedCases));
        } else if (!paired.nonInferiorityPassed()) {
            failures.add(
                    "完整方案质量差值 %.0f%% 置信区间下界 %.4f 低于非劣界 -%.4f"
                            .formatted(
                                    paired.confidenceLevel() * 100,
                                    paired.lowerConfidenceBound(),
                                    paired.nonInferiorityMargin()
                            ));
        }
        return List.copyOf(failures);
    }

    private AlignmentAblationReport.PairedQualityComparison pairedComparison(
            ArmRun run,
            ArmRun baseline) {
        String seedKey = baseline.report().benchmarkFingerprint()
                + ":" + run.input().arm().name();
        Map<String, Double> baselineScores =
                candidateScoresByCaseId(baseline.report());
        Map<String, Double> candidateScores =
                candidateScoresByCaseId(run.report());
        if (baselineScores.size() != baseline.report().caseCount()
                || candidateScores.size() != run.report().caseCount()) {
            return pairedBootstrapAnalyzer.unavailable(
                    seedKey,
                    bootstrapIterations,
                    bootstrapConfidenceLevel,
                    maximumQualityRegression,
                    "报告未包含完整逐样本结果"
            );
        }
        if (!baselineScores.keySet().equals(candidateScores.keySet())) {
            return pairedBootstrapAnalyzer.unavailable(
                    seedKey,
                    bootstrapIterations,
                    bootstrapConfidenceLevel,
                    maximumQualityRegression,
                    "四组运行的 caseId 集合不一致"
            );
        }
        List<String> caseIds = baselineScores.keySet().stream()
                .sorted()
                .toList();
        return pairedBootstrapAnalyzer.analyze(
                seedKey,
                caseIds.stream().map(baselineScores::get).toList(),
                caseIds.stream().map(candidateScores::get).toList(),
                bootstrapIterations,
                bootstrapConfidenceLevel,
                pairedWinDelta,
                maximumQualityRegression,
                minimumPairedCases
        );
    }

    private Map<String, Double> candidateScoresByCaseId(RagAbReport report) {
        Map<String, Double> scores = new LinkedHashMap<>();
        report.cases().stream()
                .sorted(Comparator.comparing(RagAbReport.CaseComparison::caseId))
                .forEach(comparison -> {
                    if (scores.putIfAbsent(
                            comparison.caseId(),
                            comparison.candidate().score().total()) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate caseId in evaluation report: "
                                        + comparison.caseId());
                    }
                });
        return Map.copyOf(scores);
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
                .append("**\n")
                .append("- Statistical gate: **")
                .append(report.statisticalGatePassed() ? "PASS" : "FAIL")
                .append("**\n\n")
                .append("| Arm | Model | Quality | Δ quality | 95% CI | W/T/L | Sign p | P(Δ>0) | Non-inferior | Cost ratio |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        report.arms().forEach(arm -> markdown
                .append("| ").append(arm.arm())
                .append(" | ").append(arm.modelVersion())
                .append(" | ").append(arm.averageQuality())
                .append(" | ").append(arm.qualityDeltaVsBaseline())
                .append(" | ").append(confidenceInterval(
                        arm.pairedQualityVsBaseline()))
                .append(" | ").append(winTieLoss(
                        arm.pairedQualityVsBaseline()))
                .append(" | ").append(
                        arm.pairedQualityVsBaseline().twoSidedSignTestPValue())
                .append(" | ").append(
                        arm.pairedQualityVsBaseline().probabilityOfImprovement())
                .append(" | ").append(
                        arm.pairedQualityVsBaseline().nonInferiorityPassed())
                .append(" | ").append(arm.costRatioVsBaseline())
                .append(" |\n"));
        if (!report.gateFailures().isEmpty()) {
            markdown.append("\n## Gate failures\n\n");
            report.gateFailures().forEach(
                    failure -> markdown.append("- ").append(failure).append("\n"));
        }
        return markdown.toString();
    }

    private String confidenceInterval(
            AlignmentAblationReport.PairedQualityComparison comparison) {
        if (!comparison.comparable()) {
            return "N/A";
        }
        return "[%.4f, %.4f]".formatted(
                comparison.lowerConfidenceBound(),
                comparison.upperConfidenceBound()
        );
    }

    private String winTieLoss(
            AlignmentAblationReport.PairedQualityComparison comparison) {
        if (!comparison.comparable()) {
            return "N/A";
        }
        return "%d/%d/%d".formatted(
                comparison.wins(),
                comparison.ties(),
                comparison.losses()
        );
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

    public record EvidenceInput(
            AlignmentAblationReport.ArmInput input,
            RagAbReport report
    ) {
    }
}
