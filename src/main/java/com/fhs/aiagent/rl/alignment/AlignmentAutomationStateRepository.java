package com.fhs.aiagent.rl.alignment;

import java.util.Optional;

public interface AlignmentAutomationStateRepository {

    Optional<AlignmentAutomationState> load();

    AlignmentAutomationState save(AlignmentAutomationState state);
}
