package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.decision.SystemOneDecisionClient;
import com.fhs.aiagent.decision.MalformedSystemOneResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends all routing judgments in one Jev-compatible request and fails open.
 */
@Component
public class DefaultSystemOneRoutingAdvisor implements SystemOneRoutingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(DefaultSystemOneRoutingAdvisor.class);

    private final SystemOneDecisionClient decisionClient;

    private final boolean enabled;

    private final String mode;

    private final String provider;

    private final double multiAgentThreshold;

    private final double safetyThreshold;

    private final double inputPricePerMillionUsd;

    @Autowired
    public DefaultSystemOneRoutingAdvisor(
            SystemOneDecisionClient decisionClient,
            @Value("${agent.decision.system-one.enabled:false}") boolean enabled,
            @Value("${agent.decision.system-one.mode:SHADOW}") String mode,
            @Value("${agent.decision.system-one.provider:UNSPECIFIED}") String provider,
            @Value("${agent.decision.system-one.multi-agent-threshold:0.75}")
            double multiAgentThreshold,
            @Value("${agent.decision.system-one.safety-threshold:0.5}")
            double safetyThreshold,
            @Value("${agent.decision.system-one.input-price-per-million-usd:0}")
            double inputPricePerMillionUsd) {
        this.decisionClient = java.util.Objects.requireNonNull(decisionClient, "decisionClient");
        this.enabled = enabled;
        this.mode = normalizeMode(mode);
        this.provider = provider == null ? "UNSPECIFIED" : provider.trim().toUpperCase(Locale.ROOT);
        this.multiAgentThreshold = probability(multiAgentThreshold, "multiAgentThreshold");
        this.safetyThreshold = probability(safetyThreshold, "safetyThreshold");
        this.inputPricePerMillionUsd = Math.max(0, inputPricePerMillionUsd);
    }

    public DefaultSystemOneRoutingAdvisor(SystemOneDecisionClient decisionClient,
                                          boolean enabled,
                                          String mode,
                                          String provider,
                                          double multiAgentThreshold) {
        this(decisionClient, enabled, mode, provider, multiAgentThreshold, 0.5, 0);
    }

    @Override
    public RoutingAdvice advise(String question) {
        if (!enabled || !"SHADOW".equals(mode)) {
            return RoutingAdvice.disabled();
        }
        long startedAt = System.nanoTime();
        try {
            SystemOneDecisionClient.SystemOneResult result = decisionClient.evaluate(
                    Map.of("question", question == null ? "" : question),
                    questions()
            );
            Map<AgentDomain, Double> domainProbabilities = new EnumMap<>(AgentDomain.class);
            for (AgentDomain domain : AgentDomain.values()) {
                domainProbabilities.put(domain, noul(
                        result, domain.name().toLowerCase(Locale.ROOT)));
            }
            double multiAgentProbability = noul(result, "should_use_multi_agent");
            double safetyProbability = domainProbabilities.getOrDefault(AgentDomain.SAFETY, 0.0);
            return new RoutingAdvice(
                    mode,
                    "SUCCESS",
                    multiAgentProbability >= multiAgentThreshold,
                    multiAgentProbability,
                    safetyProbability >= safetyThreshold,
                    safetyProbability,
                    domainProbabilities,
                    elapsedMs(startedAt),
                    provider + ":" + result.model(),
                    result.inputTokens(),
                    result.outputTokens(),
                    result.inputTokens() * inputPricePerMillionUsd / 1_000_000.0
            );
        } catch (RuntimeException exception) {
            log.warn("System One shadow routing failed open: {}", exception.getMessage());
            return new RoutingAdvice(
                    mode,
                    exception instanceof MalformedSystemOneResponseException
                            ? "MALFORMED_RESPONSE" : "FAILED",
                    false,
                    0,
                    false,
                    0,
                    Map.of(),
                    elapsedMs(startedAt),
                    provider,
                    0,
                    0,
                    0
            );
        }
    }

    private Map<String, Object> questions() {
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("relationship", noulQuestion(
                "这个请求是否需要亲密关系、伴侣沟通或冲突修复方面的专业分析？"));
        questions.put("parenting", noulQuestion(
                "这个请求是否需要育儿、儿童照护或共同养育方面的专业分析？"));
        questions.put("household", noulQuestion(
                "这个请求是否需要家务分工、家庭日常安排或家庭运营方面的专业分析？"));
        questions.put("finance", noulQuestion(
                "这个请求是否需要个人财务或家庭财务方面的专业分析？"));
        questions.put("safety", noulQuestion(
                "这个请求是否涉及人身安全、虐待、胁迫、自伤或需要紧急处理的风险？"));
        questions.put("should_use_multi_agent", noulQuestion(
                "按 Cortex 的质量、成本和延迟效用目标，这个请求是否只有通过两个或更多专业 Agent "
                        + "分工并综合，才能相对一个能力完整的单 Agent 获得实质性回答质量提升？"
                        + "仅仅提到多个主题、篇幅较长或命中安全风险并不足以判真；安全风险由独立护栏处理。"));
        return Map.copyOf(questions);
    }

    private Map<String, Object> noulQuestion(String instructions) {
        return Map.of(
                "type", "noul",
                "instructions", instructions,
                "criteria", Map.of(
                        "true", "请求明确符合该判断。",
                        "false", "请求不符合或没有足够信息支持该判断。"
                )
        );
    }

    private double noul(SystemOneDecisionClient.SystemOneResult result, String key) {
        if (result == null) {
            throw new MalformedSystemOneResponseException("System One routing response is missing");
        }
        SystemOneDecisionClient.SystemOneAnswer answer = result.answers().get(key);
        if (answer == null || !"noul".equals(answer.type()) || answer.noul() == null
                || !Double.isFinite(answer.noul()) || answer.noul() < 0 || answer.noul() > 1) {
            throw new MalformedSystemOneResponseException(
                    "System One routing response requires a noul probability for " + key);
        }
        return answer.noul();
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "SHADOW" : value.trim().toUpperCase(Locale.ROOT);
        if (!"OFF".equals(normalized) && !"SHADOW".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported System One mode: " + value);
        }
        return normalized;
    }

    private double probability(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
