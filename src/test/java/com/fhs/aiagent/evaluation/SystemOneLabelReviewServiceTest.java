package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemOneLabelReviewServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void onlyHumanApprovedDevelopmentLabelsExportAndReviewHistorySurvivesRestart() {
        var repository = repository();
        var label = create(repository, "review-sample", "a".repeat(64));
        var reviews = new SystemOneLabelReviewService(repository, mapper, false);
        assertThat(reviews.exportDevelopment().samples()).isEmpty();
        var approved = reviews.review(label.sampleId(), request(0, true));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.evidence().single().answer()).isEqualTo("single answer");
        assertThat(reviews.exportDevelopment().samples()).hasSize(1);
        String fingerprint = reviews.exportDevelopment().fingerprint();
        var restarted = new SystemOneLabelReviewService(repository(), mapper, false);
        assertThat(restarted.exportDevelopment().fingerprint()).isEqualTo(fingerprint);
        assertThatThrownBy(() -> restarted.review(label.sampleId(), request(0, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revision conflict");
        var rejected = restarted.review(label.sampleId(), request(1, false));
        assertThat(rejected.reviews()).hasSize(2);
        assertThat(restarted.exportDevelopment().samples()).isEmpty();
        assertThatThrownBy(() -> repository.save(label))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("append-only");
    }

    @Test
    void holdoutEvidenceAndReviewAreSealedAndNeverEnterTrainingEvenAfterUnlock() {
        var repository = repository();
        var label = create(repository, "holdout-sample", "f".repeat(64));
        assertThat(label.split()).isEqualTo("HOLDOUT");
        var sealed = new SystemOneLabelReviewService(repository, mapper, false);
        assertThatThrownBy(() -> sealed.get(label.sampleId())).hasMessageContaining("sealed");
        assertThatThrownBy(() -> sealed.review(label.sampleId(), request(0, true))).hasMessageContaining("sealed");
        var unlocked = new SystemOneLabelReviewService(repository, mapper, true);
        unlocked.review(label.sampleId(), request(0, true));
        assertThat(unlocked.exportDevelopment().samples()).isEmpty();
    }

    @Test
    void completedAttemptChargesCannotBeDeletedOrRewrittenBeforeReview() {
        var repository = repository();
        var label = create(repository, "immutable-sample", "a".repeat(64));
        var e = label.evidence();
        var attempts = new ArrayList<>(e.attempts());
        var original = attempts.getFirst();
        attempts.set(0, new SystemOneCounterfactualLabel.StageAttempt(original.attemptId(), original.stage(),
                original.startedAt(), original.finishedAt(), original.status(), 0.0, true, ""));
        var changed = new SystemOneCounterfactualLabel.Evidence(e.schemaVersion(), e.question(), e.observation(),
                e.single(), e.multi(), e.judgment(), e.utilityPolicy(), e.provenance(), attempts);
        var tampered = new SystemOneCounterfactualLabel(label.sampleId(), label.runId(), label.labeledAt(),
                label.split(), label.status(), label.questionFingerprint(), label.disagreementTypes(),
                label.forcedSingle(), label.forcedMulti(), label.judgeConfidence(), label.judgeRationale(),
                label.judgeContractVersion(), label.singleUtility(), label.multiUtility(), label.utilityDelta(),
                label.expectedMultiAgent(), label.humanReviewRequired(), label.judgeCostMeasured(),
                label.error(), changed, label.reviews());
        assertThatThrownBy(() -> repository.save(tampered)).hasMessageContaining("stage attempts are immutable");
    }

    @Test
    void runSnapshotsDoNotPolluteLabelListing() {
        var repository = repository();
        create(repository, "sample-one", "a".repeat(64));
        var runs = new FileSystemOneLabelingRunRepository(mapper, directory.toString());
        runs.save(new SystemOneLabelingJobService.RunSnapshot("run-one", "COMPLETED", Instant.now(),
                Instant.now(), 0, 0, "", 20, 10, 0, "", null));
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void exportDerivesBootstrapAndMixedSamplingFramesFromApprovedEvidence() {
        var repository = repository();
        var reviews = new SystemOneLabelReviewService(repository, mapper, false);
        assertThat(reviews.exportDevelopment().samplingFrame()).isEqualTo("EMPTY");
        var bootstrap = create(repository, "bootstrap-export", "f".repeat(64), true);
        assertThat(bootstrap.split()).isEqualTo("DEVELOPMENT");
        reviews.review(bootstrap.sampleId(), request(0, true));
        assertThat(reviews.exportDevelopment().samplingFrame()).isEqualTo("BOOTSTRAP_DEVELOPMENT_ONLY");
        assertThat(reviews.exportDevelopment().samples().getFirst().provenance())
                .containsEntry("sourceDatasetFingerprint", "b".repeat(64));

        var shadow = create(repository, "shadow-export", "a".repeat(64));
        reviews.review(shadow.sampleId(), request(0, true));
        assertThat(reviews.exportDevelopment().samplingFrame()).isEqualTo("MIXED_NOT_POPULATION");
        assertThat(reviews.exportDevelopment().samples()).hasSize(2);
    }

    private FileSystemOneCounterfactualLabelRepository repository() {
        return new FileSystemOneCounterfactualLabelRepository(mapper, directory.toString());
    }

    private SystemOneLabelReviewService.ReviewRequest request(int revision, boolean accepted) {
        return new SystemOneLabelReviewService.ReviewRequest(revision, "test-reviewer", "Checked both answers",
                accepted, accepted ? Boolean.TRUE : null);
    }

    private SystemOneCounterfactualLabel create(SystemOneCounterfactualLabelRepository repository,
            String id, String fingerprint) {
        return create(repository, id, fingerprint, false);
    }

    private SystemOneCounterfactualLabel create(SystemOneCounterfactualLabelRepository repository,
            String id, String fingerprint, boolean bootstrap) {
        RagEvaluationVariantExecutor executor = (variant, evaluationCase, chatId) ->
                new RagVariantExecution(variant,
                        variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "single answer" : "multi answer",
                        100, 100, 0.1, true,
                        variant == RagEvaluationVariant.AGENTIC_SINGLE_AGENT ? "SINGLE_AGENT" : "ADAPTIVE_MULTI_AGENT", "");
        CounterfactualQualityJudge judge = (q, f, single, multi) -> new CounterfactualQualityJudge.PairJudgment(0.6, 0.9, 0.99,
                        "multi meets the requirements", "judge-v2",
                        new CounterfactualQualityJudge.JudgeUsage("test-model", "response", 100, 10, 0.01, true, "hash"));
        var environment = new MockEnvironment().withProperty("spring.ai.retry.max-attempts", "1")
                .withProperty("agent.evaluation.system-one.labeling.sampling-frame", "BOOTSTRAP_DEVELOPMENT_ONLY")
                .withProperty("agent.evaluation.system-one.labeling.source-dataset-fingerprint", "b".repeat(64));
        var service = bootstrap
                ? new SystemOneCounterfactualLabelingService(executor, judge, repository, environment)
                : new SystemOneCounterfactualLabelingService(executor, judge, repository,
                        0.05, 0.05, 1, 60000, 0.01, 0.03, 0.7, 70);
        return service.label(new SystemOneShadowSample(id, Instant.now(), fingerprint, "question", "TEST",
                false, null, null, List.of(), false, bootstrap ? "BOOTSTRAP_DEVELOPMENT_ONLY" : "TEST"), "run");
    }
}
