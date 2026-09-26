package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.decision.HttpSystemOneDecisionClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/** Runs Jev and Laya outside the request critical path and stores useful disagreements. */
@Service
public class AsyncDualSystemOneShadowComparison implements SystemOneShadowComparison {

    private static final Logger log = LoggerFactory.getLogger(
            AsyncDualSystemOneShadowComparison.class);

    private final SystemOneRoutingAdvisor primary;
    private final SystemOneRoutingAdvisor challenger;
    private final SystemOneShadowSampleRepository repository;
    private final boolean enabled;
    private final boolean storeQuestion;
    private final double probabilityGapThreshold;
    private final double agreementSampleRate;
    private final Semaphore capacity;
    private final ExecutorService executor;
    private final Clock clock;
    private final SystemOneShadowMetrics metrics;

    @Autowired
    public AsyncDualSystemOneShadowComparison(
            ObjectMapper objectMapper,
            SystemOneShadowSampleRepository repository,
            Environment environment,
            SystemOneShadowMetrics metrics) {
        this(
                advisor(objectMapper, environment, "primary", ProviderSettings.primary(environment)),
                advisor(objectMapper, environment, "challenger", ProviderSettings.challenger(environment)),
                repository,
                environment.getProperty(
                        "agent.decision.system-one.comparison.enabled", Boolean.class, false),
                environment.getProperty(
                        "agent.decision.system-one.comparison.store-question", Boolean.class, false),
                environment.getProperty(
                        "agent.decision.system-one.comparison.probability-gap-threshold",
                        Double.class, 0.25),
                environment.getProperty(
                        "agent.decision.system-one.comparison.agreement-sample-rate",
                        Double.class, 0.10),
                environment.getProperty(
                        "agent.decision.system-one.comparison.maximum-in-flight",
                        Integer.class, 4),
                Executors.newVirtualThreadPerTaskExecutor(),
                Clock.systemUTC(), metrics
        );
    }

    public AsyncDualSystemOneShadowComparison(
            ObjectMapper objectMapper, SystemOneShadowSampleRepository repository,
            Environment environment) {
        this(objectMapper, repository, environment, new SystemOneShadowMetrics());
    }

    AsyncDualSystemOneShadowComparison(
            SystemOneRoutingAdvisor primary,
            SystemOneRoutingAdvisor challenger,
            SystemOneShadowSampleRepository repository,
            boolean enabled,
            boolean storeQuestion,
            double probabilityGapThreshold,
            double agreementSampleRate,
            int maximumInFlight,
            ExecutorService executor,
            Clock clock) {
        this(primary, challenger, repository, enabled, storeQuestion, probabilityGapThreshold,
                agreementSampleRate, maximumInFlight, executor, clock, new SystemOneShadowMetrics(clock));
    }

