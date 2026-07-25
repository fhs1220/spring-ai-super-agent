package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;

public record RoutingPolicyDeployment(
        String version,
        RoutingPolicyMode mode,
        double canaryRate,
        Instant createdAt,
        String reason
) {

    public RoutingPolicyDeployment {
        reason = reason == null ? "" : reason;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
