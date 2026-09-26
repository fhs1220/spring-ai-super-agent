package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.decision.SystemOneDecisionClient;
import com.fhs.aiagent.decision.MalformedSystemOneResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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

    @ParameterizedTest
    @ValueSource(strings = {"relationship", "parenting", "household", "finance",
            "safety", "should_use_multi_agent"})
    void marksNonHttpResponsesMissingAnyRoutingAnswerAsMalformed(String missingKey) {
        SystemOneDecisionClient client = (state, questions) -> {
            Map<String, SystemOneDecisionClient.SystemOneAnswer> answers = completeAnswers();
            answers.remove(missingKey);
            return new SystemOneDecisionClient.SystemOneResult("mock-provider", answers);
        };

        assertMalformedFallback(client);
    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    void rejectsInvalidTypeOrProbabilityFromNonHttpProviders(
            SystemOneDecisionClient.SystemOneAnswer invalidAnswer) {
        SystemOneDecisionClient client = (state, questions) -> {
            Map<String, SystemOneDecisionClient.SystemOneAnswer> answers = completeAnswers();
            answers.put("safety", invalidAnswer);
            return new SystemOneDecisionClient.SystemOneResult("mock-provider", answers);
        };

        assertMalformedFallback(client);
    }

    static Stream<SystemOneDecisionClient.SystemOneAnswer> invalidAnswers() {
        return Stream.of(noul(Double.NaN), noul(Double.POSITIVE_INFINITY),
                noul(Double.NEGATIVE_INFINITY), noul(-0.1), noul(1.1),
                new SystemOneDecisionClient.SystemOneAnswer("noul", null, "", null, null, Map.of()),
                new SystemOneDecisionClient.SystemOneAnswer("score", 0.8, "", null, null, Map.of()));
    }

    @Test
    void preservesMalformedStatusFromHttpClientAndNullNonHttpResult() {
        assertMalformedFallback((state, questions) -> {
            throw new MalformedSystemOneResponseException("Missing safety answer");
        });
        assertMalformedFallback((state, questions) -> null);
    }

    private void assertMalformedFallback(SystemOneDecisionClient client) {
        DefaultSystemOneRoutingAdvisor advisor = new DefaultSystemOneRoutingAdvisor(
                client, true, "SHADOW", "JEV", 0.65);

        SystemOneRoutingAdvisor.RoutingAdvice advice = advisor.advise("test");

        assertThat(advice.status()).isEqualTo("MALFORMED_RESPONSE");
        assertThat(advice.recommendedMultiAgent()).isFalse();
        assertThat(advice.recommendedSafetyGuard()).isFalse();
        assertThat(advice.domainProbabilities()).isEmpty();
    }

    private Map<String, SystemOneDecisionClient.SystemOneAnswer> completeAnswers() {
        Map<String, SystemOneDecisionClient.SystemOneAnswer> answers = new LinkedHashMap<>();
        for (String key : new String[]{"relationship", "parenting", "household", "finance",
                "safety", "should_use_multi_agent"}) {
            answers.put(key, noul(0.8));
        }
        return answers;
    }

    private static SystemOneDecisionClient.SystemOneAnswer noul(double probability) {
        return new SystemOneDecisionClient.SystemOneAnswer(
                "noul", probability, "", null, null, Map.of());
    }
}
