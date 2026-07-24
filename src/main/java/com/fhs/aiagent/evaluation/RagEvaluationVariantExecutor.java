package com.fhs.aiagent.evaluation;

@FunctionalInterface
public interface RagEvaluationVariantExecutor {

    RagVariantExecution execute(RagEvaluationVariant variant,
                                RagEvaluationCase evaluationCase,
                                String chatId);
}
