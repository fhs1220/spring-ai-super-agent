package com.fhs.aiagent.rag.multiagent;

import java.util.List;

public record RoutingPolicyDeploymentState(
        RoutingPolicyDeployment current,
        List<RoutingPolicyDeployment> history
) {

    public RoutingPolicyDeploymentState {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
