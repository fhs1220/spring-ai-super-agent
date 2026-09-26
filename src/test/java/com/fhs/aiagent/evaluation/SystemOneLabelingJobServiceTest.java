package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.AgentRunCancelledException;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SystemOneLabelingJobServiceTest {

    @TempDir Path temporaryDirectory;

    @Test
    void zeroBudgetCompletesWithoutReadingSamplesOrCallingModels() throws Exception {
        SystemOneShadowSampleService sampleService = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labelingService =
                mock(SystemOneCounterfactualLabelingService.class);
        EmptyRepository repository = new EmptyRepository();
        SystemOneLabelingJobService service = new SystemOneLabelingJobService(
                sampleService, labelingService, repository, 20, 10, false);
        try {
            SystemOneLabelingJobService.RunSnapshot started = service.start(20, 0.0);
            SystemOneLabelingJobService.RunSnapshot completed = await(service, started.runId());

            assertThat(completed.status()).isEqualTo("COMPLETED_BUDGET_LIMIT");
            assertThat(completed.completedCases()).isZero();
            assertThat(completed.report().budgetLimitReached()).isTrue();
            verifyNoInteractions(sampleService, labelingService);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void sealsHoldoutLabelsByDefault() {
        SystemOneLabelingJobService service = new SystemOneLabelingJobService(
                mock(SystemOneShadowSampleService.class),
                mock(SystemOneCounterfactualLabelingService.class),
                new EmptyRepository(), 20, 10, false);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.labels("HOLDOUT"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sealed");
            assertThat(service.labels("DEVELOPMENT")).isEmpty();
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "REVIEW_REQUIRED", "FAILED"})
    void singleHoldoutRunDoesNotRevealOutcomeThroughReport(String labelStatus) throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        when(samples.recent(1000, false)).thenReturn(List.of(sample("holdout-case")));
        when(labeling.label(any(), anyString())).thenReturn(label(
                "holdout-case", "HOLDOUT", labelStatus, true, true, true, 0.30));
        SystemOneLabelingJobService service = service(samples, labeling, new EmptyRepository());
        try {
            var result = await(service, service.start(1, 10.0).runId());
            assertThat(result.report().holdoutCases()).isEqualTo(1);
            assertThat(result.report().multiAgentPositiveCases()).isZero();
            assertThat(result.report().humanReviewRequiredCases()).isZero();
            assertThat(result.report().failedOrSkippedCases()).isZero();
            assertThat(result.report().sampleIds()).isEmpty();
            assertThat(result.currentSampleId()).isEmpty();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancellationKeepsLeaseUntilNonInterruptibleWorkerActuallyExits() throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        when(samples.recent(1000, false)).thenReturn(List.of(sample("blocked-case")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(labeling.label(any(), anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        interrupted.countDown(); // Model SDK deliberately ignores interruption.
                    }
                }
            }
            return label("blocked-case", "DEVELOPMENT", "COMPLETED", false, false, true, 0.2);
        });
        SystemOneLabelingJobService service = service(samples, labeling, new EmptyRepository());
        try {
            String runId = service.start(1, 10.0).runId();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var cancelling = service.cancel(runId);
            assertThat(cancelling.status()).isEqualTo("CANCELLING");
            assertThat(cancelling.completedAt()).isNull();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.start(1, 10.0))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already active");
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(await(service, runId).status()).isEqualTo("CANCELLED");
            assertThat(await(service, service.start(1, 10.0).runId()).status()).isEqualTo("COMPLETED");
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEVELOPMENT", "HOLDOUT"})
    void cancelledRealLabelerReconcilesPaidCheckpointAndUnknownInterruptedCall(String split) throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneShadowSample source = sample("cancelled-paid-case");
        source = new SystemOneShadowSample(source.sampleId(), source.capturedAt(),
                "HOLDOUT".equals(split) ? "f".repeat(64) : "a".repeat(64), source.question(),
                source.featureBucket(), source.authoritativeMultiAgent(), null, null,
                source.disagreementTypes(), source.reviewEligible(), source.sampledReason());
        when(samples.recent(1000, false)).thenReturn(List.of(source));
        InMemoryRepository repository = new InMemoryRepository();
        CountDownLatch secondCallEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RagEvaluationVariantExecutor executor = (variant, evaluationCase, chatId) -> {
            if (calls.incrementAndGet() == 1) {
                return new RagVariantExecution(variant, "Paid first answer", 10, 100,
                        0.2, true, variant.name(), "");
            }
            secondCallEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                throw new AgentRunCancelledException("Second model call interrupted", exception);
            }
            throw new AssertionError("Expected cancellation");
        };
        var labeling = new SystemOneCounterfactualLabelingService(executor,
                (question, fingerprint, single, multi) -> {
                    throw new AssertionError("Judge must not run after cancellation");
                }, repository, 0.05, 0.05, 1, 60000, 0.01, 0.03, 0.7, 70);
        SystemOneLabelingJobService service = service(samples, labeling, repository);
        try {
            String runId = service.start(1, 10.0).runId();
            assertThat(secondCallEntered.await(2, TimeUnit.SECONDS)).isTrue();
            service.cancel(runId);
            var result = await(service, runId);
            assertThat(result.status()).isEqualTo("CANCELLED");
            assertThat(result.totalCostCny()).isEqualTo(0.2);
            assertThat(result.costAccountingComplete()).isFalse();
            assertThat(result.error()).contains("Known charges only");
            assertThat(result.completedCases()).isEqualTo(1);
            assertThat(result.report().labeledCases()).isEqualTo(1);
            var persisted = repository.findBySampleId("cancelled-paid-case").orElseThrow();
            assertThat(persisted.status()).isEqualTo("UNKNOWN_OUTCOME");
            assertThat(persisted.evidence().attempts()).hasSize(2);
            if ("HOLDOUT".equals(split)) {
                assertThat(result.report().sampleIds()).isEmpty();
                assertThat(result.report().failedOrSkippedCases()).isZero();
            } else {
                assertThat(result.report().sampleIds()).containsExactly("cancelled-paid-case");
                assertThat(result.report().failedOrSkippedCases()).isEqualTo(1);
            }
        } finally {
            service.shutdown();
            release.countDown();
        }
    }

    @Test
    void normalReturnedLabelAndPersistedCheckpointAreNotDoubleCounted() throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        when(samples.recent(1000, false)).thenReturn(List.of(sample("one-charge")));
        InMemoryRepository repository = new InMemoryRepository();
        when(labeling.label(any(), anyString())).thenAnswer(invocation -> repository.save(
                withRun(label("one-charge", "DEVELOPMENT", "COMPLETED", true, false, true, 0.3),
                        invocation.getArgument(1))));
        SystemOneLabelingJobService service = service(samples, labeling, repository);
        try {
            var result = await(service, service.start(1, 10.0).runId());
            assertThat(result.totalCostCny()).isEqualTo(0.3);
            assertThat(result.completedCases()).isEqualTo(1);
            assertThat(result.report().labeledCases()).isEqualTo(1);
            assertThat(result.costAccountingComplete()).isTrue();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void retriesFailedAndCheckpointedLabelsButNeverUnknownOutcomesOrFinishedLabels() throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        InMemoryRepository repository = new InMemoryRepository();
        repository.save(label("a-failed", "DEVELOPMENT", "FAILED", false, false, true, 0.1));
        repository.save(label("b-checkpoint", "DEVELOPMENT", "IN_PROGRESS", false, false, true, 0.1));
        repository.save(label("c-unknown", "DEVELOPMENT", "UNKNOWN_OUTCOME", false, false, false, 0.0));
        repository.save(label("d-finished", "DEVELOPMENT", "COMPLETED", false, false, true, 0.1));
        when(samples.recent(1000, false)).thenReturn(List.of(sample("a-failed"), sample("b-checkpoint"),
                sample("c-unknown"), sample("d-finished")));
        when(labeling.label(any(), anyString())).thenAnswer(invocation -> {
            SystemOneShadowSample sample = invocation.getArgument(0);
            return repository.save(label(sample.sampleId(), "DEVELOPMENT", "COMPLETED", false, false, true, 0.2));
        });
        SystemOneLabelingJobService service = service(samples, labeling, repository);
        try {
            var result = await(service, service.start(20, 10.0).runId());
            assertThat(result.completedCases()).isEqualTo(2);
            assertThat(result.report().sampleIds()).containsExactly("a-failed", "b-checkpoint");
            verify(labeling, times(2)).label(any(), anyString());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void recoversPrunedCheckpointsAndPrefersFrozenObservationWithoutResumingTerminalLabels() throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        InMemoryRepository repository = new InMemoryRepository();
        repository.save(withObservation(label("a-pruned", "DEVELOPMENT", "FAILED",
                false, false, true, 0.1), sampleWithQuestion("a-pruned", "Frozen pruned question")));
        repository.save(withObservation(label("b-changed", "DEVELOPMENT", "IN_PROGRESS",
                false, false, true, 0.1), sampleWithQuestion("b-changed", "Frozen original question")));
        repository.save(withObservation(label("c-unknown", "DEVELOPMENT", "UNKNOWN_OUTCOME",
                false, false, false, 0), sample("c-unknown")));
        repository.save(withObservation(label("d-approved", "DEVELOPMENT", "APPROVED",
                false, false, true, 0.1), sample("d-approved")));
        when(samples.recent(1000, false)).thenReturn(List.of(
                sampleWithQuestion("b-changed", "Replaced shadow question")));
        var observedQuestions = new ConcurrentHashMap<String, String>();
        when(labeling.label(any(), anyString())).thenAnswer(invocation -> {
            SystemOneShadowSample source = invocation.getArgument(0);
            observedQuestions.put(source.sampleId(), source.question());
            return repository.save(withRun(label(source.sampleId(), "DEVELOPMENT", "COMPLETED",
                    false, false, true, 0.2), invocation.getArgument(1)));
        });
        SystemOneLabelingJobService service = service(samples, labeling, repository);
        try {
            var result = await(service, service.start(20, 10.0).runId());
            assertThat(result.completedCases()).isEqualTo(2);
            assertThat(observedQuestions).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "a-pruned", "Frozen pruned question", "b-changed", "Frozen original question"));
            assertThat(result.report().sampleIds()).containsExactly("a-pruned", "b-changed");
            verify(labeling, times(2)).label(any(), anyString());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void stopsAtFirstUnknownChargeInsteadOfContinuingBatch() throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        when(samples.recent(1000, false)).thenReturn(List.of(sample("a-unknown"), sample("b-later")));
        when(labeling.label(any(), anyString())).thenReturn(label(
                "a-unknown", "DEVELOPMENT", "FAILED", false, true, false, 0.2));
        SystemOneLabelingJobService service = service(samples, labeling, new EmptyRepository());
        try {
            var result = await(service, service.start(20, 10.0).runId());
            assertThat(result.status()).isEqualTo("STOPPED_USAGE_UNKNOWN");
            assertThat(result.completedCases()).isEqualTo(1);
            verify(labeling, times(1)).label(any(), anyString());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void stoppingThresholdIncludesAllRecordedAttemptsAndCanOvershootFinalPair() throws Exception {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        when(samples.recent(1000, false)).thenReturn(List.of(sample("a-costly"), sample("b-later")));
        when(labeling.label(any(), anyString())).thenReturn(label(
                "a-costly", "DEVELOPMENT", "COMPLETED", true, false, true, 0.5));
        SystemOneLabelingJobService service = service(samples, labeling, new EmptyRepository());
        try {
            var result = await(service, service.start(20, 0.4).runId());
            assertThat(result.status()).isEqualTo("COMPLETED_BUDGET_LIMIT");
            assertThat(result.totalCostCny()).isEqualTo(0.5);
            assertThat(result.report().judgeCostMeasured()).isTrue();
            verify(labeling, times(1)).label(any(), anyString());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void rejectsNonFiniteOrNegativeCostThresholdBeforeAnyModelCalls() {
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        SystemOneLabelingJobService service = service(samples, labeling, new EmptyRepository());
        try {
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
                assertThatThrownBy(() -> service.start(1, invalid)).isInstanceOf(IllegalArgumentException.class);
            }
            verifyNoInteractions(samples, labeling);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void persistsRunsAndMarksUnfinishedWorkInterruptedAfterRestart() throws Exception {
        FileSystemOneLabelingRunRepository repository = new FileSystemOneLabelingRunRepository(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        repository.save(new SystemOneLabelingJobService.RunSnapshot("previous-run", "RUNNING",
                Instant.parse("2026-09-26T01:00:00Z"), null, 1, 5, "", 20, 10, 0.4, "", null));
        SystemOneShadowSampleService samples = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labeling = mock(SystemOneCounterfactualLabelingService.class);
        SystemOneLabelingJobService service = new SystemOneLabelingJobService(
                samples, labeling, new EmptyRepository(), repository, 20, 10, false);
        String newRunId;
        try {
            assertThat(service.get("previous-run").status()).isEqualTo("INTERRUPTED");
            assertThat(service.get("previous-run").totalCostCny()).isEqualTo(0.4);
            assertThat(service.get("previous-run").completedAt()).isNotNull();
            newRunId = service.start(20, 0.0).runId();
            await(service, newRunId);
            verifyNoInteractions(samples, labeling);
        } finally {
            service.shutdown();
        }
        SystemOneLabelingJobService restored = new SystemOneLabelingJobService(
                samples, labeling, new EmptyRepository(), repository, 20, 10, false);
        try {
            assertThat(restored.get("previous-run").status()).isEqualTo("INTERRUPTED");
            assertThat(restored.get(newRunId).status()).isEqualTo("COMPLETED_BUDGET_LIMIT");
            assertThat(temporaryDirectory.resolve("runs/previous-run.json")).exists();
        } finally {
            restored.shutdown();
        }
    }

    @Test
    void restartReconcilesChargesPersistedAfterLastRunSnapshotWithoutLosingUnknownState() {
        FileSystemOneLabelingRunRepository runs = new FileSystemOneLabelingRunRepository(
                new ObjectMapper().findAndRegisterModules(), temporaryDirectory.toString());
        runs.save(new SystemOneLabelingJobService.RunSnapshot("crashed-run", "RUNNING",
                Instant.parse("2026-09-26T01:00:00Z"), null, 0, 2, "", 20, 10, 0, "", null));
        InMemoryRepository repository = new InMemoryRepository();
        repository.save(withRun(label("paid-checkpoint", "DEVELOPMENT", "IN_PROGRESS",
                false, true, true, 0.2), "crashed-run"));
        repository.save(withRun(label("unknown-checkpoint", "HOLDOUT", "UNKNOWN_OUTCOME",
                false, true, false, 0.1), "crashed-run"));
        SystemOneLabelingJobService service = new SystemOneLabelingJobService(
                mock(SystemOneShadowSampleService.class), mock(SystemOneCounterfactualLabelingService.class),
                repository, runs, 20, 10, false);
        try {
            var result = service.get("crashed-run");
            assertThat(result.status()).isEqualTo("INTERRUPTED");
            assertThat(result.totalCostCny()).isEqualTo(0.3);
            assertThat(result.completedCases()).isEqualTo(2);
            assertThat(result.costAccountingComplete()).isFalse();
            assertThat(result.error()).contains("Known charges only");
            assertThat(result.report().sampleIds()).containsExactly("paid-checkpoint");
            assertThat(result.report().failedOrSkippedCases()).isEqualTo(1);
            assertThat(runs.findAll().getFirst().totalCostCny()).isEqualTo(0.3);
            assertThat(runs.findAll().getFirst().costAccountingComplete()).isFalse();
        } finally {
            service.shutdown();
        }
    }

    private SystemOneLabelingJobService service(SystemOneShadowSampleService samples,
                                               SystemOneCounterfactualLabelingService labeling,
                                               SystemOneCounterfactualLabelRepository repository) {
        return new SystemOneLabelingJobService(samples, labeling, repository, 20, 10, false);
    }

    private SystemOneShadowSample sample(String id) {
        return sampleWithQuestion(id, "Example question");
    }

    private SystemOneShadowSample sampleWithQuestion(String id, String question) {
        return new SystemOneShadowSample(id, Instant.now(), "a".repeat(64), question,
                "TEST", false, null, null, List.of("ROUTE_ACTION_DISAGREEMENT"), true, "DISAGREEMENT");
    }

    private SystemOneCounterfactualLabel label(String id, String split, String status,
                                             boolean positive, boolean review, boolean costKnown, double cost) {
        Instant timestamp = Instant.now();
        var evidence = new SystemOneCounterfactualLabel.Evidence("test", "question", null,
                null, null, null, null, Map.of(), List.of(
                new SystemOneCounterfactualLabel.StageAttempt("attempt", "JUDGE", timestamp,
                        timestamp, "COMPLETED", cost, costKnown, "")));
        return new SystemOneCounterfactualLabel(id, "run", timestamp, split, status,
                "a".repeat(64), List.of(), null, null, 0.9, "private explanation", "judge-v1",
                0.7, 0.9, 0.2, positive, review, costKnown, "", evidence, List.of());
    }

    private SystemOneCounterfactualLabel withRun(SystemOneCounterfactualLabel label, String runId) {
        return new SystemOneCounterfactualLabel(label.sampleId(), runId, label.labeledAt(), label.split(),
                label.status(), label.questionFingerprint(), label.disagreementTypes(), label.forcedSingle(),
                label.forcedMulti(), label.judgeConfidence(), label.judgeRationale(), label.judgeContractVersion(),
                label.singleUtility(), label.multiUtility(), label.utilityDelta(), label.expectedMultiAgent(),
                label.humanReviewRequired(), label.judgeCostMeasured(), label.error(), label.evidence(), label.reviews());
    }

    private SystemOneCounterfactualLabel withObservation(SystemOneCounterfactualLabel label,
                                                       SystemOneShadowSample observation) {
        var evidence = label.evidence();
        evidence = new SystemOneCounterfactualLabel.Evidence(evidence.schemaVersion(), observation.question(),
                observation, evidence.single(), evidence.multi(), evidence.judgment(), evidence.utilityPolicy(),
                evidence.provenance(), evidence.attempts());
        return new SystemOneCounterfactualLabel(label.sampleId(), label.runId(), label.labeledAt(), label.split(),
                label.status(), label.questionFingerprint(), label.disagreementTypes(), label.forcedSingle(),
                label.forcedMulti(), label.judgeConfidence(), label.judgeRationale(), label.judgeContractVersion(),
                label.singleUtility(), label.multiUtility(), label.utilityDelta(), label.expectedMultiAgent(),
                label.humanReviewRequired(), label.judgeCostMeasured(), label.error(), evidence, label.reviews());
    }

    private SystemOneLabelingJobService.RunSnapshot await(
            SystemOneLabelingJobService service, String runId) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            SystemOneLabelingJobService.RunSnapshot snapshot = service.get(runId);
            if (snapshot.completedAt() != null) return snapshot;
            Thread.sleep(10);
        }
        throw new AssertionError("labeling run did not finish");
    }

    private static final class InMemoryRepository implements SystemOneCounterfactualLabelRepository {
        private final Map<String, SystemOneCounterfactualLabel> labels = new ConcurrentHashMap<>();
        @Override public SystemOneCounterfactualLabel save(SystemOneCounterfactualLabel label) {
            labels.put(label.sampleId(), label);
            return label;
        }
        @Override public Optional<SystemOneCounterfactualLabel> findBySampleId(String id) {
            return Optional.ofNullable(labels.get(id));
        }
        @Override public List<SystemOneCounterfactualLabel> findAll() { return List.copyOf(labels.values()); }
    }

    private static final class EmptyRepository
            implements SystemOneCounterfactualLabelRepository {
        @Override
        public SystemOneCounterfactualLabel save(SystemOneCounterfactualLabel label) {
            return label;
        }

        @Override
        public Optional<SystemOneCounterfactualLabel> findBySampleId(String sampleId) {
            return Optional.empty();
        }

        @Override
        public List<SystemOneCounterfactualLabel> findAll() {
            return List.of();
        }
    }
}
