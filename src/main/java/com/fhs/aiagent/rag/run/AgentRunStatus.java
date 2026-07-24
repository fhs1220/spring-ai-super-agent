package com.fhs.aiagent.rag.run;

public enum AgentRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED
}
