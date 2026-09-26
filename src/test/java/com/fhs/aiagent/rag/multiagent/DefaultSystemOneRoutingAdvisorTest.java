package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.decision.SystemOneDecisionClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSystemOneRoutingAdvisorTest {

    @Test
    void asksAllRoutingQuestionsInOneCallAndReturnsProbabilities() {
        AtomicReference<Map<String, ?>> capturedQuestions = new AtomicReference<>();
        SystemOneDecisionClient client = (state, questions) -> {
            capturedQuestions.set(questions);
            Map<String, SystemOneDecisionClient.SystemOneAnswer> answers = new LinkedHashMap<>();
            answers.put("relationship", noul(0.72));
            answers.put("parenting", noul(0.88));
            answers.put("household", noul(0.31));
            answers.put("finance", noul(0.10));
            answers.put("safety", noul(0.67));
            answers.put("should_use_multi_agent", noul(0.81));
            return new SystemOneDecisionClient.SystemOneResult("laya-multilingual", answers);
        };
        DefaultSystemOneRoutingAdvisor advisor = new DefaultSystemOneRoutingAdvisor(
                client, true, "shadow", "laya", 0.65);

        SystemOneRoutingAdvisor.RoutingAdvice advice = advisor.advise(
                "孩子担心父母分开，需要我们共同处理。"
        );

        assertThat(capturedQuestions.get()).containsOnlyKeys(
                "relationship", "parenting", "household", "finance", "safety",
                "should_use_multi_agent");
        assertThat(advice.status()).isEqualTo("SUCCESS");
        assertThat(advice.recommendedMultiAgent()).isTrue();
        assertThat(advice.multiAgentProbability()).isEqualTo(0.81);
        assertThat(advice.recommendedSafetyGuard()).isTrue();
        assertThat(advice.safetyProbability()).isEqualTo(0.67);
        assertThat(advice.domainProbabilities())
                .containsEntry(AgentDomain.PARENTING, 0.88)
                .containsEntry(AgentDomain.SAFETY, 0.67);
        assertThat(advice.model()).isEqualTo("LAYA:laya-multilingual");
    }

    @Test
    void failsOpenWithoutLeakingTheExceptionIntoRouting() {
        SystemOneDecisionClient client = (state, questions) -> {
            throw new IllegalStateException("endpoint unavailable");
        };
        DefaultSystemOneRoutingAdvisor advisor = new DefaultSystemOneRoutingAdvisor(
                client, true, "SHADOW", "JEV", 0.65);

        SystemOneRoutingAdvisor.RoutingAdvice advice = advisor.advise("普通问题");

        assertThat(advice.status()).isEqualTo("FAILED");
        assertThat(advice.recommendedMultiAgent()).isFalse();
        assertThat(advice.recommendedSafetyGuard()).isFalse();
        assertThat(advice.domainProbabilities()).isEmpty();
    }

    private static SystemOneDecisionClient.SystemOneAnswer noul(double probability) {
        return new SystemOneDecisionClient.SystemOneAnswer(
                "noul", probability, "", null, null, Map.of());
    }
}
