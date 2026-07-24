package com.fhs.aiagent.rl;

import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRewardCalculator {

    public RewardBreakdown calculate(AgentTrajectory trajectory) {
        List<AgentStep> steps = trajectory.steps();
        int documentCount = trajectory.retrievedDocumentIds().size();
        double retrieval = clamp(documentCount / 2.0);

        AgentStep review = lastStep(steps, AgentStepType.REVIEW);
        boolean grounded = booleanOutput(review, "grounded");
        boolean revised = booleanOutput(review, "revised")
                || booleanOutput(lastStep(steps, AgentStepType.REVISE), "revised");
        double grounding = grounded ? 1.0 : revised ? 0.7 : 0.0;

        AgentStep verification = lastStep(steps, AgentStepType.VERIFY);
        boolean sufficient = booleanOutput(verification, "sufficient");
        double evidenceSufficiency = sufficient ? 1.0 : documentCount > 0 ? 0.25 : 0.0;

        boolean hasTaskCompletionReview = hasOutput(review, "taskCompleted");
        boolean taskCompleted = booleanOutput(review, "taskCompleted");
        double taskCompletion;
        if (hasTaskCompletionReview) {
            taskCompletion = taskCompleted ? 1.0 : revised ? 0.8 : 0.0;
        } else {
            // 兼容升级前保存的轨迹：旧审查通过代表答案至少完成了当时的回答任务。
            taskCompletion = grounded ? 1.0 : revised ? 0.8 : 0.0;
        }

        long llmCalls = trajectory.telemetry() == null
                ? steps.stream()
                        .filter(step -> step.type() != AgentStepType.ROUTE
                                && step.type() != AgentStepType.RETRIEVE
                                && step.type() != AgentStepType.FOLLOW_UP)
                        .count()
                : trajectory.telemetry().modelCallCount();
        long followUps = steps.stream().filter(step -> step.type() == AgentStepType.FOLLOW_UP).count();
        double efficiencyPenalty = Math.max(0, llmCalls - 4) * 0.08 + followUps * 0.12;
        double efficiency = clamp(1.0 - efficiencyPenalty);

        double userFeedback = trajectory.userRating() == null
                ? 0.5
                : clamp((trajectory.userRating() - 1) / 4.0);

        double baseTotal = 0.20 * retrieval
                + 0.25 * grounding
                + 0.15 * evidenceSufficiency
                + 0.20 * taskCompletion
                + 0.10 * efficiency
                + 0.10 * userFeedback;
        AgentStep specialist = lastStep(steps, AgentStepType.SPECIALIST);
        double collaboration = collaborationQuality(specialist);
        double total = specialist == null
                ? baseTotal
                : 0.90 * baseTotal + 0.10 * collaboration;

        return new RewardBreakdown(
                round(total), round(retrieval), round(grounding), round(evidenceSufficiency),
                round(taskCompletion), round(collaboration),
                round(efficiency), round(userFeedback));
    }

    private double collaborationQuality(AgentStep specialist) {
        if (specialist == null) {
            return 1.0;
        }
        double averageProcessReward = numberOutput(specialist, "averageProcessReward");
        double agentCount = numberOutput(specialist, "agentCount");
        double successfulAgents = numberOutput(specialist, "successfulAgents");
        double successRatio = agentCount <= 0 ? 0 : successfulAgents / agentCount;
        return clamp(0.65 * averageProcessReward + 0.35 * successRatio);
    }

    private AgentStep lastStep(List<AgentStep> steps, AgentStepType type) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).type() == type) {
                return steps.get(i);
            }
        }
        return null;
    }

    private boolean booleanOutput(AgentStep step, String key) {
        return step != null && Boolean.TRUE.equals(step.output().get(key));
    }

    private boolean hasOutput(AgentStep step, String key) {
        return step != null && step.output().containsKey(key);
    }

    private double numberOutput(AgentStep step, String key) {
        if (step == null || !(step.output().get(key) instanceof Number number)) {
            return 0;
        }
        return number.doubleValue();
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
