package com.fhs.aiagent.rl.model;

import java.time.Instant;
import java.util.List;

public record AgentTrajectory(
        String trajectoryId,
        String chatId,
        String policyVersion,
        String model,
        String question,
        Instant startedAt,
        Instant completedAt,
        String status,
        List<AgentStep> steps,
        List<String> retrievedDocumentIds,
        String finalAnswer,
        RewardBreakdown reward,
        Integer userRating,
        String feedbackComment,
        AgentRunMetrics telemetry,
        String error
) {
    public AgentTrajectory withRewardAndFeedback(RewardBreakdown newReward,
                                                 Integer newRating,
                                                 String newComment) {
        return new AgentTrajectory(
                trajectoryId, chatId, policyVersion, model, question, startedAt, completedAt, status, steps,
                retrievedDocumentIds, finalAnswer, newReward, newRating, newComment, telemetry, error);
    }
}
