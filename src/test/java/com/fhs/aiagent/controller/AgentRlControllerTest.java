package com.fhs.aiagent.controller;

import com.fhs.aiagent.rl.AgentRlService;
import com.fhs.aiagent.rl.alignment.AiJudgePanelService;
import com.fhs.aiagent.rl.alignment.AlignmentAutomationService;
import com.fhs.aiagent.rl.bailian.BailianRlDatasetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRlControllerTest {

    @Test
    void missingAlignmentAssessmentReturnsNotFoundForSafeResume() {
        AiJudgePanelService panelService = mock(AiJudgePanelService.class);
        when(panelService.get("trajectory-1"))
                .thenThrow(new NoSuchElementException("missing"));
        AgentRlController controller = new AgentRlController(
                mock(AgentRlService.class),
                mock(BailianRlDatasetService.class),
                panelService,
                mock(AlignmentAutomationService.class)
        );

        assertThat(controller.assessment("trajectory-1").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
