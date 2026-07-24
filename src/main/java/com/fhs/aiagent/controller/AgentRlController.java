package com.fhs.aiagent.controller;

import com.fhs.aiagent.rl.AgentRlService;
import com.fhs.aiagent.rl.bailian.BailianRlDatasetService;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent-rl")
@ConditionalOnProperty(name = "agent.rl.api-enabled", havingValue = "true")
public class AgentRlController {

    private final AgentRlService agentRlService;

    private final BailianRlDatasetService bailianRlDatasetService;

    public AgentRlController(AgentRlService agentRlService,
                             BailianRlDatasetService bailianRlDatasetService) {
        this.agentRlService = agentRlService;
        this.bailianRlDatasetService = bailianRlDatasetService;
    }

    @GetMapping("/trajectories/{trajectoryId}")
    public AgentTrajectory trajectory(@PathVariable String trajectoryId) {
        return agentRlService.getTrajectory(trajectoryId);
    }

    @GetMapping("/trajectories")
    public List<AgentTrajectory> recentTrajectories(@RequestParam(defaultValue = "20") int limit) {
        return agentRlService.recentTrajectories(limit);
    }

    @PostMapping("/feedback")
    public AgentTrajectory submitFeedback(@RequestBody FeedbackRequest request) {
        return agentRlService.submitFeedback(
                request.trajectoryId(), request.rating(), request.comment());
    }

    @GetMapping("/metrics")
    public AgentRlService.Metrics metrics() {
        return agentRlService.metrics();
    }

    @GetMapping(value = "/export", produces = "application/x-ndjson")
    public String export(@RequestParam(defaultValue = "0") double minimumReward) {
        return agentRlService.exportJsonLines(minimumReward);
    }

    @PostMapping("/bailian/datasets")
    public BailianRlDatasetService.DatasetExportResult exportBailianDataset(
            @RequestBody(required = false) BailianDatasetRequest request) {
        if (request == null) {
            return bailianRlDatasetService.exportDefault();
        }
        return bailianRlDatasetService.export(new BailianRlDatasetService.DatasetExportOptions(
                request.minimumReward() == null ? 0.7 : request.minimumReward(),
                request.validationRatio() == null ? 0.2 : request.validationRatio(),
                request.expectedBatchSize() == null ? 64 : request.expectedBatchSize(),
                request.requireHumanApproval() == null || request.requireHumanApproval()
        ));
    }

    public record FeedbackRequest(String trajectoryId, int rating, String comment) {
    }

    public record BailianDatasetRequest(
            Double minimumReward,
            Double validationRatio,
            Integer expectedBatchSize,
            Boolean requireHumanApproval
    ) {
    }
}
