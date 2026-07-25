package com.fhs.aiagent.rag.multiagent;

import java.util.Optional;

public interface RoutingPolicyDeploymentRepository {

    Optional<RoutingPolicyDeploymentState> load();

    RoutingPolicyDeploymentState save(RoutingPolicyDeploymentState state);
}
