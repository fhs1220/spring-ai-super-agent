package com.fhs.aiagent.rl.alignment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiJudgeClientPromptTest {

    @Test
    void v2PromptCalibratesScoresAndCapsConcreteFailureModes() {
        String instruction = SpringAiJudgeClient.systemPrompt(
                AiJudgeDimension.INSTRUCTION_FOLLOWING);
        String actionability = SpringAiJudgeClient.systemPrompt(
                AiJudgeDimension.ACTIONABILITY);
        String logic = SpringAiJudgeClient.systemPrompt(
                AiJudgeDimension.LOGICAL_CONSISTENCY);
        String critical = SpringAiJudgeClient.systemPrompt(
                AiJudgeDimension.CRITICAL_REVIEW);

        assertThat(SpringAiJudgeClient.CONTRACT_VERSION)
                .isEqualTo("human-light-judge-v2");
        assertThat(instruction).contains("原子要求", "不得高于 0.6");
        assertThat(actionability).contains("检查方法", "不得高于 0.2");
        assertThat(logic).contains("残句", "不得高于 0.3");
        assertThat(critical).contains("仅出现“来源”字样不能证明");
        assertThat(critical).contains("confidence 只表示你对评分判断的把握");
    }
}
