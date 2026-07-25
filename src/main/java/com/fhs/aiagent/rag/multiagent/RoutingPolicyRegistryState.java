package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;
import java.util.List;

public record RoutingPolicyRegistryState(
        List<RoutingPolicyArtifact> artifacts,
        Instant updatedAt
) {

    public RoutingPolicyRegistryState {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
