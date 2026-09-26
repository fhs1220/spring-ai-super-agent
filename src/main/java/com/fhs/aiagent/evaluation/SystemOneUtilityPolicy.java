package com.fhs.aiagent.evaluation;

/** Versioned evaluation-only utility; independent of the live trajectory policy. */
public record SystemOneUtilityPolicy(
        double costWeight,
        double costScaleCny,
        double latencyWeight,
        double latencyScaleMs
) {
    public static final String VERSION = "system-one-utility-v2-linear";

    public SystemOneUtilityPolicy {
        requireNonNegativeFinite(costWeight, "costWeight");
        requireNonNegativeFinite(latencyWeight, "latencyWeight");
        requirePositiveFinite(costScaleCny, "costScaleCny");
        requirePositiveFinite(latencyScaleMs, "latencyScaleMs");
    }

    public static SystemOneUtilityPolicy defaults() {
        return new SystemOneUtilityPolicy(0.05, 1.0, 0.05, 60_000);
    }

    public double utility(double quality, double costCny, long latencyMs) {
        return utility(quality, costCny, (double) latencyMs);
    }

    public double utility(double quality, double costCny, double latencyMs) {
        if (!Double.isFinite(quality) || quality < 0 || quality > 1) {
            throw new IllegalArgumentException("quality must be finite and between 0 and 1");
        }
        requireNonNegativeFinite(costCny, "costCny");
        requireNonNegativeFinite(latencyMs, "latencyMs");
        // No clipping: additional spend and waiting time must retain a marginal penalty.
        return quality - costWeight * costCny / costScaleCny
                - latencyWeight * latencyMs / latencyScaleMs;
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
