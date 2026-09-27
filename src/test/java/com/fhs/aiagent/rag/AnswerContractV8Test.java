package com.fhs.aiagent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerContractV8Test {

    @Test
    void oneSentenceRequestDoesNotRequireAParagraph() {
        var contract = AnswerVerificationContract.inferred(
                "昨晚我打断伴侣说话了。请帮我写一句不要求对方立刻原谅我的道歉开头。");

        assertThat(contract.minimumAnswerChars()).isEqualTo(1);
        assertThat(contract.taskFormat().exactSentences()).isEqualTo(1);
        assertThat(contract.check("昨晚打断你说话是我的错，我想认真道歉，也尊重你现在不想回应。", "")
                .missingRequirements()).isEmpty();
    }

    @Test
    void shortMixedTaskKeepsTheRequestedSentenceCount() {
        var contract = AnswerVerificationContract.inferred(
                "孩子、家务和存钱容易跑题，请把诉求压缩成三句中性表达。");

        assertThat(contract.minimumAnswerChars()).isEqualTo(1);
        assertThat(contract.taskFormat().exactSentences()).isEqualTo(3);
    }

    @Test
    void briefItemsAndDetailedPlansHaveDifferentMinimums() {
        assertThat(AnswerVerificationContract.inferred("请给一条简单的提醒办法。")
                .minimumAnswerChars()).isEqualTo(1);
        assertThat(AnswerVerificationContract.inferred("请设计六周家庭预算和育儿联合计划。")
                .minimumAnswerChars()).isEqualTo(140);
    }
}
