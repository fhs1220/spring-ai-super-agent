package com.fhs.aiagent.rag.multiagent;

/**
 * Agent 路由执行方式。强制模式仅供隔离评测使用，线上请求应使用 ADAPTIVE。
 */
public enum MultiAgentRoutingMode {
    ADAPTIVE,
    FORCE_SINGLE,
    FORCE_MULTI
}
