package com.fhs.aiagent.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpSystemOneDecisionClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesJevCompatibleTypedAnswers() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "model":"laya-multilingual",
                  "usage":{"input_tokens":123,"output_tokens":4},
                  "answers":{
                    "safety":{"type":"noul","noul":0.91},
                    "domain":{"type":"choice","choice":"parenting",
                      "confidence":0.82,
                      "probabilities":{"parenting":0.84,"relationship":0.16}}
                  }
                }
                """);
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        HttpSystemOneDecisionClient client = new HttpSystemOneDecisionClient(
                new ObjectMapper(),
                httpClient,
                URI.create("http://localhost:8000/v1/systemone"),
                "",
                "jev-latest",
                Duration.ofSeconds(1)
        );

        SystemOneDecisionClient.SystemOneResult result = client.evaluate(
                Map.of("question", "孩子害怕父母分开"),
                Map.of("safety", Map.of("type", "noul", "instructions", "Is it risky?"))
        );

        assertThat(result.model()).isEqualTo("laya-multilingual");
        assertThat(result.inputTokens()).isEqualTo(123);
        assertThat(result.outputTokens()).isEqualTo(4);
        assertThat(result.answers().get("safety").noul()).isEqualTo(0.91);
        assertThat(result.answers().get("domain").choice()).isEqualTo("parenting");
        assertThat(result.answers().get("domain").probabilities())
                .containsEntry("parenting", 0.84);
    }
}
