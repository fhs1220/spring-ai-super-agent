package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import com.fhs.aiagent.rl.model.AgentTrace;
import com.fhs.aiagent.rl.model.AgentTraceStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemOneCounterfactualLabelingServiceTest {

    @Test
    void forcesBothVariantsAndLabelsMultiAgentWhenUtilityImproves() {
        List<RagEvaluationVariant> variants = new ArrayList<>();
        RagEvaluationVariantExecutor executor = (variant, evaluationCase, chatId) -> {
            variants.add(variant);
            return variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT
                    ? execution(variant, "single", 1_000, 0.01, "SINGLE_AGENT")
                    : execution(variant, "multi", 2_000, 0.02, "ADAPTIVE_MULTI_AGENT");
        };
        InMemoryRepository repository = new InMemoryRepository();
        SystemOneCounterfactualLabelingService service = service(
                executor,
                (question, fingerprint, single, multi) ->
                        judgment(),
                repository);

        SystemOneCounterfactualLabel label = service.label(sample(
                "sample-quality", "What should we do?", List.of("ROUTE_DISAGREEMENT")),
                "run-1");

        assertThat(variants).containsExactly(
                RagEvaluationVariant.AGENTIC_SINGLE_AGENT,
                RagEvaluationVariant.AGENTIC_MULTI_AGENT);
        assertThat(label.status()).isEqualTo("COMPLETED");
        assertThat(label.expectedMultiAgent()).isTrue();
        assertThat(label.utilityDelta()).isGreaterThan(0.17);
        assertThat(label.humanReviewRequired()).isFalse();
        assertThat(label.forcedSingle().executionMode()).isEqualTo("SINGLE_AGENT");
        assertThat(label.forcedMulti().executionMode()).isEqualTo("ADAPTIVE_MULTI_AGENT");
        assertThat(label.judgeCostMeasured()).isTrue();
        assertThat(label.totalEstimatedCostCny()).isEqualTo(0.035);
        assertThat(label.evidence().single().answer()).isEqualTo("single");
        assertThat(label.evidence().multi().answer()).isEqualTo("multi");
        assertThat(label.evidence().attempts()).hasSize(3);
        assertThat(label.trainingEligible()).isFalse();
        assertThat(repository.findBySampleId("sample-quality")).contains(label);
    }

    @Test
    void requiresHumanReviewForSafetyDisagreementEvenWithConfidentJudge() {
        RagEvaluationVariantExecutor executor = (variant, evaluationCase, chatId) ->
                execution(variant, variant.name(), 1_000, 0.01,
                        variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT");
        SystemOneCounterfactualLabelingService service = service(
                executor,
                (question, fingerprint, single, multi) ->
                        judgment(),
                new InMemoryRepository());

        SystemOneCounterfactualLabel label = service.label(sample(
                "sample-safety", "Is this safe?",
                List.of("SAFETY_ACTION_DISAGREEMENT")), "run-2");

        assertThat(label.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(label.humanReviewRequired()).isTrue();
        assertThat(label.expectedMultiAgent()).isNull();
    }

    @Test
    void skipsSamplesWhoseQuestionWasNotRetained() {
        List<RagEvaluationVariant> variants = new ArrayList<>();
        SystemOneCounterfactualLabelingService service = service(
                (variant, evaluationCase, chatId) -> {
                    variants.add(variant);
                    return execution(variant, "unused", 1, 0, "unused");
                },
                (question, fingerprint, single, multi) -> {
                    throw new AssertionError("judge must not run");
                },
                new InMemoryRepository());

        SystemOneCounterfactualLabel label = service.label(
                sample("sample-private", "", List.of()), "run-3");

        assertThat(label.status()).isEqualTo("SKIPPED_NO_QUESTION");
        assertThat(label.expectedMultiAgent()).isNull();
        assertThat(variants).isEmpty();
    }

    @Test
    void retriesKnownJudgeFailureWithoutRegeneratingAnswersAndKeepsOriginalObservation() {
        AtomicInteger generations = new AtomicInteger();
        AtomicInteger judgments = new AtomicInteger();
        InMemoryRepository repository = new InMemoryRepository();
        SystemOneCounterfactualLabelingService service = service(
                (variant, evaluationCase, chatId) -> {
                    generations.incrementAndGet();
                    return execution(variant, "answer", 100, 0.1,
                            variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT");
                }, (q, f, s, m) -> {
                    if (judgments.incrementAndGet() == 1) {
                        throw new SpringCounterfactualQualityJudge.JudgeResponseException(
                                judgment().usage(), new IllegalStateException("invalid json"));
                    }
                    return judgment();
                }, repository);
        SystemOneShadowSample original = sample("retry-sample", "question", List.of());
        assertThat(service.label(original, "run-1").status()).isEqualTo("FAILED");
        var restored = service(executorThatMustNotRun(), (q, f, s, m) -> judgment(), repository);
        var label = restored.label(sample("retry-sample", "question",
                List.of("SAFETY_ACTION_DISAGREEMENT")), "run-2");

        assertThat(generations.get()).isEqualTo(2);
        assertThat(label.status()).isEqualTo("COMPLETED");
        assertThat(label.disagreementTypes()).isEmpty();
        assertThat(label.evidence().observation()).isEqualTo(original);
        assertThat(label.evidence().attempts()).hasSize(4);
        assertThat(label.totalEstimatedCostCny()).isCloseTo(0.21,
                org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void unknownGenerationChargeStopsFurtherCallsAndBlocksAutomaticRetry() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryRepository repository = new InMemoryRepository();
        var service = service((variant, evaluationCase, chatId) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("network lost after sending request");
        }, (q, f, s, m) -> { throw new AssertionError("Judge must not run"); }, repository);
        var sample = sample("unknown-sample", "question", List.of());

        var first = service.label(sample, "run-1");
        var second = service.label(sample, "run-2");

        assertThat(first.status()).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(first.costAccountingComplete()).isFalse();
        assertThat(second).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void unfinishedProviderCallIsNotRepeatedAfterProcessRestart() {
        InMemoryRepository repository = new InMemoryRepository();
        var crashed = service((variant, evaluationCase, chatId) -> {
            throw new AssertionError("simulate process exit after durable PENDING");
        }, (q, f, s, m) -> judgment(), repository);
        var sample = sample("crashed-sample", "question", List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashed.label(sample, "run-1"))
                .isInstanceOf(AssertionError.class);
        assertThat(repository.findBySampleId(sample.sampleId()).orElseThrow().status()).isEqualTo("IN_PROGRESS");
        var restarted = service(executorThatMustNotRun(), (q, f, s, m) -> judgment(), repository);

        assertThat(restarted.label(sample, "run-2").status()).isEqualTo("UNKNOWN_OUTCOME");
    }

    @Test
    void emptyAnswerPairDoesNotSpendOnJudge() {
        var service = service((variant, evaluationCase, chatId) ->
                execution(variant, "", 100, 0.1,
                        variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT"),
                (q, f, s, m) -> { throw new AssertionError("Judge must not run"); }, new InMemoryRepository());
        var label = service.label(sample("empty-sample", "question", List.of()), "run");
        assertThat(label.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(label.evidence().attempts()).hasSize(2);
        assertThat(label.evidence().judgment()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-mode", "multi-mode", "single-variant", "multi-variant"})
    void mismatchedExecutionModeOrVariantCannotBecomeCounterfactualEvidence(String mismatch) {
        AtomicInteger judgeCalls = new AtomicInteger();
        var service = service((variant, evaluationCase, chatId) -> {
            boolean single = variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT;
            String mode = single ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT";
            RagEvaluationVariant reportedVariant = variant;
            if (mismatch.equals(single ? "single-mode" : "multi-mode")) {
                mode = single ? "ADAPTIVE_MULTI_AGENT" : "SINGLE_AGENT";
            }
            if (mismatch.equals(single ? "single-variant" : "multi-variant")) {
                reportedVariant = single ? RagEvaluationVariant.AGENTIC_MULTI_AGENT
                        : RagEvaluationVariant.AGENTIC_SINGLE_AGENT;
            }
            return execution(reportedVariant, "valid answer", 100, 0.1, mode);
        }, (q, f, s, m) -> {
            judgeCalls.incrementAndGet();
            return judgment();
        }, new InMemoryRepository());

        var label = service.label(sample("mismatched-pair", "question", List.of()), "run");

        assertThat(label.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(label.evidence().completePair()).isFalse();
        assertThat(label.evidence().judgment()).isNull();
        assertThat(label.expectedMultiAgent()).isNull();
        assertThat(label.trainingEligible()).isFalse();
        assertThat(judgeCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"input-price-per-million-tokens-cny", "output-price-per-million-tokens-cny"})
    void freezesConfiguredPricesAndRejectsPriceChangesOnResume(String changedPrice) {
        InMemoryRepository repository = new InMemoryRepository();
        MockEnvironment initialEnvironment = pricedEnvironment();
        var initial = new SystemOneCounterfactualLabelingService(
                (variant, evaluationCase, chatId) -> execution(variant, "answer", 100, 0.1,
                        variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT"),
                (q, f, s, m) -> {
                    throw new SpringCounterfactualQualityJudge.JudgeResponseException(
                            judgment().usage(), new IllegalStateException("invalid JSON"));
                }, repository, initialEnvironment);
        var sample = sample("price-change", "question", List.of());
        var failed = initial.label(sample, "run-one");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.costAccountingComplete()).isTrue();
        assertThat(failed.evidence().provenance())
                .containsEntry("inputPriceCnyPerMillionTokens", "2.0")
                .containsEntry("outputPriceCnyPerMillionTokens", "8.0");

        var changedEnvironment = pricedEnvironment()
                .withProperty("agent.rag.observability." + changedPrice, "16");
        var restored = new SystemOneCounterfactualLabelingService(executorThatMustNotRun(),
                (q, f, s, m) -> { throw new AssertionError("Judge must not run after a pricing change"); },
                repository, changedEnvironment);

        assertThatThrownBy(() -> restored.label(sample, "run-two"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Checkpoint identity changed");
        assertThat(repository.findBySampleId(sample.sampleId())).contains(failed);
    }

    @Test
    void sdkRetryPreflightRejectsTwoAttemptsWithoutAnyModelCallOrCheckpoint() {
        AtomicInteger generationCalls = new AtomicInteger();
        AtomicInteger judgeCalls = new AtomicInteger();
        InMemoryRepository repository = new InMemoryRepository();
        var service = new SystemOneCounterfactualLabelingService(
                (variant, evaluationCase, chatId) -> {
                    generationCalls.incrementAndGet();
                    return execution(variant, "unused", 100, 0.1, "unused");
                }, (q, f, s, m) -> {
                    judgeCalls.incrementAndGet();
                    return judgment();
                }, repository, pricedEnvironment().withProperty("spring.ai.retry.max-attempts", "2"));

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_AI_RETRY_MAX_ATTEMPTS=1");
        assertThatThrownBy(() -> service.label(sample("retry-preflight", "question", List.of()), "run"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SPRING_AI_RETRY_MAX_ATTEMPTS=1");
        assertThat(generationCalls).hasValue(0);
        assertThat(judgeCalls).hasValue(0);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void multiAgentFallbackKeepsKnownGenerationChargesAndSkipsJudge() {
        AtomicInteger judgeCalls = new AtomicInteger();
        var service = service((variant, evaluationCase, chatId) -> {
            if (variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT) {
                return execution(variant, "single answer", 100, 0.1, "SINGLE_AGENT");
            }
            var trace = new AgentTrace(200, "ADAPTIVE_MULTI_AGENT", List.of(
                    new AgentTraceStep("SYNTHESIZE", "synthesis", "failed", 100, false, List.of()),
                    new AgentTraceStep("GENERATE", "fallback", "single answer", 100, true, List.of())),
                    List.of(), null);
            return new RagVariantExecution(variant, "fallback answer", 200, 100, 0.2,
                    true, "ADAPTIVE_MULTI_AGENT", "", trace);
        }, (q, f, s, m) -> {
            judgeCalls.incrementAndGet();
            return judgment();
        }, new InMemoryRepository());

        var label = service.label(sample("fallback-pair", "question", List.of()), "run");

        assertThat(label.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(label.error()).contains("multi-agent fallback");
        assertThat(label.forcedMulti().executionMode()).isEqualTo("SINGLE_AGENT_FALLBACK");
        assertThat(label.evidence().attempts()).hasSize(2);
        assertThat(label.costAccountingComplete()).isTrue();
        assertThat(label.totalEstimatedCostCny()).isCloseTo(0.3,
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(label.evidence().judgment()).isNull();
        assertThat(label.trainingEligible()).isFalse();
        assertThat(judgeCalls).hasValue(0);
    }

    @Test
    void bootstrapDevelopmentQuestionsNeverBecomeHoldoutAndFreezeDatasetSource() {
        var repository = new InMemoryRepository();
        var service = new SystemOneCounterfactualLabelingService(
                (variant, evaluationCase, chatId) -> execution(variant, "answer", 100, 0.1,
                        variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT"),
                (q, f, s, m) -> judgment(), repository, bootstrapEnvironment());

        var sample = bootstrapSample("bootstrap-source", "f".repeat(64));
        var label = service.label(sample, "run");

        assertThat(label.split()).isEqualTo("DEVELOPMENT");
        assertThat(label.evidence().provenance())
                .containsEntry("samplingFrame", "BOOTSTRAP_DEVELOPMENT_ONLY")
                .containsEntry("sourceDatasetFingerprint", "b".repeat(64));
        var differentDataset = bootstrapEnvironment().withProperty(
                "agent.evaluation.system-one.labeling.source-dataset-fingerprint", "c".repeat(64));
        var restored = new SystemOneCounterfactualLabelingService(executorThatMustNotRun(),
                (q, f, s, m) -> { throw new AssertionError("Changed source must not call Judge"); },
                repository, differentDataset);
        assertThatThrownBy(() -> restored.label(sample, "next-run"))
                .hasMessageContaining("Checkpoint sampling source changed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSPECIFIED", "", "not-a-sha256"})
    void bootstrapRequiresValidDatasetFingerprintBeforeAnyCall(String fingerprint) {
        var repository = new InMemoryRepository();
        var environment = bootstrapEnvironment().withProperty(
                "agent.evaluation.system-one.labeling.source-dataset-fingerprint", fingerprint);
        var service = new SystemOneCounterfactualLabelingService(executorThatMustNotRun(),
                (q, f, s, m) -> { throw new AssertionError("Invalid source must not call Judge"); },
                repository, environment);

        assertThatThrownBy(() -> service.label(bootstrapSample("invalid-source", "f".repeat(64)), "run"))
                .hasMessageContaining("valid source-dataset-fingerprint SHA-256");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void bootstrapAndShadowSampleMarkersCannotBeMixedByMisconfiguration() {
        var repository = new InMemoryRepository();
        CounterfactualQualityJudge judge = (q, f, s, m) -> { throw new AssertionError("Judge must not run"); };
        var bootstrap = new SystemOneCounterfactualLabelingService(executorThatMustNotRun(), judge,
                repository, bootstrapEnvironment());
        var shadow = new SystemOneCounterfactualLabelingService(executorThatMustNotRun(), judge,
                repository, pricedEnvironment());

        assertThatThrownBy(() -> bootstrap.label(sample("shadow", "question", List.of()), "run"))
                .hasMessageContaining("sampledReason does not match");
        assertThatThrownBy(() -> shadow.label(bootstrapSample("bootstrap", "f".repeat(64)), "run"))
                .hasMessageContaining("sampledReason does not match");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void rejectsUnsupportedSamplingFrameBeforeStartingCalls() {
        var service = new SystemOneCounterfactualLabelingService(executorThatMustNotRun(),
                (q, f, s, m) -> { throw new AssertionError("Judge must not run"); },
                new InMemoryRepository(), pricedEnvironment().withProperty(
                        "agent.evaluation.system-one.labeling.sampling-frame", "RANDOM_TRAFFIC"));

        assertThatThrownBy(service::validateConfiguration).hasMessageContaining("Unsupported labeling sampling-frame");
    }

    private MockEnvironment bootstrapEnvironment() {
        return pricedEnvironment().withProperty("agent.evaluation.system-one.labeling.sampling-frame",
                        "BOOTSTRAP_DEVELOPMENT_ONLY")
                .withProperty("agent.evaluation.system-one.labeling.source-dataset-fingerprint", "b".repeat(64));
    }

    private SystemOneShadowSample bootstrapSample(String id, String fingerprint) {
        return new SystemOneShadowSample(id, Instant.parse("2026-09-26T00:00:00Z"), fingerprint,
                "existing development question", "BOOTSTRAP", false, null, null,
                List.of(), false, "BOOTSTRAP_DEVELOPMENT_ONLY");
    }

    private MockEnvironment pricedEnvironment() {
        return new MockEnvironment().withProperty("spring.ai.retry.max-attempts", "1")
                .withProperty("agent.rag.observability.input-price-per-million-tokens-cny", "2")
                .withProperty("agent.rag.observability.output-price-per-million-tokens-cny", "8");
    }

    private RagEvaluationVariantExecutor executorThatMustNotRun() {
        return (variant, evaluationCase, chatId) -> { throw new AssertionError("Generation must reuse checkpoint"); };
    }

    private CounterfactualQualityJudge.PairJudgment judgment() {
        return new CounterfactualQualityJudge.PairJudgment(0.70, 0.90, 0.95,
                "multi covers the constraints", "judge-v2",
                new CounterfactualQualityJudge.JudgeUsage("test-model", "response-1", 100, 20,
                        0.005, true, "a".repeat(64)));
    }

    private SystemOneCounterfactualLabelingService service(
            RagEvaluationVariantExecutor executor,
            CounterfactualQualityJudge judge,
            SystemOneCounterfactualLabelRepository repository) {
        return new SystemOneCounterfactualLabelingService(
                executor, judge, repository,
                0.05, 0.05, 1.0, 60_000,
                0.01, 0.03, 0.70, 70);
    }

    private RagVariantExecution execution(
            RagEvaluationVariant variant, String answer, long latency,
            double cost, String mode) {
        return new RagVariantExecution(variant, answer, latency, 100,
                cost, true, mode, "");
    }

    private SystemOneShadowSample sample(
            String id, String question, List<String> disagreements) {
        SystemOneShadowSample.ProviderObservation observation =
                new SystemOneShadowSample.ProviderObservation(
                        "SUCCESS", false, 0.2, false, 0.1,
                        10, "test", 10, 1, 0);
        return new SystemOneShadowSample(
                id, Instant.parse("2026-09-26T00:00:00Z"), "a".repeat(64),
                question, "RELATIONSHIP", false, observation, observation,
                disagreements, !disagreements.isEmpty(), "TEST");
    }

    private static final class InMemoryRepository
            implements SystemOneCounterfactualLabelRepository {
        private final List<SystemOneCounterfactualLabel> labels = new ArrayList<>();

        @Override
        public SystemOneCounterfactualLabel save(SystemOneCounterfactualLabel label) {
            labels.removeIf(existing -> existing.sampleId().equals(label.sampleId()));
            labels.add(label);
            return label;
        }

        @Override
        public Optional<SystemOneCounterfactualLabel> findBySampleId(String sampleId) {
            return labels.stream().filter(label -> label.sampleId().equals(sampleId)).findFirst();
        }

        @Override
        public List<SystemOneCounterfactualLabel> findAll() {
            return List.copyOf(labels);
        }
    }
}
