package com.fhs.aiagent.rag.multiagent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncDualSystemOneShadowComparisonTest {

    @Test
    void queuesBothProvidersWithoutBlockingAndPersistsDisagreement() throws Exception {
        CountDownLatch releaseProviders = new CountDownLatch(1);
        CountDownLatch providersStarted = new CountDownLatch(2);
        CapturingRepository repository = new CapturingRepository();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AsyncDualSystemOneShadowComparison comparison =
                new AsyncDualSystemOneShadowComparison(
                        blockingAdvisor(providersStarted, releaseProviders, 0.10, 0.90),
                        blockingAdvisor(providersStarted, releaseProviders, 0.85, 0.10),
                        repository,
                        true,
                        false,
                        0.25,
                        0,
                        2,
                        executor,
                        Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC)
                );
        try {
            SystemOneShadowComparison.Submission submission = comparison.submit(
                    "孩子、家务和预算是否值得多 Agent？", true, "PARENTING+HOUSEHOLD");

            assertThat(submission.accepted()).isTrue();
            assertThat(submission.status()).isEqualTo("QUEUED_DUAL_SHADOW");
            assertThat(providersStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.samples).isEmpty();

            releaseProviders.countDown();
            assertThat(repository.saved.await(1, TimeUnit.SECONDS)).isTrue();
            SystemOneShadowSample sample = repository.samples.getFirst();
            assertThat(sample.question()).isEmpty();
            assertThat(sample.questionFingerprint()).hasSize(64);
            assertThat(sample.authoritativeMultiAgent()).isTrue();
            assertThat(sample.disagreementTypes()).containsExactly(
                    "ROUTE_ACTION_DISAGREEMENT",
                    "SAFETY_ACTION_DISAGREEMENT",
                    "MULTI_AGENT_PROBABILITY_GAP");
            assertThat(sample.reviewEligible()).isTrue();
        } finally {
            releaseProviders.countDown();
            comparison.close();
        }
    }

    @Test
    void dropsNewObservationWhenCapacityIsFull() throws Exception {
        CountDownLatch releaseProviders = new CountDownLatch(1);
        CountDownLatch providersStarted = new CountDownLatch(2);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CapturingRepository repository = new CapturingRepository();
        SystemOneShadowMetrics metrics = new SystemOneShadowMetrics();
        AsyncDualSystemOneShadowComparison comparison =
                new AsyncDualSystemOneShadowComparison(
                        blockingAdvisor(providersStarted, releaseProviders, 0.1, 0.1),
                        blockingAdvisor(providersStarted, releaseProviders, 0.9, 0.9),
                        repository,
                        true, false, 0.25, 0, 1, executor,
                        Clock.systemUTC(), metrics);
        try {
            assertThat(comparison.submit("first", false, "A").accepted()).isTrue();
            assertThat(providersStarted.await(1, TimeUnit.SECONDS)).isTrue();

            SystemOneShadowComparison.Submission saturated =
                    comparison.submit("second", false, "A");

            assertThat(saturated.active()).isTrue();
            assertThat(saturated.accepted()).isFalse();
            assertThat(saturated.status()).isEqualTo("SKIPPED_CAPACITY");
            assertThat(metrics.snapshot().submitted()).isEqualTo(2);
            assertThat(metrics.snapshot().accepted()).isEqualTo(1);
            assertThat(metrics.snapshot().capacityDropped()).isEqualTo(1);
            assertThat(metrics.snapshot().rejected()).isZero();
            releaseProviders.countDown();
            assertThat(repository.saved.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProviders.countDown();
            comparison.close();
        }
    }

    @Test
    void countsEveryAgreementEvenWhenRepeatedQuestionIsNeverPersisted() throws Exception {
        SystemOneRoutingAdvisor advisor = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", false, 0.1, false, 0.1, Map.of(),
                150, "JEV:test", 100, 10, 0.001);
        CapturingRepository repository = new CapturingRepository();
        SystemOneShadowMetrics metrics = new SystemOneShadowMetrics();
        AsyncDualSystemOneShadowComparison comparison = new AsyncDualSystemOneShadowComparison(
                advisor, advisor, repository, true, false, 0.25, 0, 2,
                Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), metrics);
        try {
            assertThat(comparison.submit("same question", false, "A").accepted()).isTrue();
            assertThat(comparison.submit("same question", false, "A").accepted()).isTrue();
            awaitMetric(() -> metrics.snapshot().unretainedAgreements() == 2);

            SystemOneShadowMetrics.Snapshot snapshot = metrics.snapshot();
            assertThat(repository.samples).isEmpty();
            assertThat(snapshot.submitted()).isEqualTo(2);
            assertThat(snapshot.accepted()).isEqualTo(2);
            assertThat(snapshot.completedComparisons()).isEqualTo(2);
            assertThat(snapshot.persistedObservations()).isZero();
            assertThat(snapshot.primary().completed()).isEqualTo(2);
            assertThat(snapshot.challenger().success()).isEqualTo(2);
            assertThat(snapshot.primary().inputTokens()).isEqualTo(200);
            assertThat(snapshot.primary().knownEstimatedApiCostUsd()).isEqualTo(0.002);
        } finally {
            comparison.close();
        }
    }

    @Test
    void countsMalformedAndThrownProviderFailuresWithoutLosingOtherProviderResults() throws Exception {
        SystemOneRoutingAdvisor malformed = question -> new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "MALFORMED_RESPONSE", false, 0, false, 0, Map.of(),
                6_000, "LAYA:test", 0, 0, 0);
        SystemOneRoutingAdvisor throwing = question -> { throw new IllegalStateException("provider failed"); };
        CapturingRepository repository = new CapturingRepository();
        SystemOneShadowMetrics metrics = new SystemOneShadowMetrics();
        AsyncDualSystemOneShadowComparison comparison = new AsyncDualSystemOneShadowComparison(
                malformed, throwing, repository, true, false, 0.25, 0, 1,
                Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), metrics);
        try {
            comparison.submit("failure", false, "A");
            awaitMetric(() -> metrics.snapshot().persistedObservations() == 1);

            SystemOneShadowMetrics.Snapshot snapshot = metrics.snapshot();
            assertThat(snapshot.completedComparisons()).isEqualTo(1);
            assertThat(snapshot.primary().failure()).isEqualTo(1);
            assertThat(snapshot.primary().malformedResponses()).isEqualTo(1);
            assertThat(snapshot.challenger().failure()).isEqualTo(1);
            assertThat(snapshot.primary().estimatedApiCostComplete()).isFalse();
            assertThat(snapshot.primary().unknownCostCount()).isEqualTo(1);
            assertThat(snapshot.primary().latencyBuckets().getLast().upperBoundInclusiveMs()).isNull();
            assertThat(snapshot.primary().latencyBuckets().getLast().count()).isEqualTo(1);
        } finally {
            comparison.close();
        }
    }

    @Test
    void separatesExecutorRejectionFromCapacityDrops() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdown();
        SystemOneShadowMetrics metrics = new SystemOneShadowMetrics();
        AsyncDualSystemOneShadowComparison comparison = new AsyncDualSystemOneShadowComparison(
                SystemOneRoutingAdvisor.disabled(), SystemOneRoutingAdvisor.disabled(),
                new CapturingRepository(), true, false, 0.25, 0, 1, executor,
                Clock.systemUTC(), metrics);

        assertThat(comparison.submit("rejected", false, "A").accepted()).isFalse();
        assertThat(metrics.snapshot().submitted()).isEqualTo(1);
        assertThat(metrics.snapshot().accepted()).isZero();
        assertThat(metrics.snapshot().rejected()).isEqualTo(1);
        assertThat(metrics.snapshot().capacityDropped()).isZero();
        assertThat(metrics.snapshot().primary().completed()).isZero();
    }

    private void awaitMetric(java.util.function.BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(ready.getAsBoolean()).isTrue();
    }

    private SystemOneRoutingAdvisor blockingAdvisor(
            CountDownLatch started,
            CountDownLatch release,
            double multiProbability,
            double safetyProbability) {
        return question -> {
            started.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test provider was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new SystemOneRoutingAdvisor.RoutingAdvice(
                    "SHADOW",
                    "SUCCESS",
                    multiProbability >= 0.5,
                    multiProbability,
                    safetyProbability >= 0.5,
                    safetyProbability,
                    Map.of(),
                    10,
                    multiProbability < 0.5 ? "JEV:test" : "LAYA:test",
                    100,
                    10,
                    0.001
            );
        };
    }

    private static final class CapturingRepository
            implements SystemOneShadowSampleRepository {

        private final List<SystemOneShadowSample> samples = new ArrayList<>();
        private final CountDownLatch saved = new CountDownLatch(1);

        @Override
        public synchronized SystemOneShadowSample save(SystemOneShadowSample sample) {
            samples.add(sample);
            saved.countDown();
            return sample;
        }

        @Override
        public synchronized List<SystemOneShadowSample> findRecent(int limit) {
            return samples.stream().limit(limit).toList();
        }
    }
}
