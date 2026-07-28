package com.fhs.aiagent.rl.alignment;

import java.time.Instant;
import java.util.List;

public record AutomatedAlignmentAssessment(
        String trajectoryId,
        String questionFingerprint,
        String policyVersion,
        Instant evaluatedAt,
        SupervisionLabel supervisionLabel,
        TrainingDecision trainingDecision,
        double verifierReward,
        double aiReward,
        double totalReward,
        double confidence,
        double judgeAgreement,
        int judgeCount,
        List<AiJudgeScore> judgeScores,
        List<String> reasons,
        boolean evaluationOnly
) {
    public boolean approvedPositive() {
        return trainingDecision == TrainingDecision.POSITIVE
                && supervisionLabel != SupervisionLabel.EVALUATION_ONLY
                && supervisionLabel != SupervisionLabel.REJECTED;
    }
}
