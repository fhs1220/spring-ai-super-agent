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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class RagAbEvaluationService {

    private static final double WIN_DELTA = 0.03;

    private static final double CRITICAL_REGRESSION_DELTA = -0.15;

    private static final double CASE_PASS_SCORE = 0.7;

    private final RagEvaluationVariantExecutor variantExecutor;

    private final RagAnswerScorer scorer;

    private final ObjectMapper objectMapper;

    private final Resource benchmarkResource;

    private final Path reportDirectory;

    private final int configuredMaximumCases;

    private final double candidateMinimumScore;

    private final double maximumQualityRegression;

    private final double minimumRouteAccuracy;

    public RagAbEvaluationService(
            RagEvaluationVariantExecutor variantExecutor,
            RagAnswerScorer scorer,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${agent.evaluation.benchmark:classpath:evaluation/love-rag-ab.jsonl}")
            String benchmark,
            @Value("${agent.evaluation.report-directory:tmp/evaluation}")
            String reportDirectory,
            @Value("${agent.evaluation.maximum-cases:50}") int maximumCases,
            @Value("${agent.evaluation.candidate-minimum-score:0.72}") double candidateMinimumScore,
            @Value("${agent.evaluation.maximum-quality-regression:0.02}")
            double maximumQualityRegression,
            @Value("${agent.evaluation.minimum-route-accuracy:0.8}") double minimumRouteAccuracy) {
        this.variantExecutor = variantExecutor;
        this.scorer = scorer;
        this.objectMapper = objectMapper;
        this.benchmarkResource = resourceLoader.getResource(benchmark);
        this.reportDirectory = Path.of(reportDirectory).toAbsolutePath().normalize();
        this.configuredMaximumCases = Math.max(1, maximumCases);
        this.candidateMinimumScore = clamp(candidateMinimumScore);
        this.maximumQualityRegression = Math.max(0, maximumQualityRegression);
        this.minimumRouteAccuracy = clamp(minimumRouteAccuracy);
    }

    public RagAbReport evaluate(String runId,
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
        List<RagEvaluationCase> cases = loadCases();
        int limit = requestedCases <= 0
                ? cases.size()
                : Math.min(requestedCases, cases.size());
        limit = Math.min(limit, configuredMaximumCases);
        cases = List.copyOf(cases.subList(0, limit));

        List<RagAbReport.CaseComparison> comparisons = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            throwIfCancelled(cancellation);
            RagEvaluationCase evaluationCase = cases.get(index);
            progress.accept(new Progress(
                    runId, index, cases.size(), evaluationCase.id(), "RUNNING"));

            RagVariantExecution baseline;
            RagVariantExecution candidate;
            if (index % 2 == 0) {
                baseline = execute(RagEvaluationVariant.TRADITIONAL_RAG, evaluationCase, runId);
                throwIfCancelled(cancellation);
                candidate = execute(RagEvaluationVariant.AGENTIC_RAG_V4, evaluationCase, runId);
            } else {
                candidate = execute(RagEvaluationVariant.AGENTIC_RAG_V4, evaluationCase, runId);
                throwIfCancelled(cancellation);
                baseline = execute(RagEvaluationVariant.TRADITIONAL_RAG, evaluationCase, runId);
            }
            throwIfCancelled(cancellation);

            RagAnswerScorer.Score baselineScore = scorer.score(evaluationCase, baseline);
            RagAnswerScorer.Score candidateScore = scorer.score(evaluationCase, candidate);
            double delta = round(candidateScore.total() - baselineScore.total());
            String winner = delta > WIN_DELTA
                    ? "CANDIDATE"
                    : delta < -WIN_DELTA ? "BASELINE" : "TIE";
            boolean criticalRegression = delta < CRITICAL_REGRESSION_DELTA
                    || baseline.succeeded() && !candidate.succeeded();
            comparisons.add(new RagAbReport.CaseComparison(
                    evaluationCase.id(),
                    evaluationCase.question(),
                    evaluationCase.tags(),
                    new RagAbReport.VariantResult(baseline, baselineScore),
                    new RagAbReport.VariantResult(candidate, candidateScore),
                    winner,
                    delta,
                    criticalRegression
            ));
            progress.accept(new Progress(
                    runId, index + 1, cases.size(), evaluationCase.id(), "COMPLETED"));
        }

        RagAbReport.VariantSummary baselineSummary = summarize(
                RagEvaluationVariant.TRADITIONAL_RAG, comparisons, true);
        RagAbReport.VariantSummary candidateSummary = summarize(
                RagEvaluationVariant.AGENTIC_RAG_V4, comparisons, false);
        double routeAccuracy = round(comparisons.stream()
                .filter(comparison -> comparison.candidate().score().routeCorrect())
                .count() / (double) Math.max(1, comparisons.size()));
        int candidateWins = countWinners(comparisons, "CANDIDATE");
        int ties = countWinners(comparisons, "TIE");
        int candidateLosses = countWinners(comparisons, "BASELINE");
        List<String> gateFailures = gateFailures(
                comparisons, baselineSummary, candidateSummary, routeAccuracy);
        Path reportPath = reportDirectory.resolve(runId + ".json");
        RagAbReport report = new RagAbReport(
                runId,
                startedAt,
                Instant.now(),
                comparisons.size(),
                baselineSummary,
                candidateSummary,
                candidateWins,
                ties,
                candidateLosses,
                routeAccuracy,
                gateFailures.isEmpty(),
                gateFailures,
                List.copyOf(comparisons),
                reportPath.toString()
        );
        persist(reportPath, report);
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
                    cases.add(objectMapper.readValue(normalized, RagEvaluationCase.class));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException(
                            "Invalid benchmark JSONL at line " + lineNumber, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read evaluation benchmark", exception);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("Evaluation benchmark is empty");
        }
        return List.copyOf(cases);
    }

    private RagVariantExecution execute(RagEvaluationVariant variant,
                                        RagEvaluationCase evaluationCase,
                                        String runId) {
        long startedAt = System.nanoTime();
        String chatId = "eval-%s-%s-%s".formatted(
                runId, evaluationCase.id(), variant.name().toLowerCase());
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
            List<RagAbReport.CaseComparison> comparisons,
            boolean baseline) {
        List<RagAbReport.VariantResult> results = comparisons.stream()
                .map(comparison -> baseline ? comparison.baseline() : comparison.candidate())
                .toList();
        double averageScore = results.stream()
                .mapToDouble(result -> result.score().total())
                .average()
                .orElse(0);
        double passRate = results.stream()
                .filter(result -> result.score().total() >= CASE_PASS_SCORE)
                .count() / (double) Math.max(1, results.size());
        double averageLatency = results.stream()
                .mapToLong(result -> result.execution().latencyMs())
                .average()
                .orElse(0);
        long totalTokens = results.stream()
                .mapToLong(result -> result.execution().totalTokens())
                .sum();
        double cost = results.stream()
                .mapToDouble(result -> result.execution().estimatedCostCny())
                .sum();
        int usageMeasuredCases = (int) results.stream()
                .filter(result -> result.execution().usageAvailable())
                .count();
        int failures = (int) results.stream()
                .filter(result -> !result.execution().succeeded())
                .count();
        return new RagAbReport.VariantSummary(
                variant,
                round(averageScore),
                round(passRate),
                round(averageLatency),
                totalTokens,
                roundCost(cost),
                usageMeasuredCases,
                failures
        );
    }

    private List<String> gateFailures(
            List<RagAbReport.CaseComparison> comparisons,
            RagAbReport.VariantSummary baseline,
            RagAbReport.VariantSummary candidate,
            double routeAccuracy) {
        List<String> failures = new ArrayList<>();
        if (candidate.averageScore() < candidateMinimumScore) {
            failures.add("候选平均质量 %.4f 低于门槛 %.4f"
                    .formatted(candidate.averageScore(), candidateMinimumScore));
        }
        if (candidate.averageScore() + maximumQualityRegression < baseline.averageScore()) {
            failures.add("候选相对基线回退超过 %.4f".formatted(maximumQualityRegression));
        }
        if (routeAccuracy < minimumRouteAccuracy) {
            failures.add("自适应路由准确率 %.4f 低于门槛 %.4f"
                    .formatted(routeAccuracy, minimumRouteAccuracy));
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

    private int countWinners(List<RagAbReport.CaseComparison> comparisons, String winner) {
        return (int) comparisons.stream()
                .filter(comparison -> winner.equals(comparison.winner()))
                .count();
    }

    private void persist(Path target, RagAbReport report) {
        try {
            Files.createDirectories(reportDirectory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist evaluation report: " + target, exception);
        }
    }

    private void throwIfCancelled(BooleanSupplier cancellation) {
        AgentRunCancelledException.throwIfCancelled();
        if (cancellation.getAsBoolean()) {
            throw new AgentRunCancelledException("A/B evaluation was cancelled");
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

    public record Progress(
            String runId,
            int completedCases,
            int totalCases,
            String currentCaseId,
            String status
    ) {
    }
}
