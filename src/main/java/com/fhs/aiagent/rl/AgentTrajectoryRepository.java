package com.fhs.aiagent.rl;

import com.fhs.aiagent.rl.model.AgentTrajectory;

import java.util.List;
import java.util.Optional;

public interface AgentTrajectoryRepository {

    AgentTrajectory save(AgentTrajectory trajectory);

    Optional<AgentTrajectory> findById(String trajectoryId);

    List<AgentTrajectory> findAll();
}
