package com.fhs.aiagent.rl;

import com.fhs.aiagent.rl.model.AgentTrajectory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryAgentTrajectoryRepository implements AgentTrajectoryRepository {

    private final Map<String, AgentTrajectory> trajectories = new ConcurrentHashMap<>();

    @Override
    public AgentTrajectory save(AgentTrajectory trajectory) {
        trajectories.put(trajectory.trajectoryId(), trajectory);
        return trajectory;
    }

    @Override
    public Optional<AgentTrajectory> findById(String trajectoryId) {
        return Optional.ofNullable(trajectories.get(trajectoryId));
    }

    @Override
    public List<AgentTrajectory> findAll() {
        return trajectories.values().stream()
                .sorted(Comparator.comparing(AgentTrajectory::startedAt).reversed())
                .toList();
    }
}
