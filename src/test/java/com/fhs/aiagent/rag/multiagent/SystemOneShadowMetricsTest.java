package com.fhs.aiagent.rag.multiagent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SystemOneShadowMetricsTest {
    @Test
    void aggregatesConcurrentObservationsWithoutConflatingZeroCostWithCompleteBilling() {
        Instant since = Instant.parse("2026-09-26T00:00:00Z");
        SystemOneShadowMetrics metrics = new SystemOneShadowMetrics(Clock.fixed(since, ZoneOffset.UTC));
        SystemOneRoutingAdvisor.RoutingAdvice freeOrUnknown = new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", false, 0.1, false, 0, Map.of(),
                100, "LAYA:local", 200, 10, 0);
        CompletableFuture.allOf(IntStream.range(0, 100).mapToObj(index -> CompletableFuture.runAsync(() -> {
            metrics.submitted();
            metrics.accepted();
            metrics.providerCompleted(true, freeOrUnknown);
        })).toArray(CompletableFuture[]::new)).join();

        SystemOneShadowMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.since()).isEqualTo(since);
        assertThat(snapshot.scope()).isEqualTo("PROCESS_LOCAL_RESET_ON_RESTART");
        assertThat(snapshot.submitted()).isEqualTo(100);
        assertThat(snapshot.primary().completed()).isEqualTo(100);
        assertThat(snapshot.primary().success()).isEqualTo(100);
        assertThat(snapshot.primary().inputTokens()).isEqualTo(20_000);
        assertThat(snapshot.primary().averageLatencyMs()).isEqualTo(100);
        assertThat(snapshot.primary().latencyBuckets().get(1).count()).isEqualTo(100);
        assertThat(snapshot.primary().unknownCostCount()).isEqualTo(100);
        assertThat(snapshot.primary().estimatedApiCostComplete()).isFalse();
        assertThat(new SystemOneShadowMetrics().snapshot().submitted()).isZero();
    }
}
