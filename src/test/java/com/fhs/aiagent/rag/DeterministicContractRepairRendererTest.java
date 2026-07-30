package com.fhs.aiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicContractRepairRendererTest {

    @Test
    void rendersModelSuppliedEvidenceAndActionsWithoutInventingTheirContent() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of("用心", "边界|舒适"),
                List.of(),
                1,
                1600,
                false,
                false,
                false,
                3
        );
        StructuredContractRepair repair = new StructuredContractRepair(
                "建议从双方可接受的小事开始。",
                List.of(
                        new StructuredContractRepair.Evidence(
                                "concept-01",
                                "提前了解偏好并记录对方真正重视的小事。"),
                        new StructuredContractRepair.Evidence(
                                "concept-02",
                                "先确认双方可接受的范围，不以惊喜为名施加压力。")
                ),
                List.of(
                        "询问近期偏好并记下答案。",
                        "共同确认预算和时间范围。",
                        "完成后询问感受并记录调整点。"
                ),
                ""
        );

        DeterministicContractRepairRenderer.RenderResult result =
                DeterministicContractRepairRenderer.render(
                        repair, contract, "");

        assertThat(result.contractPassed()).isTrue();
        assertThat(result.renderedConceptSections()).isEqualTo(2);
        assertThat(result.renderedActionItems()).isEqualTo(3);
        assertThat(result.answer())
                .contains(
                        "**用心**：提前了解偏好并记录对方真正重视的小事。",
                        "**边界**：先确认双方可接受的范围，不以惊喜为名施加压力。",
                        "1. 询问近期偏好并记下答案。",
                        "2. 共同确认预算和时间范围。",
                        "3. 完成后询问感受并记录调整点。");
    }

    @Test
    void refusesKeywordOnlyEvidenceAndInsufficientActions() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of("社交圈"),
                List.of(),
                1,
                1600,
                false,
                false,
                false,
                3
        );
        StructuredContractRepair repair = new StructuredContractRepair(
                "建议从小目标开始。",
                List.of(new StructuredContractRepair.Evidence(
                        "concept-01", "社交圈")),
                List.of("行动一", "行动二"),
                ""
        );

        DeterministicContractRepairRenderer.RenderResult result =
                DeterministicContractRepairRenderer.render(
                        repair, contract, "");

        assertThat(result.contractPassed()).isFalse();
        assertThat(result.acceptedStructuredEvidence()).isFalse();
        assertThat(result.missingRequirements())
                .contains(
                        "覆盖概念：社交圈",
                        "至少提供 3 个行动项");
        assertThat(result.unresolvedRequirementIds())
                .containsExactly("concept-01", "action-items");
        assertThat(result.answer()).doesNotContain("**社交圈**");
    }

    @Test
    void neverDeletesForbiddenPhrasesOrFabricatesCitations() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of("独立"),
                List.of("推荐课程"),
                1,
                1600,
                true,
                false,
                false,
                0
        );
        StructuredContractRepair repair = new StructuredContractRepair(
                "推荐课程可以快速解决问题。",
                List.of(new StructuredContractRepair.Evidence(
                        "独立",
                        "保留可自主安排的学习时间，并与家庭责任协调。")),
                List.of(),
                ""
        );
        String context = "[来源 1 | 已婚篇.md]\n应兼顾个人成长和家庭责任。";

        DeterministicContractRepairRenderer.RenderResult result =
                DeterministicContractRepairRenderer.render(
                        repair, contract, context);

        assertThat(result.contractPassed()).isFalse();
        assertThat(result.answer()).contains("推荐课程");
        assertThat(result.answer()).doesNotContain("[来源 1]");
        assertThat(result.missingRequirements())
                .contains(
                        "删除禁用短语：推荐课程",
                        "使用有效的 [来源 n] 单编号引用");
    }

    @Test
    void marksAssumptionsOnlyFromSubstantiveModelContent() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of(),
                List.of(),
                1,
                1600,
                false,
                false,
                true,
                0
        );
        StructuredContractRepair repair = new StructuredContractRepair(
                "可以先执行低风险方案。",
                List.of(),
                List.of(),
                "目前没有完整预算信息，先按零额外支出设计。"
        );

        DeterministicContractRepairRenderer.RenderResult result =
                DeterministicContractRepairRenderer.render(
                        repair, contract, "");

        assertThat(result.contractPassed()).isTrue();
        assertThat(result.assumptionsRendered()).isTrue();
        assertThat(result.answer())
                .contains("**合理假设**：目前没有完整预算信息");
    }

    @Test
    void normalizesModelSuppliedActionNumbersBeforeRendering() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of(),
                List.of(),
                1,
                1600,
                false,
                false,
                false,
                3
        );
        StructuredContractRepair repair = new StructuredContractRepair(
                "今天开始执行。",
                List.of(),
                List.of(
                        "1. 检查个人卫生。",
                        "2、联系一位朋友沟通近况。",
                        "3) 安排十分钟快走。"
                ),
                ""
        );

        DeterministicContractRepairRenderer.RenderResult result =
                DeterministicContractRepairRenderer.render(
                        repair, contract, "");

        assertThat(result.contractPassed()).isTrue();
        assertThat(result.answer())
                .contains(
                        "1. 检查个人卫生。",
                        "2. 联系一位朋友沟通近况。",
                        "3. 安排十分钟快走。")
                .doesNotContain("1. 1.", "2. 2、", "3. 3)");
    }

    @Test
    void boundsOversizedModelFieldsInsteadOfFailingDeserialization() {
        StructuredContractRepair repair = new StructuredContractRepair(
                "答".repeat(8_100),
                List.of(new StructuredContractRepair.Evidence(
                        "concept-01", "证".repeat(1_700))),
                List.of("行动".repeat(900)),
                "假".repeat(1_700)
        );

        assertThat(repair.answer().codePointCount(
                0, repair.answer().length())).isEqualTo(8_000);
        assertThat(repair.evidence().get(0).content().codePointCount(
                0, repair.evidence().get(0).content().length()))
                .isEqualTo(1_600);
        assertThat(repair.actionItems().get(0).codePointCount(
                0, repair.actionItems().get(0).length()))
                .isEqualTo(1_600);
        assertThat(repair.assumptions().codePointCount(
                0, repair.assumptions().length())).isEqualTo(1_600);
    }

    @Test
    void rejectsEvidenceAttachedToAnUnknownRequirementId() {
        AnswerVerificationContract contract = new AnswerVerificationContract(
                List.of("共同目标|阶段计划"),
                List.of(),
                1,
                1600,
                false,
                false,
                false,
                0
        );
        StructuredContractRepair repair = new StructuredContractRepair(
                "先从一次短沟通开始。",
                List.of(new StructuredContractRepair.Evidence(
                        "concept-99",
                        "共同商定一个月目标，并拆成每周可以检查的阶段计划。")),
                List.of(),
                ""
        );

        DeterministicContractRepairRenderer.RenderResult result =
                DeterministicContractRepairRenderer.render(
                        repair, contract, "");

        assertThat(result.contractPassed()).isFalse();
        assertThat(result.answer()).doesNotContain("共同商定一个月目标");
        assertThat(result.unresolvedRequirementIds())
                .containsExactly("concept-01");
    }

    @Test
    void acceptsVersionedAndLegacyEvidenceJsonKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        StructuredContractRepair versioned = mapper.readValue("""
                {
                  "answer": "完整答案",
                  "evidence": [{
                    "requirement_id": "concept-01",
                    "content": "提供具体建议内容。"
                  }],
                  "action_items": [],
                  "assumptions": ""
                }
                """, StructuredContractRepair.class);
        StructuredContractRepair legacy = mapper.readValue("""
                {
                  "answer": "完整答案",
                  "evidence": [{
                    "requirement": "社交圈",
                    "content": "提供具体建议内容。"
                  }],
                  "actionItems": [],
                  "assumptions": ""
                }
                """, StructuredContractRepair.class);

        assertThat(versioned.evidence().get(0).requirementId())
                .isEqualTo("concept-01");
        assertThat(legacy.evidence().get(0).requirementId())
                .isEqualTo("社交圈");
    }
}
