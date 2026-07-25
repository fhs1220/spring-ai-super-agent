package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;

public record RoutingPolicyDeployment(
        String version,
        String policyVersion,
        RoutingPolicyMode mode,
        double canaryRate,
        Instant createdAt,
        String reason
) {

    public RoutingPolicyDeployment {
        policyVersion = policyVersion == null || policyVersion.isBlank()
                ? RoutingPolicyRegistryService.BASELINE_VERSION
                : policyVersion.trim();
        reason = reason == null ? "" : reason;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
