package com.fhs.aiagent.rag.run;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {

    DurableAgentRun save(DurableAgentRun run);

    Optional<DurableAgentRun> findById(String runId);

    List<DurableAgentRun> findAll();
}
