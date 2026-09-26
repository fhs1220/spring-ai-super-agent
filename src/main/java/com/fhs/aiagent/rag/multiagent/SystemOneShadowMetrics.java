package com.fhs.aiagent.rag.multiagent;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Bounded, process-local counters for all enabled submissions, independent of sample storage. */
@Component
public class SystemOneShadowMetrics {
    private static final long[] LATENCY_BOUNDS_MS = {50, 100, 250, 500, 1_000, 2_500, 5_000};
    private final Clock clock;
    private final Instant since;
    private final ProviderCounters primary = new ProviderCounters();
    private final ProviderCounters challenger = new ProviderCounters();
    private long submitted;
    private long accepted;
    private long capacityDropped;
    private long rejected;
    private long completedComparisons;
    private long failedComparisons;
    private long persistedObservations;
    private long persistenceFailures;
    private long unretainedAgreements;

    public SystemOneShadowMetrics() {
        this(Clock.systemUTC());
    }

    SystemOneShadowMetrics(Clock clock) {
        this.clock = clock;
        this.since = Instant.now(clock);
    }

    synchronized void submitted() { submitted++; }
    synchronized void accepted() { accepted++; }
    synchronized void capacityDropped() { capacityDropped++; }
    synchronized void rejected() { rejected++; accepted--; }
    synchronized void comparisonCompleted() { completedComparisons++; }
    synchronized void comparisonFailed() { failedComparisons++; }
    synchronized void persisted() { persistedObservations++; }
    synchronized void persistenceFailed() { persistenceFailures++; }
    synchronized void agreementNotRetained() { unretainedAgreements++; }

    synchronized void providerCompleted(boolean primaryProvider,
                                        SystemOneRoutingAdvisor.RoutingAdvice advice) {
        (primaryProvider ? primary : challenger).record(advice);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot("system-one-shadow-metrics-v1", "PROCESS_LOCAL_RESET_ON_RESTART",
                since, Instant.now(clock), submitted, accepted, capacityDropped, rejected,
                completedComparisons, failedComparisons, persistedObservations,
                persistenceFailures, unretainedAgreements,
                primary.snapshot(), challenger.snapshot());
    }

    public record Snapshot(
            String schemaVersion, String scope, Instant since, Instant capturedAt,
            long submitted, long accepted, long capacityDropped, long rejected,
            long completedComparisons, long failedComparisons, long persistedObservations,
            long persistenceFailures, long unretainedAgreements,
            ProviderSnapshot primary, ProviderSnapshot challenger) {
    }

    public record ProviderSnapshot(
            long completed, long success, long failure, long malformedResponses,
            String lastObservedModel, long modelChanges,
            double averageLatencyMs, List<LatencyBucket> latencyBuckets,
            long inputTokens, long outputTokens, long usageAvailableCount,
            double knownEstimatedApiCostUsd, long costEstimateAvailableCount,
            long unknownCostCount, boolean estimatedApiCostComplete) {
    }

    /** Non-cumulative counts; a null upper bound is the final +infinity bucket. */
    public record LatencyBucket(Long upperBoundInclusiveMs, long count) {
    }

    private static final class ProviderCounters {
        private final long[] latencyBuckets = new long[LATENCY_BOUNDS_MS.length + 1];
        private long completed;
        private long success;
        private long malformed;
        private String lastModel = "";
        private long modelChanges;
        private double totalLatencyMs;
        private long inputTokens;
        private long outputTokens;
        private long usageAvailableCount;
        private double estimatedApiCostUsd;
        private long costEstimateAvailableCount;

        private void record(SystemOneRoutingAdvisor.RoutingAdvice advice) {
            completed++;
            if ("SUCCESS".equals(advice.status())) success++;
            if ("MALFORMED_RESPONSE".equals(advice.status())) malformed++;
            if (!advice.model().isBlank()) {
                if (!lastModel.isBlank() && !lastModel.equals(advice.model())) modelChanges++;
                // Keep cardinality bounded even if an upstream provider changes model names.
                lastModel = advice.model();
            }
            long latency = Math.max(0, advice.latencyMs());
            totalLatencyMs += latency;
            int bucket = 0;
            while (bucket < LATENCY_BOUNDS_MS.length && latency > LATENCY_BOUNDS_MS[bucket]) bucket++;
            latencyBuckets[bucket]++;
            inputTokens += advice.inputTokens();
            outputTokens += advice.outputTokens();
            boolean usageAvailable = advice.inputTokens() > 0 || advice.outputTokens() > 0;
            if (usageAvailable) usageAvailableCount++;
            // RoutingAdvice has no explicit zero-price/usage contract. Treat zero as unknown;
            // this API never claims that local hardware or unmetered calls are free.
            if (Double.isFinite(advice.estimatedCostUsd()) && advice.estimatedCostUsd() > 0) {
                estimatedApiCostUsd += advice.estimatedCostUsd();
                costEstimateAvailableCount++;
            }
        }

        private ProviderSnapshot snapshot() {
            List<LatencyBucket> buckets = new ArrayList<>();
            for (int i = 0; i < latencyBuckets.length; i++) {
                buckets.add(new LatencyBucket(i < LATENCY_BOUNDS_MS.length ? LATENCY_BOUNDS_MS[i] : null,
                        latencyBuckets[i]));
            }
            return new ProviderSnapshot(completed, success, completed - success, malformed,
                    lastModel, modelChanges, completed == 0 ? 0 : totalLatencyMs / completed,
                    List.copyOf(buckets), inputTokens, outputTokens, usageAvailableCount,
                    estimatedApiCostUsd, costEstimateAvailableCount,
                    completed - costEstimateAvailableCount,
                    completed > 0 && completed == costEstimateAvailableCount);
        }
    }
}
