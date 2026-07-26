package com.fhs.aiagent.rag.multiagent;

import java.util.Optional;

public interface ProgressiveDeliveryAutomationRepository {

    Optional<ProgressiveDeliveryAutomationState> load();

    ProgressiveDeliveryAutomationState save(
            ProgressiveDeliveryAutomationState state);
}
