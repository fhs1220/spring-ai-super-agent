package com.fhs.aiagent.rag.multiagent;

import java.util.List;

/**
 * Agent 能力契约。字段保持显式和可序列化，便于未来映射为 A2A Agent Card。
 */
public record AgentDescriptor(
        String id,
        String name,
        String description,
        List<String> skills,
        String inputSchemaVersion,
        String outputSchemaVersion
) {
}
