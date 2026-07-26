package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.app.LoveApp;
import com.fhs.aiagent.rag.multiagent.MultiAgentRoutingMode;
import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import org.springframework.stereotype.Component;

@Component
public class LoveAppEvaluationVariantExecutor implements RagEvaluationVariantExecutor {

    private final LoveApp loveApp;

    public LoveAppEvaluationVariantExecutor(LoveApp loveApp) {
        this.loveApp = loveApp;
    }

    @Override
    public RagVariantExecution execute(RagEvaluationVariant variant,
                                       RagEvaluationCase evaluationCase,
                                       String chatId) {
        long startedAt = System.nanoTime();
        if (variant == RagEvaluationVariant.TRADITIONAL_RAG) {
            String answer = loveApp.doChatWithTraditionalRag(evaluationCase.question(), chatId);
            return new RagVariantExecution(
                    variant,
                    answer,
                    elapsedMs(startedAt),
                    0,
                    0,
                    false,
                    "",
                    ""
            );
        }

        MultiAgentRoutingMode routingMode = switch (variant) {
            case AGENTIC_SINGLE_AGENT -> MultiAgentRoutingMode.FORCE_SINGLE;
            case AGENTIC_MULTI_AGENT -> MultiAgentRoutingMode.FORCE_MULTI;
            case AGENTIC_RAG_V5 -> MultiAgentRoutingMode.ADAPTIVE;
            case TRADITIONAL_RAG -> throw new IllegalStateException(
                    "traditional RAG must use the baseline execution path");
        };
        AgenticRagResult result = loveApp.doChatWithAgenticRagEvaluation(
                evaluationCase.question(), chatId, routingMode);
        AgentRunMetrics telemetry = result.trace() == null ? null : result.trace().telemetry();
        return new RagVariantExecution(
                variant,
                result.answer(),
                elapsedMs(startedAt),
                telemetry == null ? 0 : telemetry.totalTokens(),
                telemetry == null ? 0 : telemetry.estimatedCostCny(),
                telemetry != null,
                result.trace() == null ? "" : result.trace().executionMode(),
                ""
        );
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
