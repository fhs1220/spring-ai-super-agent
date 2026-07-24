package com.fhs.aiagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagAnswerScorerTest {

    private final RagAnswerScorer scorer = new RagAnswerScorer();

    @Test
    void scoresConceptsDirectnessCitationAndRouteDeterministically() {
        RagEvaluationCase evaluationCase = new RagEvaluationCase(
                "case-1",
                "问题",
                List.of("test"),
                List.of("沟通|交流", "复盘"),
                List.of("保证收益"),
                20,
                300,
                true,
                "ADAPTIVE_MULTI_AGENT"
        );
        RagVariantExecution execution = new RagVariantExecution(
                RagEvaluationVariant.AGENTIC_RAG_V4,
                "双方先沟通并认真倾听，约定每周一起复盘。[来源 1]",
                100,
                50,
                0.001,
                true,
                "ADAPTIVE_MULTI_AGENT",
                ""
        );

        RagAnswerScorer.Score score = scorer.score(evaluationCase, execution);

        assertThat(score.total()).isEqualTo(1);
        assertThat(score.conceptCoverage()).isEqualTo(1);
        assertThat(score.citationQuality()).isEqualTo(1);
        assertThat(score.routeCorrect()).isTrue();
    }

    @Test
    void rejectsLegacyCitationStyleAndUnnecessaryFollowUp() {
        RagEvaluationCase evaluationCase = new RagEvaluationCase(
                "case-2",
                "问题",
                List.of(),
                List.of("家务"),
                List.of(),
                0,
                0,
                true,
                "SINGLE_AGENT"
        );
        RagVariantExecution execution = new RagVariantExecution(
                RagEvaluationVariant.AGENTIC_RAG_V4,
                "请你详细描述家务问题（参考来源3）。",
                100,
                0,
                0,
                false,
                "SINGLE_AGENT",
                ""
        );

        RagAnswerScorer.Score score = scorer.score(evaluationCase, execution);

        assertThat(score.directness()).isZero();
        assertThat(score.citationQuality()).isZero();
        assertThat(score.total()).isLessThan(0.8);
    }
}
