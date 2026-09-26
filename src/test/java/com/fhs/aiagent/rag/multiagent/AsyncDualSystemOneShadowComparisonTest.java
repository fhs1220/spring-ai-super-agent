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
        AsyncDualSystemOneShadowComparison comparison =
                new AsyncDualSystemOneShadowComparison(
                        blockingAdvisor(providersStarted, releaseProviders, 0.1, 0.1),
                        blockingAdvisor(providersStarted, releaseProviders, 0.9, 0.9),
                        repository,
                        true, false, 0.25, 0, 1, executor,
                        Clock.systemUTC());
        try {
            assertThat(comparison.submit("first", false, "A").accepted()).isTrue();
            assertThat(providersStarted.await(1, TimeUnit.SECONDS)).isTrue();

            SystemOneShadowComparison.Submission saturated =
                    comparison.submit("second", false, "A");

            assertThat(saturated.active()).isTrue();
            assertThat(saturated.accepted()).isFalse();
            assertThat(saturated.status()).isEqualTo("SKIPPED_CAPACITY");
            releaseProviders.countDown();
            assertThat(repository.saved.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProviders.countDown();
            comparison.close();
        }
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
