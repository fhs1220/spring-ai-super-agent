package com.fhs.aiagent.rag;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnswerContractV6Test {
    private static final String TABLE = """
            | 指标 | 我 | 伴侣 |
            | --- | --- | --- |
            | 学习小时/周 | 待填（用于备考） | 2小时/周（同口径） |
            | 夜间次数/周 | 3（含起夜） | 待填（同口径） |
            | 家务照护小时/周 | 待填（含喂养、哄睡） | 4 |
            若连续两周差异超过约定上限，调整下一周分工。
            """;

    @Test void boilerplateIsCheckedWithStableRepairIdAndDoesNotBanDialogueConsent() {
        var c = AnswerVerificationContract.inferred("请给我道歉开场白。");
        var missing = c.check("当然。我是专注恋爱心理的咨询助手。" + "我想认真向你道歉。".repeat(20), "").missingRequirements();
        assertThat(c.repairRequirementIds(missing)).contains("no-assistant-boilerplate");
        assertThat(c.promptChecklist()).contains("删除无关的助手身份介绍");
        assertThat(c.taskFormat().missingRequirements("如果你愿意，我也可以继续帮你写一版。")).isNotEmpty();
        assertThat(c.taskFormat().missingRequirements("如果你愿意，下一条我可以直接帮你写一段求助短信。"))
                .isNotEmpty();
        assertThat(c.taskFormat().missingRequirements("“如果你愿意，我想听听你的感受。”")).isEmpty();
        assertThat(AnswerVerificationContract.inferred("你是谁？介绍一下你自己。")
                .taskFormat().missingRequirements("我是关系沟通助手。")).isEmpty();
    }

    @Test void annotatedCellsAndExplicitUnitsRemainComparable() {
        assertThat(WorkloadComparisonCheck.passed(TABLE)).isTrue();
        assertThat(WorkloadComparisonCheck.passed(TABLE.replace("夜间次数", "夜间照护次数"))).isTrue();
        assertThat(WorkloadComparisonCheck.passed(TABLE.replace("/周", "")
                .replace("| 学习小时", "| 统计周期 | 每周 | 每周 |\n| 学习小时"))).isTrue();
    }

    @Test void conflictingUnitsPeriodsAndAmbiguousMeasurementsFailClosed() {
        for (String bad : new String[]{TABLE.replace("2小时/周", "2分钟/周"), TABLE.replace("2小时/周", "2小时/天"),
                TABLE.replace("3（含起夜）", "3次（或3小时）"), TABLE.replace("4 |", "-4 |"),
                TABLE.replace("4 |", " |"), TABLE.replace("4 |", "待填或不记录 |"),
                TABLE.replace("/周", ""), TABLE.replace("夜间次数/周", "夜间负担/周"),
                TABLE.replace("若连续", "| 夜间次数/周 | 1小时 | 2次 |\n若连续")}) {
            assertThat(WorkloadComparisonCheck.passed(bad)).as(bad).isFalse();
        }
    }

    @Test void placeholdersAreNotEvidenceThatNumericalFairnessHasBeenEstablished() {
        // Unknown values can form a template; the checker does not claim numerical equality.
        assertThat(WorkloadComparisonCheck.passed(TABLE.replace("2小时/周（同口径）", "待填")
                .replace("3（含起夜）", "待填").replace("4 |", "待填 |"))).isTrue();
    }
}
