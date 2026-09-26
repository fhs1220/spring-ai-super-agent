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
        Map<String, String> requestedTypes = requestedTypes(questions);
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
            return parseResponse(response.body(), requestedTypes);
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

    private Map<String, String> requestedTypes(Map<String, ?> questions) {
        Map<String, String> types = new LinkedHashMap<>();
        questions.forEach((key, question) -> {
            JsonNode definition = objectMapper.valueToTree(question);
            JsonNode type = definition == null ? null : definition.get("type");
            if (key == null || key.isBlank() || type == null
                    || !type.isTextual() || type.textValue().isBlank()) {
                throw new IllegalArgumentException("Each System One question must have a key and type");
            }
            types.put(key, type.textValue());
        });
        return types;
    }

    private SystemOneResult parseResponse(String body, Map<String, String> requestedTypes) {
        try {
            JsonNode root = body == null ? null : objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw malformed("System One response must be an object");
            }
            JsonNode answersNode = root.path("answers");
            if (!answersNode.isObject()) {
                throw malformed("System One response has no answers object");
            }
            if (answersNode.size() != requestedTypes.size()) {
                throw malformed("System One response answer keys do not match the request");
            }
            Map<String, SystemOneAnswer> answers = new LinkedHashMap<>();
            for (Map.Entry<String, String> requested : requestedTypes.entrySet()) {
                answers.put(requested.getKey(), parseAnswer(
                        answersNode.get(requested.getKey()), requested.getKey(), requested.getValue()));
            }
            JsonNode usage = root.path("usage");
            return new SystemOneResult(
                    root.path("model").asText(model),
                    answers,
                    tokenCount(usage, "input_tokens", "prompt_tokens"),
                    tokenCount(usage, "output_tokens", "completion_tokens")
            );
        } catch (JsonProcessingException exception) {
            throw new MalformedSystemOneResponseException(
                    "Could not parse System One response", exception);
        }
    }

    private long tokenCount(JsonNode usage, String primary, String fallback) {
        JsonNode value = usage.get(primary);
        if (value == null || !value.isNumber()) {
            value = usage.get(fallback);
        }
        return value != null && value.isNumber() ? Math.max(0, value.asLong()) : 0;
    }

    private SystemOneAnswer parseAnswer(JsonNode node, String key, String expectedType) {
        if (node == null || !node.isObject()) {
            throw malformed("System One response is missing an answer object for " + key);
        }
        JsonNode type = node.get("type");
        if (type == null || !type.isTextual() || !expectedType.equals(type.textValue())) {
            throw malformed("System One answer type does not match the request for " + key);
        }
        Double noul = number(node, "noul", key);
        Double score = number(node, "score", key);
        Double confidence = number(node, "confidence", key);
        if ("noul".equals(expectedType)) {
            requireProbability(noul, key + ".noul");
        } else if ("score".equals(expectedType) && score == null) {
            throw malformed("System One answer requires a finite score for " + key);
        }
        JsonNode choice = node.get("choice");
        if ("choice".equals(expectedType)
                && (choice == null || !choice.isTextual() || choice.textValue().isBlank())) {
            throw malformed("System One answer requires a textual choice for " + key);
        }
        if (confidence != null) {
            requireProbability(confidence, key + ".confidence");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonNode probabilitiesNode = node.get("probabilities");
        if (probabilitiesNode != null && !probabilitiesNode.isNull()) {
            if (!probabilitiesNode.isObject()) {
                throw malformed("System One probabilities must be an object for " + key);
            }
            probabilitiesNode.fields().forEachRemaining(field -> {
                Double value = number(probabilitiesNode, field.getKey(), key + ".probabilities");
                requireProbability(value, key + ".probabilities." + field.getKey());
                probabilities.put(field.getKey(), value);
            });
        }
        return new SystemOneAnswer(
                expectedType,
                noul,
                choice == null ? "" : choice.asText(""),
                score,
                confidence,
                probabilities
        );
    }

    private Double number(JsonNode node, String field, String key) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw malformed("System One answer requires a finite number for " + key + "." + field);
        }
        return value.asDouble();
    }

    private void requireProbability(Double value, String field) {
        if (value == null || value < 0 || value > 1) {
            throw malformed("System One answer requires a probability between 0 and 1 for " + field);
        }
    }

    private MalformedSystemOneResponseException malformed(String message) {
        return new MalformedSystemOneResponseException(message);
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
