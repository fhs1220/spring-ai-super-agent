package com.fhs.aiagent.rag;

@FunctionalInterface
public interface AgentProgressListener {

    AgentProgressListener NONE = event -> {
    };

    void onProgress(AgentProgressEvent event);
}
