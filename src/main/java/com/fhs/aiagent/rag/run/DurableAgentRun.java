package com.fhs.aiagent.rag.run;

import com.fhs.aiagent.rag.AgentProgressEvent;
import com.fhs.aiagent.rl.model.AgenticRagResult;

import java.time.Instant;
import java.util.List;

/**
 * 可跨进程恢复的 Agent 运行快照。请求、进度和最终结果写入本地原子文件。
 */
public record DurableAgentRun(
        String runId,
        String message,
        String chatId,
        AgentRunStatus status,
        int attempt,
        List<AgentProgressEvent> events,
        AgenticRagResult result,
        String error,
        Instant createdAt,
        Instant updatedAt
) {

    public DurableAgentRun {
        events = events == null ? List.of() : List.copyOf(events);
        error = error == null ? "" : error;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean terminal() {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED;
    }
}
