package com.fhs.aiagent.controller;

import com.fhs.aiagent.rl.bailian.AgentRlRetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 百炼云端 Rollout 使用的只读检索环境。默认关闭，并要求独立令牌。
 */
@RestController
@RequestMapping("/agent-rl/environment")
@ConditionalOnProperty(name = "agent.rl.environment.api-enabled", havingValue = "true")
public class AgentRlEnvironmentController {

    private final AgentRlRetrievalService retrievalService;

    private final String expectedToken;

    public AgentRlEnvironmentController(
            AgentRlRetrievalService retrievalService,
            @Value("${agent.rl.environment.token:}") String expectedToken) {
        this.retrievalService = retrievalService;
        this.expectedToken = expectedToken;
    }

    @PostMapping("/retrieve")
    public AgentRlRetrievalService.RetrievalResult retrieve(
            @RequestHeader(name = "X-Agent-RL-Token", required = false) String suppliedToken,
            @RequestBody RetrievalRequest request) {
        authorize(suppliedToken);
        return retrievalService.retrieve(
                request.query(),
                request.topK() == null ? 4 : request.topK(),
                request.similarityThreshold() == null ? 0.3 : request.similarityThreshold()
        );
    }

    private void authorize(String suppliedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Agent RL environment token is not configured");
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Agent RL token");
        }
    }

    public record RetrievalRequest(
            String query,
            Integer topK,
            Double similarityThreshold
    ) {
    }
}
