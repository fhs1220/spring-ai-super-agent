package com.fhs.aiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerTaskFormatTest {
    private static final String FOUR = "我们因为孩子、家务和花钱方式吵架。请把我的诉求整理成四句不指责的表达，不要制定方案。";
    private static final String WEEKS = "请制定未来六周的现金流、育儿和沟通联合计划，并标出每周检查点。";
    private static final String BURDEN = "我们备考并照护婴儿，每周两次帮手，请保证双方负担可比较。";
    private static final String FOUR_ANSWER = "1. 我希望我们平静聊聊孩子的照护。\n2. 我最近因家务感到疲惫。\n3. 我对花钱标准不同有些不安。\n4. 我想听听你的感受。[来源 1]";

    static String weeklyAnswer(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(n -> "## 第" + n
                + "周：核对现金流并安排育儿和沟通。\n检查点：确认账单覆盖和照护人员已安排。\n").reduce("", String::concat);
    }

    @Test
    void fourSentencesAreNotInflatedToTheDefaultMinimumLength() {
        var contract = AnswerVerificationContract.inferred(FOUR);
        assertThat(contract.taskFormat().exactSentences()).isEqualTo(4);
        assertThat(contract.minimumAnswerChars()).isEqualTo(1);
        assertThat(contract.check(FOUR_ANSWER, "[来源 1 | 沟通]\n倾听感受").passed()).isTrue();
        assertThat(contract.promptChecklist()).contains("只输出 4 句", "不添加开场介绍");
    }

    @Test
    void countsPunctuationNotListNumbersAndRejectsPrefacesAndDanglingText() {
        var format = AnswerTaskFormat.inferred(FOUR);
        assertThat(format.missingRequirements(FOUR_ANSWER)).isEmpty();
        for (String bad : List.of("我是咨询助手。\n" + FOUR_ANSWER,
                "下面给你四句：" + FOUR_ANSWER, FOUR_ANSWER + "\n如果你愿意，我可以继续。",
                FOUR_ANSWER + "尾部解释", FOUR_ANSWER.replace("4. 我想听听你的感受。[来源 1]", ""),
                FOUR_ANSWER.replace("4. 我想听听你的感受。", "4. ！！！"))) {
            assertThat(format.missingRequirements(bad)).as(bad).isNotEmpty();
        }
        assertThat(AnswerTaskFormat.inferred("请用四句话回答。").exactSentences()).isEqualTo(4);
        assertThat(AnswerTaskFormat.inferred("我们吵架时说了四句话，你怎么看？").exactSentences()).isNull();
        assertThat(AnswerTaskFormat.inferred("不要用四句话回答。").exactSentences()).isNull();
        assertThat(AnswerVerificationContract.inferred("将这三个诉求整理成四句表达。")
                .minimumActionItems()).isZero();
    }

    @Test
    void eachWeekNeedsItsOwnActionsAndCheckpointInOrder() {
        var format = AnswerTaskFormat.inferred(WEEKS);
        String full = weeklyAnswer(6);
        assertThat(format.missingRequirements(full)).isEmpty();
        assertThat(format.missingRequirements(full.replace("第3周", "第3-4周"))).isNotEmpty();
        assertThat(format.missingRequirements(full.replace("第3周", "第2周"))).isNotEmpty();
        assertThat(format.missingRequirements(weeklyAnswer(5))).isNotEmpty();
        assertThat(format.missingRequirements(weeklyAnswer(7))).isNotEmpty();
        assertThat(format.missingRequirements(full.replace("检查点：", "相关内容："))).hasSize(6);
        assertThat(format.missingRequirements(IntStream.rangeClosed(1, 6)
                .mapToObj(n -> "第" + n + "周：\n检查点：\n").reduce("", String::concat))).hasSize(6);
        assertThat(AnswerTaskFormat.inferred("下周要做什么？").weeklyCheckpoints()).isNull();
        assertThat(AnswerTaskFormat.inferred("未来六周保持沟通。").weeklyCheckpoints()).isNull();
        assertThat(AnswerTaskFormat.inferred("未来101周计划及每周检查点。").weeklyCheckpoints()).isNull();
    }

    @Test
    void workloadNeedsComparableRowsNotJustTheWordFairness() {
        var format = AnswerTaskFormat.inferred(BURDEN);
        String table = """
                假设以下为本周待记录的数据，每周仅两次帮手，时间待协商。
                | 指标 | A | B |
                | --- | --- | --- |
                | 学习小时/周 | 待填 | 待填 |
                | 夜醒次数/周 | 待填 | 待填 |
                | 家务照护小时/周 | 待填 | 待填 |
                若一方本周总负担比另一方多2小时，周会调整下一周家务分工。
                """;
        assertThat(format.missingRequirements(table)).isEmpty();
        assertThat(format.missingRequirements("双方公平轮换照护，尽量给双方学习时间。")).isNotEmpty();
        assertThat(format.missingRequirements(table.replace("| B |", "| A |"))).isNotEmpty();
        assertThat(format.missingRequirements(table.replace("| 待填 | 待填 |", "| 待填 |  |"))).isNotEmpty();
        assertThat(format.missingRequirements(table.replace("若一方", "一方").replace("调整", "安排"))).isNotEmpty();
        assertThat(format.missingRequirements("| 指标 | A | B |\n| 学习夜间家务小时/周 | 1 | 1 |\n若不同则调整。")).isNotEmpty();
    }

    @Test
    void newRequirementsHaveStableRepairIdsAndRoundTripAlongsideOldJson() throws Exception {
        var mapper = new ObjectMapper();
        var old = mapper.readValue("{\"minimum_answer_chars\":1,\"maximum_answer_chars\":1600}", AnswerVerificationContract.class);
        assertThat(old.taskFormat().requirements()).isEmpty();
        var contract = old.mergeInferred(WEEKS);
        var check = contract.check("没有计划", "");
        assertThat(contract.repairRequirementIds(check.missingRequirements()))
                .contains("format-week-order", "format-week-1", "format-week-6");
        assertThat(contract.repairPromptChecklist(check.missingRequirements())).contains("format-week-6");
        assertThat(mapper.readValue(mapper.writeValueAsString(contract), AnswerVerificationContract.class)).isEqualTo(contract);
    }

    @Test
    void boundsNewFieldsAndRejectsConflictingExplicitCounts() {
        assertThatThrownBy(() -> new AnswerTaskFormat(11, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerTaskFormat(null, 13, false)).isInstanceOf(IllegalArgumentException.class);
        var contract = new AnswerVerificationContract(List.of(), List.of(), 1, 1000, false, false, false, 0,
                new AnswerTaskFormat(3, null, false));
        assertThatThrownBy(() -> contract.mergeInferred(FOUR)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectorDoesNotTradeAwayWeeksForAnUnderLengthRevision() {
        String full = weeklyAnswer(6);
        String tooLong = full + "补充解释".repeat(450);
        String missingWeeks = weeklyAnswer(2);
        var selected = AgenticRagService.selectHigherPrecisionCandidate(WEEKS, "", tooLong, missingWeeks);
        assertThat(selected.selectedCandidate()).isEqualTo("DRAFT");
        // Retention is not success: the overlong candidate still fails the contract.
        assertThat(AnswerVerificationContract.inferred(WEEKS).check(selected.answer(), "").passed()).isFalse();
        assertThat(AgenticRagService.selectHigherPrecisionCandidate(WEEKS, "", tooLong, full).selectedCandidate()).isEqualTo("REVISED");
    }

    @Test
    void selectorFavorsValidFourSentencesOverVerboseIntroduction() {
        var selected = AgenticRagService.selectHigherPrecisionCandidate(FOUR, "", "我是咨询助手。" + FOUR_ANSWER, FOUR_ANSWER);
        assertThat(selected.selectedCandidate()).isEqualTo("REVISED");
    }
}
