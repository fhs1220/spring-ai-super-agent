package com.fhs.aiagent.rag;

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
                                "用心",
                                "提前了解偏好并记录对方真正重视的小事。"),
                        new StructuredContractRepair.Evidence(
                                "边界|舒适",
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
                        "社交圈", "社交圈")),
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
}
