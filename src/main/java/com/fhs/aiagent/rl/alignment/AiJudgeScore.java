package com.fhs.aiagent.rl.alignment;

public record AiJudgeScore(
        String judgeId,
        AiJudgeDimension dimension,
        double score,
        double confidence,
        String rationale
) {
}
