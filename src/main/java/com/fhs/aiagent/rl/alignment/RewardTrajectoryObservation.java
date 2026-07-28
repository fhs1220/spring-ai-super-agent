package com.fhs.aiagent.rl.alignment;

public record RewardTrajectoryObservation(
        String sampleId,
        String taskGroup,
        String policyVersion,
        int round,
        double reward,
        double confidence,
        boolean labeledAnchor,
        boolean evaluationOnly
) {
}
