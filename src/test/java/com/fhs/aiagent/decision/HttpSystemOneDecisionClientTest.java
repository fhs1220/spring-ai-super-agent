package com.fhs.aiagent.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpSystemOneDecisionClientTest {

    @Test
    void parsesJevCompatibleTypedAnswers() throws Exception {
        HttpSystemOneDecisionClient client = clientRespondingWith("""
                {
                  "model":"laya-multilingual",
                  "usage":{"input_tokens":123,"output_tokens":4},
                  "answers":{
                    "safety":{"type":"noul","noul":0.91},
                    "domain":{"type":"choice","choice":"parenting",
                      "confidence":0.82,
                      "probabilities":{"parenting":0.84,"relationship":0.16}},
                    "quality":{"type":"score","score":87.5}
                  }
                }
                """, 200);

        SystemOneDecisionClient.SystemOneResult result = client.evaluate(
                Map.of("question", "孩子害怕父母分开"),
                Map.of(
                        "safety", Map.of("type", "noul", "instructions", "Is it risky?"),
                        "domain", Map.of("type", "choice", "instructions", "Choose a domain"),
                        "quality", Map.of("type", "score", "instructions", "Score from 0 to 100"))
        );

        assertThat(result.model()).isEqualTo("laya-multilingual");
        assertThat(result.inputTokens()).isEqualTo(123);
        assertThat(result.outputTokens()).isEqualTo(4);
        assertThat(result.answers().get("safety").noul()).isEqualTo(0.91);
        assertThat(result.answers().get("domain").choice()).isEqualTo("parenting");
        assertThat(result.answers().get("domain").probabilities())
                .containsEntry("parenting", 0.84);
        assertThat(result.answers().get("quality").score()).isEqualTo(87.5);
    }

    @ParameterizedTest
    @MethodSource("malformedNoulResponses")
    void rejectsResponsesThatDoNotSatisfyRequestedNoulContract(String body) throws Exception {
        HttpSystemOneDecisionClient client = clientRespondingWith(body, 200);

        assertThatThrownBy(() -> client.evaluate(
                Map.of("question", "test"), Map.of("safety", Map.of("type", "noul"))))
                .isInstanceOf(MalformedSystemOneResponseException.class);
    }

    static Stream<String> malformedNoulResponses() {
        return Stream.of(
                "not-json", "", "null", "[]", "{}", "{\"answers\":{}}",
                "{\"answers\":{\"other\":{\"type\":\"noul\",\"noul\":0.9}}}",
                "{\"answers\":{\"safety\":null}}",
                "{\"answers\":{\"safety\":0.9}}",
                "{\"answers\":{\"safety\":{\"noul\":0.9}}}",
                "{\"answers\":{\"safety\":{\"type\":\"score\",\"noul\":0.9}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\"}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":null}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":\"0.9\"}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":-0.1}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":1.1}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":1e400}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":\"NaN\"}}}",
                "{\"answers\":{\"safety\":{\"type\":\"noul\",\"noul\":0.9},"
                        + "\"extra\":{\"type\":\"noul\",\"noul\":0.2}}}"
        );
    }

    @Test
    void acceptsBoundaryProbabilitiesAndChoiceWithoutOptionalConfidence() throws Exception {
        HttpSystemOneDecisionClient client = clientRespondingWith("""
                {"answers":{
                  "no":{"type":"noul","noul":0},
                  "yes":{"type":"noul","noul":1},
                  "domain":{"type":"choice","choice":"parenting"}
                }}
                """, 200);

        SystemOneDecisionClient.SystemOneResult result = client.evaluate(Map.of(), Map.of(
                "no", Map.of("type", "noul"), "yes", Map.of("type", "noul"),
                "domain", Map.of("type", "choice")));

        assertThat(result.answers().get("no").noul()).isZero();
        assertThat(result.answers().get("yes").noul()).isEqualTo(1);
        assertThat(result.answers().get("domain").choice()).isEqualTo("parenting");
    }

    @Test
    void rejectsMalformedChoiceAndScoreValues() throws Exception {
        for (String answer : new String[]{
                "{\"type\":\"choice\",\"choice\":9}",
                "{\"type\":\"choice\",\"choice\":\"parenting\",\"confidence\":1.2}",
                "{\"type\":\"choice\",\"choice\":\"parenting\",\"probabilities\":{\"parenting\":\"bad\"}}",
                "{\"type\":\"score\",\"score\":\"87\"}",
                "{\"type\":\"score\",\"score\":1e400}",
                "{\"type\":\"score\"}"
        }) {
            String type = new ObjectMapper().readTree(answer).path("type").asText();
            HttpSystemOneDecisionClient client = clientRespondingWith(
                    "{\"answers\":{\"result\":" + answer + "}}", 200);
            assertThatThrownBy(() -> client.evaluate(Map.of(),
                    Map.of("result", Map.of("type", type))))
                    .as(answer)
                    .isInstanceOf(MalformedSystemOneResponseException.class);
        }
    }

    @Test
    void keepsHttpFailuresDistinctFromMalformedResponses() throws Exception {
        HttpSystemOneDecisionClient client = clientRespondingWith("{}", 429);

        assertThatThrownBy(() -> client.evaluate(
                Map.of(), Map.of("safety", Map.of("type", "noul"))))
                .isInstanceOf(HttpSystemOneDecisionClient.SystemOneDecisionException.class)
                .isNotInstanceOf(MalformedSystemOneResponseException.class);
    }

    @SuppressWarnings("unchecked")
    private HttpSystemOneDecisionClient clientRespondingWith(String body, int status) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return new HttpSystemOneDecisionClient(new ObjectMapper(), httpClient,
                URI.create("http://localhost:8000/v1/systemone"), "", "jev-latest",
                Duration.ofSeconds(1));
    }
}
