package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.AgentRunCancelledException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class RagAbEvaluationService {

    public static final String BENCHMARK_VERSION = "love-rag-benchmark-v2";

    private static final double WIN_DELTA = 0.03;

    private static final double CRITICAL_REGRESSION_DELTA = -0.15;

    private static final double CASE_PASS_SCORE = 0.7;

    private static final List<RagEvaluationVariant> VARIANTS = List.of(
            RagEvaluationVariant.TRADITIONAL_RAG,
            RagEvaluationVariant.AGENTIC_SINGLE_AGENT,
            RagEvaluationVariant.AGENTIC_MULTI_AGENT,
            RagEvaluationVariant.AGENTIC_RAG_V5
    );

    private final RagEvaluationVariantExecutor variantExecutor;

    private final RagAnswerScorer scorer;

    private final ObjectMapper objectMapper;

    private final Resource benchmarkResource;

    private final Path reportDirectory;

    private final int configuredMaximumCases;

    private final int minimumBenchmarkCases;

    private final double candidateMinimumScore;

    private final double maximumQualityRegression;

    private final double minimumRouteAccuracy;

    private final double maximumAdaptiveOracleCostRatio;

    private final RagAbReport.RuntimeIdentity runtimeIdentity;

    public RagAbEvaluationService(
            RagEvaluationVariantExecutor variantExecutor,
            RagAnswerScorer scorer,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${agent.evaluation.benchmark:classpath:evaluation/love-rag-ab.jsonl}")
            String benchmark,
            @Value("${agent.evaluation.report-directory:tmp/evaluation}")
            String reportDirectory,
            @Value("${agent.evaluation.maximum-cases:100}") int maximumCases,
            @Value("${agent.evaluation.minimum-benchmark-cases:30}")
            int minimumBenchmarkCases,
            @Value("${agent.evaluation.candidate-minimum-score:0.72}")
            double candidateMinimumScore,
            @Value("${agent.evaluation.maximum-quality-regression:0.02}")
            double maximumQualityRegression,
            @Value("${agent.evaluation.minimum-route-accuracy:0.8}")
            double minimumRouteAccuracy,
            @Value("${agent.evaluation.maximum-adaptive-oracle-cost-ratio:1.10}")
            double maximumAdaptiveOracleCostRatio,
            @Value("${agent.evaluation.model-version:qwen-plus}")
            String modelVersion,
            @Value("${agent.evaluation.model-artifact-fingerprint:UNSPECIFIED}")
            String modelArtifactFingerprint,
            @Value("${agent.evaluation.training-config-fingerprint:UNSPECIFIED}")
            String trainingConfigFingerprint,
            @Value("${agent.evaluation.reward-schema-version:human-light-rlvr-v2}")
            String rewardSchemaVersion,
            @Value("${agent.evaluation.source-deployment:local}")
            String sourceDeployment) {
        this.variantExecutor = Objects.requireNonNull(
                variantExecutor, "variantExecutor");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.benchmarkResource = resourceLoader.getResource(benchmark);
        this.reportDirectory = Path.of(reportDirectory).toAbsolutePath().normalize();
        this.configuredMaximumCases = Math.max(1, maximumCases);
        this.minimumBenchmarkCases = Math.max(1, minimumBenchmarkCases);
        this.candidateMinimumScore = clamp(candidateMinimumScore);
        this.maximumQualityRegression = Math.max(0, maximumQualityRegression);
        this.minimumRouteAccuracy = clamp(minimumRouteAccuracy);
        this.maximumAdaptiveOracleCostRatio =
                Math.max(0, maximumAdaptiveOracleCostRatio);
        this.runtimeIdentity = new RagAbReport.RuntimeIdentity(
                modelVersion,
                modelArtifactFingerprint,
                trainingConfigFingerprint,
                rewardSchemaVersion,
                sourceDeployment
        );
    }

    public RagAbReport evaluate(
            String runId,
            int requestedCases,
            BooleanSupplier cancellationRequested,
            Consumer<Progress> progressListener) {
        Instant startedAt = Instant.now();
        BooleanSupplier cancellation = cancellationRequested == null
                ? () -> false
                : cancellationRequested;
        Consumer<Progress> progress = progressListener == null
                ? ignored -> {
                }
                : progressListener;
        List<RagEvaluationCase> benchmarkCases = loadCases();
        String benchmarkFingerprint = fingerprint(benchmarkCases);
        int limit = requestedCases <= 0
                ? benchmarkCases.size()
                : Math.min(requestedCases, benchmarkCases.size());
        limit = Math.min(limit, configuredMaximumCases);
        List<RagEvaluationCase> cases =
                List.copyOf(benchmarkCases.subList(0, limit));

        List<RagAbReport.CaseComparison> comparisons = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            throwIfCancelled(cancellation);
            RagEvaluationCase evaluationCase = cases.get(index);
            progress.accept(new Progress(
                    runId, index, cases.size(), evaluationCase.id(), "RUNNING"));
            Map<RagEvaluationVariant, RagAbReport.VariantResult> results =
                    executeMatrix(
                            evaluationCase,
                            runId,
                            index,
                            cancellation
                    );
            RagAbReport.VariantResult baseline =
                    results.get(RagEvaluationVariant.TRADITIONAL_RAG);
            RagAbReport.VariantResult candidate =
                    results.get(RagEvaluationVariant.AGENTIC_RAG_V5);
            double delta = round(
                    candidate.score().total() - baseline.score().total());
            String winner = delta > WIN_DELTA
                    ? "CANDIDATE"
                    : delta < -WIN_DELTA ? "BASELINE" : "TIE";
            boolean criticalRegression = delta < CRITICAL_REGRESSION_DELTA
                    || baseline.execution().succeeded()
                    && !candidate.execution().succeeded();
            comparisons.add(new RagAbReport.CaseComparison(
                    evaluationCase.id(),
                    evaluationCase.question(),
                    evaluationCase.tags(),
                    baseline,
                    results.get(RagEvaluationVariant.AGENTIC_SINGLE_AGENT),
                    results.get(RagEvaluationVariant.AGENTIC_MULTI_AGENT),
                    candidate,
                    winner,
                    delta,
                    criticalRegression
            ));
            progress.accept(new Progress(
                    runId,
                    index + 1,
                    cases.size(),
                    evaluationCase.id(),
                    "COMPLETED"
            ));
        }

        RagAbReport.VariantSummary baselineSummary = summarize(
                RagEvaluationVariant.TRADITIONAL_RAG, comparisons);
        RagAbReport.VariantSummary singleSummary = summarize(
                RagEvaluationVariant.AGENTIC_SINGLE_AGENT, comparisons);
        RagAbReport.VariantSummary multiSummary = summarize(
                RagEvaluationVariant.AGENTIC_MULTI_AGENT, comparisons);
        RagAbReport.VariantSummary candidateSummary = summarize(
                RagEvaluationVariant.AGENTIC_RAG_V5, comparisons);
        double routeAccuracy = round(comparisons.stream()
                .filter(comparison -> comparison.candidate().score().routeCorrect())
                .count() / (double) Math.max(1, comparisons.size()));
        CostComparison costComparison = compareAdaptiveToOracle(comparisons);
        List<String> gateFailures = gateFailures(
                comparisons,
                baselineSummary,
                candidateSummary,
                routeAccuracy,
                costComparison
        );
        Path jsonPath = reportDirectory.resolve(runId + ".json");
        Path markdownPath = reportDirectory.resolve(runId + ".md");
        RagAbReport report = new RagAbReport(
                runId,
                BENCHMARK_VERSION,
                benchmarkFingerprint,
                runtimeIdentity,
                startedAt,
                Instant.now(),
                comparisons.size(),
                baselineSummary,
                singleSummary,
                multiSummary,
                candidateSummary,
                countWinners(comparisons, "CANDIDATE"),
                countWinners(comparisons, "TIE"),
                countWinners(comparisons, "BASELINE"),
                routeAccuracy,
                costComparison.comparable(),
                costComparison.ratio(),
                gateFailures.isEmpty(),
                gateFailures,
                summarizeTags(comparisons),
                List.copyOf(comparisons),
                jsonPath.toString(),
                markdownPath.toString()
        );
        persist(jsonPath, markdownPath, report);
        return report;
    }

    public List<RagEvaluationCase> loadCases() {
        if (!benchmarkResource.exists()) {
            throw new IllegalStateException(
                    "Evaluation benchmark does not exist: " + benchmarkResource);
        }
        List<RagEvaluationCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                benchmarkResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String normalized = line.trim();
                if (normalized.isBlank() || normalized.startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(objectMapper.readValue(
                            normalized, RagEvaluationCase.class));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException(
                            "Invalid benchmark JSONL at line " + lineNumber,
                            exception
                    );
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read evaluation benchmark", exception);
        }
        validateCases(cases);
        return List.copyOf(cases);
    }

    public BenchmarkMetadata benchmarkMetadata() {
        List<RagEvaluationCase> cases = loadCases();
        Map<String, Long> tagCounts = new LinkedHashMap<>();
        cases.stream()
                .flatMap(evaluationCase -> evaluationCase.tags().stream())
                .sorted()
                .forEach(tag -> tagCounts.merge(tag, 1L, Long::sum));
        long singleCases = cases.stream()
                .filter(evaluationCase -> "SINGLE_AGENT".equals(
                        evaluationCase.expectedExecutionMode()))
                .count();
        long multiCases = cases.stream()
                .filter(evaluationCase -> "ADAPTIVE_MULTI_AGENT".equals(
                        evaluationCase.expectedExecutionMode()))
                .count();
        return new BenchmarkMetadata(
                BENCHMARK_VERSION,
                fingerprint(cases),
                cases.size(),
                singleCases,
                multiCases,
                Map.copyOf(tagCounts)
        );
    }

    private Map<RagEvaluationVariant, RagAbReport.VariantResult> executeMatrix(
            RagEvaluationCase evaluationCase,
            String runId,
            int caseIndex,
            BooleanSupplier cancellation) {
        Map<RagEvaluationVariant, RagAbReport.VariantResult> results =
                new LinkedHashMap<>();
        for (int offset = 0; offset < VARIANTS.size(); offset++) {
            RagEvaluationVariant variant = VARIANTS.get(
                    (caseIndex + offset) % VARIANTS.size());
            throwIfCancelled(cancellation);
            RagVariantExecution execution = execute(
                    variant, evaluationCase, runId);
            results.put(
                    variant,
                    new RagAbReport.VariantResult(
                            execution,
                            scorer.score(evaluationCase, execution)
                    )
            );
        }
        return Map.copyOf(results);
    }

    private RagVariantExecution execute(
            RagEvaluationVariant variant,
            RagEvaluationCase evaluationCase,
            String runId) {
        long startedAt = System.nanoTime();
        String chatId = "eval-%s-%s-%s".formatted(
                runId,
                evaluationCase.id(),
                variant.name().toLowerCase()
        );
        try {
            return variantExecutor.execute(variant, evaluationCase, chatId);
        } catch (RuntimeException exception) {
            if (AgentRunCancelledException.isCancellation(exception)) {
                throw exception;
            }
            return new RagVariantExecution(
                    variant,
                    "",
                    elapsedMs(startedAt),
                    0,
                    0,
                    false,
                    "",
                    exception.getClass().getSimpleName() + ": "
                            + Objects.toString(exception.getMessage(), "")
            );
        }
    }

    private RagAbReport.VariantSummary summarize(
            RagEvaluationVariant variant,
            List<RagAbReport.CaseComparison> comparisons) {
        List<RagAbReport.VariantResult> results = comparisons.stream()
                .map(comparison -> result(comparison, variant))
                .toList();
        double averageScore = results.stream()
                .mapToDouble(item -> item.score().total())
                .average()
                .orElse(0);
        double passRate = results.stream()
                .filter(item -> item.score().total() >= CASE_PASS_SCORE)
                .count() / (double) Math.max(1, results.size());
        double averageLatency = results.stream()
                .mapToLong(item -> item.execution().latencyMs())
                .average()
                .orElse(0);
        return new RagAbReport.VariantSummary(
                variant,
                round(averageScore),
                round(passRate),
                round(averageLatency),
                results.stream()
                        .mapToLong(item -> item.execution().totalTokens())
                        .sum(),
                roundCost(results.stream()
                        .mapToDouble(item -> item.execution().estimatedCostCny())
                        .sum()),
                (int) results.stream()
                        .filter(item -> item.execution().usageAvailable())
                        .count(),
                (int) results.stream()
                        .filter(item -> !item.execution().succeeded())
                        .count()
        );
    }

    private RagAbReport.VariantResult result(
            RagAbReport.CaseComparison comparison,
            RagEvaluationVariant variant) {
        return switch (variant) {
            case TRADITIONAL_RAG -> comparison.baseline();
            case AGENTIC_SINGLE_AGENT -> comparison.forcedSingle();
            case AGENTIC_MULTI_AGENT -> comparison.forcedMulti();
            case AGENTIC_RAG_V5 -> comparison.candidate();
        };
    }

    private CostComparison compareAdaptiveToOracle(
            List<RagAbReport.CaseComparison> comparisons) {
        boolean comparable = comparisons.stream().allMatch(comparison -> {
            RagAbReport.VariantResult oracle = oracle(comparison);
            return comparison.candidate().execution().usageAvailable()
                    && oracle.execution().usageAvailable();
        });
        if (!comparable) {
            return new CostComparison(false, 0);
        }
        double candidateCost = comparisons.stream()
                .mapToDouble(comparison -> comparison.candidate()
                        .execution().estimatedCostCny())
                .sum();
        double oracleCost = comparisons.stream()
                .map(this::oracle)
                .mapToDouble(item -> item.execution().estimatedCostCny())
                .sum();
        return new CostComparison(
                true,
                oracleCost <= 0
                        ? (candidateCost <= 0 ? 1 : Double.POSITIVE_INFINITY)
                        : round(candidateCost / oracleCost)
        );
    }

    private RagAbReport.VariantResult oracle(
            RagAbReport.CaseComparison comparison) {
        return "ADAPTIVE_MULTI_AGENT".equals(
                comparison.candidate().execution().executionMode())
                ? comparison.forcedMulti()
                : comparison.forcedSingle();
    }

    private List<RagAbReport.TagSummary> summarizeTags(
            List<RagAbReport.CaseComparison> comparisons) {
        Set<String> tags = new LinkedHashSet<>();
        comparisons.forEach(comparison -> tags.addAll(comparison.tags()));
        return tags.stream()
                .sorted()
                .map(tag -> {
                    List<RagAbReport.CaseComparison> tagged = comparisons.stream()
                            .filter(comparison -> comparison.tags().contains(tag))
                            .toList();
                    return new RagAbReport.TagSummary(
                            tag,
                            tagged.size(),
                            averageScore(tagged, RagEvaluationVariant.TRADITIONAL_RAG),
                            averageScore(tagged, RagEvaluationVariant.AGENTIC_SINGLE_AGENT),
                            averageScore(tagged, RagEvaluationVariant.AGENTIC_MULTI_AGENT),
                            averageScore(tagged, RagEvaluationVariant.AGENTIC_RAG_V5)
                    );
                })
                .toList();
    }

    private double averageScore(
            List<RagAbReport.CaseComparison> comparisons,
            RagEvaluationVariant variant) {
        return round(comparisons.stream()
                .map(comparison -> result(comparison, variant))
                .mapToDouble(item -> item.score().total())
                .average()
                .orElse(0));
    }

    private List<String> gateFailures(
            List<RagAbReport.CaseComparison> comparisons,
            RagAbReport.VariantSummary baseline,
            RagAbReport.VariantSummary candidate,
            double routeAccuracy,
            CostComparison costComparison) {
        List<String> failures = new ArrayList<>();
        if (candidate.averageScore() < candidateMinimumScore) {
            failures.add("候选平均质量 %.4f 低于门槛 %.4f"
                    .formatted(candidate.averageScore(), candidateMinimumScore));
        }
        if (candidate.averageScore() + maximumQualityRegression
                < baseline.averageScore()) {
            failures.add("候选相对基线回退超过 %.4f"
                    .formatted(maximumQualityRegression));
        }
        if (routeAccuracy < minimumRouteAccuracy) {
            failures.add("自适应路由准确率 %.4f 低于门槛 %.4f"
                    .formatted(routeAccuracy, minimumRouteAccuracy));
        }
        if (costComparison.comparable()
                && costComparison.ratio() > maximumAdaptiveOracleCostRatio) {
            failures.add("自适应成本/同路由强制基线 %.4f 超过门槛 %.4f"
                    .formatted(
                            costComparison.ratio(),
                            maximumAdaptiveOracleCostRatio
                    ));
        }
        List<String> criticalCases = comparisons.stream()
                .filter(RagAbReport.CaseComparison::criticalRegression)
                .map(RagAbReport.CaseComparison::caseId)
                .toList();
        if (!criticalCases.isEmpty()) {
            failures.add("存在关键回归样本：" + criticalCases);
        }
        if (candidate.failureCount() > baseline.failureCount()) {
            failures.add("候选执行失败数高于基线");
        }
        return List.copyOf(failures);
    }

    private int countWinners(
            List<RagAbReport.CaseComparison> comparisons,
            String winner) {
        return (int) comparisons.stream()
                .filter(comparison -> winner.equals(comparison.winner()))
                .count();
    }

    private void validateCases(List<RagEvaluationCase> cases) {
        if (cases.size() < minimumBenchmarkCases) {
            throw new IllegalStateException(
                    "Evaluation benchmark requires at least "
                            + minimumBenchmarkCases + " cases, found " + cases.size());
        }
        Set<String> ids = new LinkedHashSet<>();
        for (RagEvaluationCase evaluationCase : cases) {
            if (evaluationCase.id() == null || evaluationCase.id().isBlank()) {
                throw new IllegalStateException("Evaluation case id must not be blank");
            }
            if (!ids.add(evaluationCase.id())) {
                throw new IllegalStateException(
                        "Duplicate evaluation case id: " + evaluationCase.id());
            }
            if (!"SINGLE_AGENT".equals(evaluationCase.expectedExecutionMode())
                    && !"ADAPTIVE_MULTI_AGENT".equals(
                            evaluationCase.expectedExecutionMode())) {
                throw new IllegalStateException(
                        "Invalid expected execution mode for "
                                + evaluationCase.id());
            }
            if (evaluationCase.requiredConcepts().isEmpty()) {
                throw new IllegalStateException(
                        "Evaluation case requires concepts: "
                                + evaluationCase.id());
            }
        }
    }

    private String fingerprint(List<RagEvaluationCase> cases) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(cases);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Failed to fingerprint evaluation benchmark", exception);
        }
    }

    private void persist(Path jsonPath, Path markdownPath, RagAbReport report) {
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
                    "Failed to persist evaluation report: " + jsonPath,
                    exception
            );
        }
    }

    private String renderMarkdown(RagAbReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Agentic RAG 四路基准报告\n\n")
                .append("- Run: `").append(report.runId()).append("`\n")
                .append("- Benchmark: `").append(report.benchmarkVersion())
                .append("` / `").append(report.benchmarkFingerprint()).append("`\n")
                .append("- Model asset: `")
                .append(report.runtimeIdentity().modelVersion())
                .append("` / `")
                .append(report.runtimeIdentity().modelArtifactFingerprint())
                .append("`\n")
                .append("- Training config: `")
                .append(report.runtimeIdentity().trainingConfigFingerprint())
                .append("` / reward schema `")
                .append(report.runtimeIdentity().rewardSchemaVersion())
                .append("`\n")
                .append("- Cases: ").append(report.caseCount()).append("\n")
                .append("- Regression gate: **")
                .append(report.regressionGatePassed() ? "PASS" : "FAIL")
                .append("**\n\n")
                .append("| Variant | Score | Pass rate | Avg latency | Tokens | Cost CNY | Failures |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (RagAbReport.VariantSummary summary : List.of(
                report.baseline(),
                report.forcedSingle(),
                report.forcedMulti(),
                report.candidate())) {
            markdown.append("| ").append(summary.variant())
                    .append(" | ").append(summary.averageScore())
                    .append(" | ").append(summary.passRate())
                    .append(" | ").append(summary.averageLatencyMs())
                    .append(" ms | ").append(summary.totalTokens())
                    .append(" | ").append(summary.estimatedCostCny())
                    .append(" | ").append(summary.failureCount())
                    .append(" |\n");
        }
        markdown.append("\n- Adaptive route accuracy: ")
                .append(report.routeAccuracy()).append("\n")
                .append("- Candidate W/T/L: ")
                .append(report.candidateWins()).append("/")
                .append(report.ties()).append("/")
                .append(report.candidateLosses()).append("\n")
                .append("- Adaptive/oracle-route cost ratio: ")
                .append(report.usageComparable()
                        ? report.adaptiveOracleCostRatio()
                        : "N/A (usage unavailable)")
                .append("\n\n");
        if (!report.gateFailures().isEmpty()) {
            markdown.append("## Gate failures\n\n");
            report.gateFailures().forEach(
                    failure -> markdown.append("- ").append(failure).append("\n"));
            markdown.append("\n");
        }
        markdown.append("## Largest regressions\n\n")
                .append("| Case | Delta | Winner |\n")
                .append("|---|---:|---|\n");
        report.cases().stream()
                .sorted(Comparator.comparingDouble(
                        RagAbReport.CaseComparison::scoreDelta))
                .limit(10)
                .forEach(comparison -> markdown
                        .append("| ").append(comparison.caseId())
                        .append(" | ").append(comparison.scoreDelta())
                        .append(" | ").append(comparison.winner())
                        .append(" |\n"));
        return markdown.toString();
    }

    private void throwIfCancelled(BooleanSupplier cancellation) {
        AgentRunCancelledException.throwIfCancelled();
        if (cancellation.getAsBoolean()) {
            throw new AgentRunCancelledException(
                    "A/B evaluation was cancelled");
        }
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private double roundCost(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    private record CostComparison(boolean comparable, double ratio) {
    }

    public record Progress(
            String runId,
            int completedCases,
            int totalCases,
            String currentCaseId,
            String status
    ) {
    }

    public record BenchmarkMetadata(
            String version,
            String fingerprint,
            int caseCount,
            long expectedSingleAgentCases,
            long expectedMultiAgentCases,
            Map<String, Long> tagCounts
    ) {
    }
}
