package com.fhs.aiagent.rag.multiagent;

import java.util.List;

public record MultiAgentAnswer(
        String answer,
        MultiAgentDecision decision,
        List<SpecialistContribution> contributions,
        boolean fallbackRequired,
        long specialistDurationMs,
        long synthesisDurationMs
) {
}
