package com.fhs.aiagent.rl.alignment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "agent.rl.alignment.auto-evaluate-enabled",
        havingValue = "true")
public class AutomatedAlignmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomatedAlignmentScheduler.class);

    private final AiJudgePanelService panelService;

    private final int batchSize;

    public AutomatedAlignmentScheduler(
            AiJudgePanelService panelService,
            @Value("${agent.rl.alignment.auto-evaluate-batch-size:10}") int batchSize) {
        this.panelService = panelService;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            fixedDelayString = "${agent.rl.alignment.auto-evaluate-interval-ms:300000}",
            initialDelayString = "${agent.rl.alignment.auto-evaluate-initial-delay-ms:60000}")
    public void assessPendingTrajectories() {
        try {
            AiJudgePanelService.BatchAssessmentResult result =
                    panelService.assessPending(batchSize);
            if (result.assessedCount() > 0) {
                log.info("AI Judge batch completed: assessed={}, positive={}, holdout={}, excluded={}",
                        result.assessedCount(),
                        result.positiveCount(),
                        result.holdoutCount(),
                        result.excludedOrNegativeCount());
            }
        } catch (RuntimeException exception) {
            log.warn("AI Judge scheduled batch failed: {}", exception.getMessage());
        }
    }
}
