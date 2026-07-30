package com.fhs.aiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerVerificationContractTest {

    @Test
    void detectsEveryMissingRlvrConceptInsteadOfTrustingAReviewerBoolean() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of(
                        "兴趣爱好",
                        "社交圈",
                        "工作|学习|目标",
                        "独立",
                        "平衡",
                        "15分钟|十五分钟",
                        "话术|可以这样说"),
                List.of("推荐课程"),
                140,
                1600,
                true,
                false,
                false,
                3
        );
        String answer = """
                1. 开场（2分钟）：我想和你聊聊自己的成长。
                2. 陈述需求（3分钟）：我希望每天学习新技能。
                3. 请求支持（4分钟）：可以一起调整家务吗？
                4. 倾听反馈（3分钟）：你觉得怎样安排更合适？
                5. 总结共识（3分钟）：今晚先试一次再复盘。[来源 1]
                """;

        AnswerVerificationContract.ContractCheck check =
                contract.check(answer, "[来源 1 | 已婚篇.md]\n共同协商家庭事务。");

        assertThat(check.passed()).isFalse();
        assertThat(check.missingRequirements())
                .contains(
                        "覆盖概念：兴趣爱好",
                        "覆盖概念：社交圈",
                        "覆盖概念：独立",
                        "覆盖概念：平衡",
                        "覆盖概念：话术|可以这样说");
    }

    @Test
    void mergesDirectQuestionConstraintsWithTheExplicitContract() {
        AnswerVerificationContract explicit = new AnswerVerificationContract(
                List.of("独立"),
                List.of(),
                null,
                null,
                true,
                false,
                false,
                0
        );

        AnswerVerificationContract merged = explicit.mergeInferred(
                "不要追问，请给十五分钟话术和三项行动。");

        assertThat(merged.requiredConcepts())
                .contains("独立", "15分钟|十五分钟", "话术|可以这样说");
        assertThat(merged.noFollowUp()).isTrue();
        assertThat(merged.minimumActionItems()).isEqualTo(3);
        assertThat(merged.minimumAnswerChars()).isEqualTo(140);
        assertThat(merged.maximumAnswerChars()).isEqualTo(1600);
    }

    @Test
    void acceptsTheSnakeCaseContractUsedByRlvrDatasets() throws Exception {
        AnswerVerificationContract contract =
                new ObjectMapper().readValue("""
                        {
                          "required_concepts": ["兴趣爱好"],
                          "forbidden_phrases": ["推荐课程"],
                          "minimum_answer_chars": 140,
                          "maximum_answer_chars": 1600,
                          "citation_required": true,
                          "no_follow_up": true,
                          "must_mark_assumptions": false,
                          "minimum_action_items": 3
                        }
                        """, AnswerVerificationContract.class);

        assertThat(contract.requiredConcepts()).containsExactly("兴趣爱好");
        assertThat(contract.forbiddenPhrases()).containsExactly("推荐课程");
        assertThat(contract.minimumAnswerChars()).isEqualTo(140);
        assertThat(contract.noFollowUp()).isTrue();
        assertThat(contract.minimumActionItems()).isEqualTo(3);
    }
}
