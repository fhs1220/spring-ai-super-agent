package com.fhs.aiagent.evaluation;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 对同一固定基准上的逐样本质量差值执行可复现的配对 Bootstrap。
 */
public class PairedBootstrapAnalyzer {

    private static final double EPSILON = 1.0e-12;

    public AlignmentAblationReport.PairedQualityComparison analyze(
            String seedKey,
            List<Double> baselineScores,
            List<Double> candidateScores,
            int iterations,
            double confidenceLevel,
            double tieThreshold,
            double nonInferiorityMargin,
            int minimumSamples) {
        long seed = Integer.toUnsignedLong(
                (seedKey == null ? "" : seedKey).hashCode());
        if (baselineScores == null
                || candidateScores == null
                || baselineScores.isEmpty()
                || baselineScores.size() != candidateScores.size()) {
            return unavailable(
                    seed,
                    iterations,
                    confidenceLevel,
                    nonInferiorityMargin,
                    "逐样本候选分数缺失或无法按 caseId 配对"
            );
        }

        int sampleCount = baselineScores.size();
        double[] deltas = new double[sampleCount];
        int wins = 0;
        int ties = 0;
        int losses = 0;
        for (int index = 0; index < sampleCount; index++) {
            double baseline = finiteScore(baselineScores.get(index));
            double candidate = finiteScore(candidateScores.get(index));
            double delta = candidate - baseline;
            deltas[index] = delta;
            if (delta > tieThreshold) {
                wins++;
            } else if (delta < -tieThreshold) {
                losses++;
            } else {
                ties++;
            }
        }

        double meanDelta = mean(deltas);
        double standardDeviation = sampleStandardDeviation(deltas, meanDelta);
        double standardizedEffect = standardDeviation > EPSILON
                ? meanDelta / standardDeviation
                : 0;
        double[] sortedDeltas = Arrays.copyOf(deltas, deltas.length);
        Arrays.sort(sortedDeltas);
        double medianDelta = quantile(sortedDeltas, 0.5);

        int safeIterations = Math.max(100, iterations);
        double safeConfidenceLevel = Math.max(
                0.5, Math.min(0.999, confidenceLevel));
        double[] bootstrapMeans = new double[safeIterations];
        SplittableRandom random = new SplittableRandom(seed);
        int positiveBootstrapMeans = 0;
        for (int iteration = 0; iteration < safeIterations; iteration++) {
            double sum = 0;
            for (int sample = 0; sample < sampleCount; sample++) {
                sum += deltas[random.nextInt(sampleCount)];
            }
            double bootstrapMean = sum / sampleCount;
            bootstrapMeans[iteration] = bootstrapMean;
            if (bootstrapMean > 0) {
                positiveBootstrapMeans++;
            }
        }
        Arrays.sort(bootstrapMeans);
        double alpha = (1 - safeConfidenceLevel) / 2;
        double lowerBound = quantile(bootstrapMeans, alpha);
        double upperBound = quantile(bootstrapMeans, 1 - alpha);
        boolean enoughSamples = sampleCount >= Math.max(2, minimumSamples);
        boolean nonInferiorityPassed = enoughSamples
                && lowerBound >= -Math.max(0, nonInferiorityMargin);
        boolean statisticallySignificant = enoughSamples
                && (lowerBound > 0 || upperBound < 0);

        return new AlignmentAblationReport.PairedQualityComparison(
                true,
                "",
                sampleCount,
                round(meanDelta),
                round(medianDelta),
                round(standardDeviation),
                round(lowerBound),
                round(upperBound),
                round(standardizedEffect),
                wins,
                ties,
                losses,
                round(exactTwoSidedSignTest(wins, losses)),
                round(positiveBootstrapMeans / (double) safeIterations),
                statisticallySignificant,
                round(Math.max(0, nonInferiorityMargin)),
                nonInferiorityPassed,
                enoughSamples,
                safeIterations,
                round(safeConfidenceLevel),
                seed
        );
    }

    public AlignmentAblationReport.PairedQualityComparison unavailable(
            String seedKey,
            int iterations,
            double confidenceLevel,
            double nonInferiorityMargin,
            String reason) {
        long seed = Integer.toUnsignedLong(
                (seedKey == null ? "" : seedKey).hashCode());
        return unavailable(
                seed,
                iterations,
                confidenceLevel,
                nonInferiorityMargin,
                reason
        );
    }

    private AlignmentAblationReport.PairedQualityComparison unavailable(
            long seed,
            int iterations,
            double confidenceLevel,
            double nonInferiorityMargin,
            String reason) {
        return new AlignmentAblationReport.PairedQualityComparison(
                false,
                reason == null ? "逐样本数据不可比较" : reason,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1,
                0,
                false,
                round(Math.max(0, nonInferiorityMargin)),
                false,
                false,
                Math.max(100, iterations),
                round(Math.max(0.5, Math.min(0.999, confidenceLevel))),
                seed
        );
    }

    private double exactTwoSidedSignTest(int wins, int losses) {
        int nonTies = wins + losses;
        if (nonTies == 0) {
            return 1;
        }
        int tail = Math.min(wins, losses);
        double term = Math.pow(0.5, nonTies);
        double cumulative = term;
        for (int successes = 0; successes < tail; successes++) {
            term *= (nonTies - successes) / (double) (successes + 1);
            cumulative += term;
        }
        return Math.min(1, 2 * cumulative);
    }

    private double finiteScore(Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "Paired quality scores must be finite");
        }
        return value;
    }

    private double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private double sampleStandardDeviation(double[] values, double mean) {
        if (values.length < 2) {
            return 0;
        }
        double squared = 0;
        for (double value : values) {
            double centered = value - mean;
            squared += centered * centered;
        }
        return Math.sqrt(squared / (values.length - 1));
    }

    private double quantile(double[] sorted, double probability) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = Math.max(0, Math.min(1, probability))
                * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
