package com.fhs.aiagent.rag;

import java.time.Instant;
import java.util.List;

/**
 * Agentic RAG 执行期间向客户端发送的轻量进度事件。
 */
public record AgentProgressEvent(
        String phase,
        String status,
        String title,
        String summary,
        List<String> details,
        long elapsedMs,
        Instant timestamp
) {

    public AgentProgressEvent {
        details = details == null ? List.of() : List.copyOf(details);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
