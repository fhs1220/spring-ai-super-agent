package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
                        new CounterfactualQualityJudge.PairJudgment(
                                0.70, 0.90, 0.95, "multi covers the constraints", "judge-v1"),
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
        assertThat(label.judgeCostMeasured()).isFalse();
        assertThat(repository.findBySampleId("sample-quality")).contains(label);
    }

    @Test
    void requiresHumanReviewForSafetyDisagreementEvenWithConfidentJudge() {
        RagEvaluationVariantExecutor executor = (variant, evaluationCase, chatId) ->
                execution(variant, variant.name(), 1_000, 0.01, variant.name());
        SystemOneCounterfactualLabelingService service = service(
                executor,
                (question, fingerprint, single, multi) ->
                        new CounterfactualQualityJudge.PairJudgment(
                                0.50, 0.90, 0.99, "material difference", "judge-v1"),
                new InMemoryRepository());

        SystemOneCounterfactualLabel label = service.label(sample(
                "sample-safety", "Is this safe?",
                List.of("SAFETY_ACTION_DISAGREEMENT")), "run-2");

        assertThat(label.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(label.humanReviewRequired()).isTrue();
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

    private SystemOneCounterfactualLabelingService service(
            RagEvaluationVariantExecutor executor,
            CounterfactualQualityJudge judge,
            SystemOneCounterfactualLabelRepository repository) {
        return new SystemOneCounterfactualLabelingService(
                executor, judge, repository,
                0.05, 0.05, 0.02, 60_000,
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
