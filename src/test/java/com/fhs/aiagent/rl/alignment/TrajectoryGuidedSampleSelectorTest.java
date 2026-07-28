package com.fhs.aiagent.rl.alignment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrajectoryGuidedSampleSelectorTest {

    private final TrajectoryGuidedSampleSelector selector =
            new TrajectoryGuidedSampleSelector();

    @Test
    void selectsUnlabeledSampleWhoseLearningDynamicsMatchLabeledAnchor() {
        List<RewardTrajectoryObservation> observations = new ArrayList<>();
        addTrajectory(observations, "anchor", List.of(0.2, 0.5, 0.8), 0.99, true, false);
        addTrajectory(observations, "similar", List.of(0.1, 0.45, 0.9), 0.9, false, false);
        addTrajectory(observations, "opposite", List.of(0.9, 0.5, 0.1), 0.9, false, false);
        addTrajectory(observations, "benchmark", List.of(0.2, 0.5, 0.8), 1.0, false, true);

        TrajectoryGuidedSampleSelector.SelectionReport report = selector.select(
                observations,
                new TrajectoryGuidedSampleSelector.SelectionOptions(3, 0.1, 0.8, 0.75)
        );

        assertThat(report.labeledAnchorSampleCount()).isEqualTo(1);
        assertThat(report.unlabeledCandidateSampleCount()).isEqualTo(2);
        assertThat(report.selectedSampleCount()).isEqualTo(1);
        assertThat(report.evaluationOnlyExcludedCount()).isEqualTo(1);
        assertThat(report.decisions())
                .filteredOn(TrajectoryGuidedSampleSelector.CandidateDecision::selected)
                .extracting(TrajectoryGuidedSampleSelector.CandidateDecision::sampleId)
                .containsExactly("similar");
    }

    @Test
    void doesNotSelectHighSimilarityCandidateWhenJudgeConfidenceIsLow() {
        List<RewardTrajectoryObservation> observations = new ArrayList<>();
        addTrajectory(observations, "anchor", List.of(0.2, 0.5, 0.8), 0.99, true, false);
        addTrajectory(observations, "uncertain", List.of(0.1, 0.45, 0.9), 0.4, false, false);

        TrajectoryGuidedSampleSelector.SelectionReport report = selector.select(
                observations,
                new TrajectoryGuidedSampleSelector.SelectionOptions(3, 0.1, 0.8, 0.75)
        );

        assertThat(report.selectedSampleCount()).isZero();
        assertThat(report.decisions().get(0).reason()).contains("置信度不足");
    }

    @Test
    void excludesWholeSampleIfAnyObservationIsMarkedEvaluationOnly() {
        List<RewardTrajectoryObservation> observations = new ArrayList<>();
        addTrajectory(observations, "anchor", List.of(0.2, 0.5, 0.8), 0.99, true, false);
        addTrajectory(observations, "mixed", List.of(0.2, 0.5, 0.8), 0.95, false, false);
        observations.add(new RewardTrajectoryObservation(
                "mixed", "weekly-plan", "policy-v3", 3, 0.9, 0.95, false, true));

        TrajectoryGuidedSampleSelector.SelectionReport report = selector.select(
                observations,
                new TrajectoryGuidedSampleSelector.SelectionOptions(3, 0.1, 0.8, 0.75)
        );

        assertThat(report.evaluationOnlyExcludedCount()).isEqualTo(1);
        assertThat(report.unlabeledCandidateSampleCount()).isZero();
        assertThat(report.selectedSampleCount()).isZero();
    }

    private void addTrajectory(List<RewardTrajectoryObservation> target,
                               String sampleId,
                               List<Double> rewards,
                               double confidence,
                               boolean anchor,
                               boolean evaluationOnly) {
        for (int round = 0; round < rewards.size(); round++) {
            target.add(new RewardTrajectoryObservation(
                    sampleId,
                    "weekly-plan",
                    "policy-v" + round,
                    round,
                    rewards.get(round),
                    confidence,
                    anchor,
                    evaluationOnly
            ));
        }
    }
}
