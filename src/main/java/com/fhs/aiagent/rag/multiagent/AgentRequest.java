package com.fhs.aiagent.rag.multiagent;

/**
 * 专业 Agent 的强类型输入，禁止通过隐式共享聊天记录传递状态。
 */
public record AgentRequest(
        String question,
        String conversationHistory,
        String evidenceContext,
        String systemPrompt
) {
}