    AsyncDualSystemOneShadowComparison(
            SystemOneRoutingAdvisor primary, SystemOneRoutingAdvisor challenger,
            SystemOneShadowSampleRepository repository, boolean enabled, boolean storeQuestion,
            double probabilityGapThreshold, double agreementSampleRate, int maximumInFlight,
            ExecutorService executor, Clock clock, SystemOneShadowMetrics metrics) {
        this.primary = java.util.Objects.requireNonNull(primary, "primary");
        this.challenger = java.util.Objects.requireNonNull(challenger, "challenger");
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.enabled = enabled;
        this.storeQuestion = storeQuestion;
        this.probabilityGapThreshold = clamp(probabilityGapThreshold);
        this.agreementSampleRate = clamp(agreementSampleRate);
        this.capacity = new Semaphore(Math.max(1, maximumInFlight));
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public Submission submit(
            String question, boolean authoritativeMultiAgent, String featureBucket) {
        if (!enabled) return Submission.disabled();
        metrics.submitted();
        if (!capacity.tryAcquire()) {
            metrics.capacityDropped();
            return Submission.saturated();
        }
        String normalizedQuestion = question == null ? "" : question.trim();
        String sampleId = "shadow-" + fingerprint(normalizedQuestion).substring(0, 32);
        metrics.accepted();
        try {
            executor.submit(() -> compareAndPersist(
                    sampleId, normalizedQuestion, authoritativeMultiAgent, featureBucket));
            return Submission.queued(sampleId);
        } catch (RejectedExecutionException exception) {
            metrics.rejected();
            capacity.release();
            log.warn("System One dual shadow executor rejected sample {}", sampleId);
            return Submission.saturated();
        }
    }

    private void compareAndPersist(
            String sampleId,
            String question,
            boolean authoritativeMultiAgent,
            String featureBucket) {
        try {
            CompletableFuture<SystemOneRoutingAdvisor.RoutingAdvice> primaryFuture =
                    CompletableFuture.supplyAsync(() -> observeProvider(primary, question, true), executor);
            CompletableFuture<SystemOneRoutingAdvisor.RoutingAdvice> challengerFuture =
                    CompletableFuture.supplyAsync(() -> observeProvider(challenger, question, false), executor);
            SystemOneRoutingAdvisor.RoutingAdvice primaryAdvice = primaryFuture.join();
            SystemOneRoutingAdvisor.RoutingAdvice challengerAdvice = challengerFuture.join();
            metrics.comparisonCompleted();
            persist(sampleId, question, authoritativeMultiAgent, featureBucket,
                    primaryAdvice, challengerAdvice);
        } catch (RuntimeException exception) {
            metrics.comparisonFailed();
            log.warn("System One dual shadow comparison failed for sample {}: {}",
                    sampleId, exception.getMessage());
        } finally {
            capacity.release();
        }
    }

    private SystemOneRoutingAdvisor.RoutingAdvice observeProvider(
            SystemOneRoutingAdvisor advisor, String question, boolean primaryProvider) {
        long started = System.nanoTime();
        SystemOneRoutingAdvisor.RoutingAdvice advice;
        try {
            advice = advisor.advise(question);
            if (advice == null) advice = providerFailure("MALFORMED_RESPONSE", started);
        } catch (RuntimeException exception) {
            advice = providerFailure("EXCEPTION", started);
        }
        metrics.providerCompleted(primaryProvider, advice);
        return advice;
    }

    private SystemOneRoutingAdvisor.RoutingAdvice providerFailure(String status, long started) {
        return new SystemOneRoutingAdvisor.RoutingAdvice("SHADOW", status,
                false, 0, false, 0, java.util.Map.of(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "", 0, 0, 0);
    }

    private void persist(
            String sampleId,
            String question,
            boolean authoritativeMultiAgent,
            String featureBucket,
            SystemOneRoutingAdvisor.RoutingAdvice primaryAdvice,
            SystemOneRoutingAdvisor.RoutingAdvice challengerAdvice) {
        List<String> disagreements = disagreements(primaryAdvice, challengerAdvice);
        String fingerprint = fingerprint(question);
        boolean reviewEligible = !disagreements.isEmpty();
        boolean agreementControl = !reviewEligible && agreementSample(fingerprint);
        if (!reviewEligible && !agreementControl) {
            metrics.agreementNotRetained();
            return;
        }
        try {
            repository.save(new SystemOneShadowSample(
                sampleId,
                Instant.now(clock),
                fingerprint,
                storeQuestion ? question : "",
                featureBucket,
                authoritativeMultiAgent,
                SystemOneShadowSample.ProviderObservation.from(primaryAdvice),
                SystemOneShadowSample.ProviderObservation.from(challengerAdvice),
                disagreements,
                reviewEligible,
                reviewEligible ? "DISAGREEMENT" : "AGREEMENT_CONTROL"
            ));
            metrics.persisted();
        } catch (RuntimeException exception) {
            metrics.persistenceFailed();
            log.warn("System One dual shadow persistence failed for sample {}: {}",
                    sampleId, exception.getMessage());
        }
    }

    private List<String> disagreements(
            SystemOneRoutingAdvisor.RoutingAdvice primaryAdvice,
            SystemOneRoutingAdvisor.RoutingAdvice challengerAdvice) {
        List<String> reasons = new ArrayList<>();
        if (!"SUCCESS".equals(primaryAdvice.status())
                || !"SUCCESS".equals(challengerAdvice.status())) {
            reasons.add("PROVIDER_FAILURE");
        }
        if (primaryAdvice.recommendedMultiAgent()
                != challengerAdvice.recommendedMultiAgent()) {
            reasons.add("ROUTE_ACTION_DISAGREEMENT");
        }
        if (primaryAdvice.recommendedSafetyGuard()
                != challengerAdvice.recommendedSafetyGuard()) {
            reasons.add("SAFETY_ACTION_DISAGREEMENT");
        }
        if (Math.abs(primaryAdvice.multiAgentProbability()
                - challengerAdvice.multiAgentProbability()) >= probabilityGapThreshold) {
            reasons.add("MULTI_AGENT_PROBABILITY_GAP");
        }
        return List.copyOf(reasons);
    }

    private boolean agreementSample(String fingerprint) {
        int bucket = Math.floorMod(fingerprint.hashCode(), 10_000);
        return bucket < Math.round(agreementSampleRate * 10_000);
    }

    private String fingerprint(String question) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(question.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static SystemOneRoutingAdvisor advisor(
            ObjectMapper objectMapper,
            Environment environment,
            String role,
            ProviderSettings settings) {
        String prefix = "agent.decision.system-one.comparison." + role + ".";
        HttpSystemOneDecisionClient client = new HttpSystemOneDecisionClient(
                objectMapper,
                environment.getProperty(prefix + "base-url", settings.baseUrl()),
                environment.getProperty(prefix + "endpoint-path", settings.endpointPath()),
                environment.getProperty(prefix + "api-key", settings.apiKey()),
                environment.getProperty(prefix + "model", settings.model()),
                environment.getProperty(prefix + "connect-timeout-ms", Long.class, 500L),
                environment.getProperty(prefix + "request-timeout-ms", Long.class, 1_200L)
        );
        return new DefaultSystemOneRoutingAdvisor(
                client,
                true,
                "SHADOW",
                environment.getProperty(prefix + "provider", settings.provider()),
                environment.getProperty(prefix + "multi-agent-threshold", Double.class,
                        settings.multiAgentThreshold()),
                environment.getProperty(prefix + "safety-threshold", Double.class, 0.5),
                environment.getProperty(prefix + "input-price-per-million-usd", Double.class,
                        settings.inputPricePerMillionUsd())
        );
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private record ProviderSettings(
            String provider,
            String baseUrl,
            String endpointPath,
            String apiKey,
            String model,
            double multiAgentThreshold,
            double inputPricePerMillionUsd
    ) {

        static ProviderSettings primary(Environment environment) {
            return new ProviderSettings(
                    "OPENROUTER_JEV",
                    "https://openrouter.ai",
                    "/api/alpha/decisions",
                    environment.getProperty("OPENROUTER_API_KEY", ""),
                    "~typesafe/jev-latest",
                    0.20,
                    0.042
            );
        }

        static ProviderSettings challenger(Environment environment) {
            return new ProviderSettings(
                    "LAYA",
                    "http://localhost:8000",
                    "/v1/systemone",
                    "",
                    "multilingual",
                    0.75,
                    0
            );
        }
    }
}
