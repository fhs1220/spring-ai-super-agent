package com.fhs.aiagent.controller;

import com.fhs.aiagent.app.LoveApp;
import com.fhs.aiagent.rag.multiagent.AdaptiveMultiAgentOrchestrator;
import com.fhs.aiagent.rag.multiagent.AgentDescriptor;
import com.fhs.aiagent.rl.AgentRlService;
import com.fhs.aiagent.rl.bailian.BailianRlDatasetService;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ai/love_app")
public class AiController {

    private final LoveApp loveApp;

    private final AgentRlService agentRlService;

    private final BailianRlDatasetService bailianRlDatasetService;

    private final AdaptiveMultiAgentOrchestrator multiAgentOrchestrator;

    public AiController(LoveApp loveApp,
                        AgentRlService agentRlService,
                        BailianRlDatasetService bailianRlDatasetService,
                        AdaptiveMultiAgentOrchestrator multiAgentOrchestrator) {
        this.loveApp = loveApp;
        this.agentRlService = agentRlService;
        this.bailianRlDatasetService = bailianRlDatasetService;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
    }

    @PostMapping("/chat/agentic-rag")
    public AgenticRagResult agenticRag(@RequestBody AgenticRagRequest request) {
        return loveApp.doChatWithAgenticRagTrace(request.message(), request.chatId());
    }

    @PostMapping("/agent-rl/feedback")
    public AgentTrajectory submitAgentRlFeedback(@RequestBody AgentRlFeedbackRequest request) {
        return agentRlService.submitFeedback(
                request.trajectoryId(), request.rating(), request.comment());
    }

    @GetMapping("/agent-rl/metrics")
    public AgentRlService.Metrics agentRlMetrics() {
        return agentRlService.metrics();
    }

    @GetMapping("/agent-rl/readiness")
    public BailianRlDatasetService.DatasetReadiness agentRlReadiness() {
        return bailianRlDatasetService.readinessDefault();
    }

    /**
     * 返回不含提示词和密钥的 Agent 能力卡，后续可直接映射到 A2A Agent Card。
     */
    @GetMapping("/agents")
    public List<AgentDescriptor> agents() {
        return multiAgentOrchestrator.descriptors();
    }

    public record AgenticRagRequest(String message, String chatId) {
    }

    public record AgentRlFeedbackRequest(String trajectoryId, int rating, String comment) {
    }
}
