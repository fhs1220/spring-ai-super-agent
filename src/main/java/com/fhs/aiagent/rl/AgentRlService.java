package com.fhs.aiagent.rl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AgentRlService {

    private final AgentTrajectoryRepository repository;

    private final AgentRewardCalculator rewardCalculator;

    private final ObjectMapper objectMapper;

    public AgentRlService(AgentTrajectoryRepository repository,
                          AgentRewardCalculator rewardCalculator,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.rewardCalculator = rewardCalculator;
        this.objectMapper = objectMapper;
    }

    public AgentTrajectory getTrajectory(String trajectoryId) {
        return repository.findById(trajectoryId)
                .orElseThrow(() -> new NoSuchElementException("Trajectory not found: " + trajectoryId));
    }

    public List<AgentTrajectory> recentTrajectories(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return repository.findAll().stream().limit(safeLimit).toList();
    }

    public AgentTrajectory submitFeedback(String trajectoryId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        AgentTrajectory existing = getTrajectory(trajectoryId);
        AgentTrajectory withFeedback = existing.withRewardAndFeedback(
                existing.reward(), rating, normalizeComment(comment));
        RewardBreakdown reward = rewardCalculator.calculate(withFeedback);
        return repository.save(withFeedback.withRewardAndFeedback(reward, rating, normalizeComment(comment)));
    }

    public Metrics metrics() {
        List<AgentTrajectory> trajectories = repository.findAll().stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status()))
                .toList();
        if (trajectories.isEmpty()) {
            return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        double averageReward = trajectories.stream()
                .filter(trajectory -> trajectory.reward() != null)
                .mapToDouble(trajectory -> trajectory.reward().total())
                .average().orElse(0);
        double groundedRate = trajectories.stream()
                .filter(trajectory -> trajectory.steps().stream().anyMatch(step ->
                        step.type() == AgentStepType.REVIEW
                                && Boolean.TRUE.equals(step.output().get("grounded"))))
                .count() / (double) trajectories.size();
        double averageLatencyMs = trajectories.stream()
                .mapToLong(trajectory -> trajectory.steps().stream()
                        .mapToLong(step -> step.durationMs()).sum())
                .average().orElse(0);
        double averageRating = trajectories.stream()
                .filter(trajectory -> trajectory.userRating() != null)
                .mapToInt(trajectory -> trajectory.userRating())
                .average().orElse(0);
        double multiAgentRate = trajectories.stream()
                .filter(trajectory -> trajectory.steps().stream().anyMatch(step ->
                        step.type() == AgentStepType.ROUTE
                                && "ADAPTIVE_MULTI_AGENT".equals(step.output().get("mode"))))
                .count() / (double) trajectories.size();
        double averageCollaborationQuality = trajectories.stream()
                .filter(trajectory -> trajectory.reward() != null)
                .filter(trajectory -> trajectory.steps().stream()
                        .anyMatch(step -> step.type() == AgentStepType.SPECIALIST))
                .mapToDouble(trajectory -> trajectory.reward().collaborationQuality())
                .average().orElse(0);
        long totalTokens = trajectories.stream()
                .filter(trajectory -> trajectory.telemetry() != null)
                .mapToLong(trajectory -> trajectory.telemetry().totalTokens())
                .sum();
        double estimatedCostCny = trajectories.stream()
                .filter(trajectory -> trajectory.telemetry() != null)
                .mapToDouble(trajectory -> trajectory.telemetry().estimatedCostCny())
                .sum();
        long timeoutCount = trajectories.stream()
                .filter(trajectory -> trajectory.telemetry() != null)
                .mapToLong(trajectory -> trajectory.telemetry().timeoutCount())
                .sum();

        return new Metrics(
                trajectories.size(), round(averageReward), round(groundedRate),
                round(averageLatencyMs), round(averageRating),
                round(multiAgentRate), round(averageCollaborationQuality),
                totalTokens, roundCost(estimatedCostCny), timeoutCount);
    }

    public String exportJsonLines(double minimumReward) {
        return repository.findAll().stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status()))
                .filter(trajectory -> trajectory.reward() != null
                        && trajectory.reward().total() >= minimumReward)
                .map(this::toJson)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String toJson(AgentTrajectory trajectory) {
        try {
            return objectMapper.writeValueAsString(trajectory);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to export trajectory " + trajectory.trajectoryId(), exception);
        }
    }

    private String normalizeComment(String comment) {
        if (comment == null) {
            return "";
        }
        String normalized = comment.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private double roundCost(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    public record Metrics(
            long trajectoryCount,
            double averageReward,
            double groundedRate,
            double averageLatencyMs,
            double averageUserRating,
            double multiAgentRate,
            double averageCollaborationQuality,
            long totalTokens,
            double estimatedCostCny,
            long timeoutCount
    ) {
    }
}
