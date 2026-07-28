package com.fhs.aiagent.rl.alignment;

import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutomatedAlignmentEvaluatorTest {

    private final AutomatedAlignmentEvaluator evaluator =
            new AutomatedAlignmentEvaluator(4, 0.7, 0.72, 0.75, 0.35, 0.7);

    @Test
    void createsPseudoPositiveWithoutHumanRatingWhenJudgesAgree() {
        AutomatedAlignmentAssessment assessment =
                evaluator.evaluate(trajectory(0.9, null), scores(0.9, 0.95), false);

        assertThat(assessment.supervisionLabel())
                .isEqualTo(SupervisionLabel.PSEUDO_LABELED);
        assertThat(assessment.trainingDecision()).isEqualTo(TrainingDecision.POSITIVE);
        assertThat(assessment.confidence()).isGreaterThan(0.9);
        assertThat(assessment.judgeAgreement()).isGreaterThan(0.95);
        assertThat(assessment.approvedPositive()).isTrue();
    }

    @Test
    void holdsOutConflictingAiJudgesInsteadOfCreatingUnsafePseudoLabel() {
        List<AiJudgeScore> conflicting = List.of(
                score(AiJudgeDimension.INSTRUCTION_FOLLOWING, 1.0, 0.95),
                score(AiJudgeDimension.ACTIONABILITY, 0.0, 0.95),
                score(AiJudgeDimension.LOGICAL_CONSISTENCY, 1.0, 0.95),
                score(AiJudgeDimension.CRITICAL_REVIEW, 0.0, 0.95)
        );

        AutomatedAlignmentAssessment assessment =
                evaluator.evaluate(trajectory(0.9, null), conflicting, false);

        assertThat(assessment.supervisionLabel()).isEqualTo(SupervisionLabel.UNLABELED);
        assertThat(assessment.trainingDecision()).isEqualTo(TrainingDecision.HOLDOUT);
        assertThat(assessment.judgeAgreement()).isZero();
        assertThat(assessment.reasons()).anyMatch(reason -> reason.contains("分歧"));
    }

    @Test
    void evaluationOnlyDataCanNeverBecomeTrainingData() {
        AutomatedAlignmentAssessment assessment =
                evaluator.evaluate(trajectory(0.9, null), scores(0.95, 0.99), true);

        assertThat(assessment.supervisionLabel())
                .isEqualTo(SupervisionLabel.EVALUATION_ONLY);
        assertThat(assessment.trainingDecision()).isEqualTo(TrainingDecision.EXCLUDED);
        assertThat(assessment.approvedPositive()).isFalse();
    }

    @Test
    void rlvrHardFailureOverridesOptimisticAiJudges() {
        AutomatedAlignmentAssessment assessment =
                evaluator.evaluate(trajectory(0.4, null), scores(0.99, 0.99), false);

        assertThat(assessment.supervisionLabel()).isEqualTo(SupervisionLabel.REJECTED);
        assertThat(assessment.trainingDecision()).isEqualTo(TrainingDecision.EXCLUDED);
        assertThat(assessment.reasons()).anyMatch(reason -> reason.contains("证据忠实度"));
    }

    @Test
    void optionalHumanFeedbackRemainsAHighConfidenceAnchor() {
        AutomatedAlignmentAssessment assessment =
                evaluator.evaluate(trajectory(0.9, 5), List.of(), false);

        assertThat(assessment.supervisionLabel())
                .isEqualTo(SupervisionLabel.HUMAN_LABELED);
        assertThat(assessment.trainingDecision()).isEqualTo(TrainingDecision.POSITIVE);
        assertThat(assessment.confidence()).isEqualTo(1.0);
    }

    private List<AiJudgeScore> scores(double score, double confidence) {
        return Arrays.stream(AiJudgeDimension.values())
                .map(dimension -> score(dimension, score, confidence))
                .toList();
    }

    private AiJudgeScore score(AiJudgeDimension dimension,
                               double score,
                               double confidence) {
        return new AiJudgeScore(
                "judge-" + dimension,
                dimension,
                score,
                confidence,
                "测试评审"
        );
    }

    private AgentTrajectory trajectory(double grounding, Integer rating) {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new AgentTrajectory(
                "trajectory-1",
                "chat-1",
                "agentic-rag-v5",
                "qwen-plus",
                "请直接给出一周计划",
                now,
                now,
                "COMPLETED",
                List.of(),
                List.of("doc-1", "doc-2"),
                "星期一至星期日的可执行计划",
                new RewardBreakdown(
                        0.9, 1.0, grounding, 1.0, 1.0, 0.8, 0.9, 0.5),
                rating,
                null,
                null,
                null
        );
    }
}
