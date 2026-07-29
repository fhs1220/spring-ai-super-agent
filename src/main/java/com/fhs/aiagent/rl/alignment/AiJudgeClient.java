package com.fhs.aiagent.rl.alignment;

import com.fhs.aiagent.rl.model.AgentTrajectory;

public interface AiJudgeClient {

    AiJudgeScore judge(AgentTrajectory trajectory, AiJudgeDimension dimension);

    default String contractVersion() {
        return "unspecified-judge-contract";
    }
}
