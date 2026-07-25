package com.fhs.aiagent.rag.multiagent;

import java.util.Optional;

public interface RoutingPolicyRegistryRepository {

    Optional<RoutingPolicyRegistryState> load();

    RoutingPolicyRegistryState save(RoutingPolicyRegistryState state);
}
