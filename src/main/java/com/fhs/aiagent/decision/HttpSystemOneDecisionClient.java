package com.fhs.aiagent.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP adapter shared by TypeSafe Jev and a self-hosted Laya server.
 */
@Component
public class HttpSystemOneDecisionClient implements SystemOneDecisionClient {

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private final URI endpoint;

    private final String apiKey;

    private final String model;

    private final Duration requestTimeout;

    @Autowired
    public HttpSystemOneDecisionClient(
            ObjectMapper objectMapper,
            @Value("${agent.decision.system-one.base-url:http://localhost:8000}") String baseUrl,
            @Value("${agent.decision.system-one.endpoint-path:/v1/systemone}") String endpointPath,
            @Value("${agent.decision.system-one.api-key:}") String apiKey,
            @Value("${agent.decision.system-one.model:}") String model,
            @Value("${agent.decision.system-one.connect-timeout-ms:500}") long connectTimeoutMs,
            @Value("${agent.decision.system-one.request-timeout-ms:1200}") long requestTimeoutMs) {
        this(
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(100, connectTimeoutMs)))
                        .build(),
                endpoint(baseUrl, endpointPath),
                apiKey,
                model,
                Duration.ofMillis(Math.max(100, requestTimeoutMs))
        );
    }

    HttpSystemOneDecisionClient(ObjectMapper objectMapper,
                                HttpClient httpClient,
                                URI endpoint,
                                String apiKey,
                                String model,
                                Duration requestTimeout) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.requestTimeout = java.util.Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public SystemOneResult evaluate(Object state, Map<String, ?> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!model.isBlank()) {
            payload.put("model", model);
        }
        payload.put("state", state);
        payload.put("questions", questions);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writePayload(payload)));
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SystemOneDecisionException(
                        "System One endpoint returned HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SystemOneDecisionException("System One request interrupted", exception);
        } catch (IOException exception) {
            throw new SystemOneDecisionException("System One request failed", exception);
        }
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new SystemOneDecisionException("Could not serialize System One request", exception);
        }
    }

    private SystemOneResult parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode answersNode = root.path("answers");
            if (!answersNode.isObject()) {
                throw new SystemOneDecisionException("System One response has no answers object");
            }
            Map<String, SystemOneAnswer> answers = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = answersNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                answers.put(field.getKey(), parseAnswer(field.getValue()));
            }
            JsonNode usage = root.path("usage");
            return new SystemOneResult(
                    root.path("model").asText(model),
                    answers,
                    tokenCount(usage, "input_tokens", "prompt_tokens"),
                    tokenCount(usage, "output_tokens", "completion_tokens")
            );
        } catch (JsonProcessingException exception) {
            throw new SystemOneDecisionException("Could not parse System One response", exception);
        }
    }

    private long tokenCount(JsonNode usage, String primary, String fallback) {
        JsonNode value = usage.get(primary);
        if (value == null || !value.isNumber()) {
            value = usage.get(fallback);
        }
        return value != null && value.isNumber() ? Math.max(0, value.asLong()) : 0;
    }

    private SystemOneAnswer parseAnswer(JsonNode node) {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonNode probabilitiesNode = node.path("probabilities");
        if (probabilitiesNode.isObject()) {
            probabilitiesNode.fields().forEachRemaining(field ->
                    probabilities.put(field.getKey(), field.getValue().asDouble()));
        }
        return new SystemOneAnswer(
                node.path("type").asText(""),
                number(node, "noul"),
                node.path("choice").asText(""),
                number(node, "score"),
                number(node, "confidence"),
                probabilities
        );
    }

    private Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private static URI endpoint(String baseUrl, String endpointPath) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("System One base URL must not be blank");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String path = endpointPath == null ? "" : endpointPath.trim();
        if (path.isBlank()) {
            throw new IllegalArgumentException("System One endpoint path must not be blank");
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(normalized + path);
    }

    public static class SystemOneDecisionException extends RuntimeException {

        public SystemOneDecisionException(String message) {
            super(message);
        }

        public SystemOneDecisionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
