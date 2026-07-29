package com.fhs.aiagent.controller;

import com.fhs.aiagent.rl.AgentRlService;
import com.fhs.aiagent.rl.alignment.AlignmentAutomationService;
import com.fhs.aiagent.rl.alignment.AiJudgePanelService;
import com.fhs.aiagent.rl.alignment.AutomatedAlignmentAssessment;
import com.fhs.aiagent.rl.bailian.BailianRlDatasetService;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/agent-rl")
@ConditionalOnProperty(name = "agent.rl.api-enabled", havingValue = "true")
public class AgentRlController {

    private final AgentRlService agentRlService;

    private final BailianRlDatasetService bailianRlDatasetService;

    private final AiJudgePanelService aiJudgePanelService;

    private final AlignmentAutomationService alignmentAutomationService;

    public AgentRlController(AgentRlService agentRlService,
                             BailianRlDatasetService bailianRlDatasetService,
                             AiJudgePanelService aiJudgePanelService,
                             AlignmentAutomationService
                                     alignmentAutomationService) {
        this.agentRlService = agentRlService;
        this.bailianRlDatasetService = bailianRlDatasetService;
        this.aiJudgePanelService = aiJudgePanelService;
        this.alignmentAutomationService = alignmentAutomationService;
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

    @PostMapping("/alignment/assessments/{trajectoryId}")
    public AutomatedAlignmentAssessment assess(@PathVariable String trajectoryId) {
        return aiJudgePanelService.assess(trajectoryId);
    }

    @GetMapping("/alignment/assessments/{trajectoryId}")
    public ResponseEntity<AutomatedAlignmentAssessment> assessment(
            @PathVariable String trajectoryId) {
        try {
            return ResponseEntity.ok(aiJudgePanelService.get(trajectoryId));
        } catch (NoSuchElementException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/alignment/assessments")
    public AlignmentAutomationService.AutomationRunResult assessPending(
            @RequestParam(defaultValue = "10") int limit) {
        return alignmentAutomationService.runManual(limit);
    }

    @GetMapping("/alignment/metrics")
    public AiJudgePanelService.AlignmentMetrics alignmentMetrics() {
        return aiJudgePanelService.metrics();
    }

    @GetMapping("/alignment/contract")
    public AiJudgePanelService.JudgeContract alignmentContract() {
        return aiJudgePanelService.contract();
    }

    @GetMapping("/alignment/automation")
    public AlignmentAutomationService.AutomationStatus alignmentAutomation() {
        return alignmentAutomationService.status();
    }

    @PostMapping("/alignment/automation/run")
    public AlignmentAutomationService.AutomationRunResult
    runAlignmentAutomation(
            @RequestParam(defaultValue = "10") int limit) {
        return alignmentAutomationService.runManual(limit);
    }

    @PostMapping("/alignment/automation/control")
    public AlignmentAutomationService.AutomationStatus
    controlAlignmentAutomation(
            @RequestBody AlignmentAutomationControlRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("control request is required");
        }
        return alignmentAutomationService.updateControl(
                request.paused(),
                request.resetFailureCircuit(),
                request.reason()
        );
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
        BailianRlDatasetService.TrainingApprovalMode approvalMode =
                approvalMode(request);
        return bailianRlDatasetService.export(new BailianRlDatasetService.DatasetExportOptions(
                request.minimumReward() == null ? 0.7 : request.minimumReward(),
                request.validationRatio() == null ? 0.2 : request.validationRatio(),
                request.expectedBatchSize() == null ? 64 : request.expectedBatchSize(),
                approvalMode,
                datasetProfile(request, approvalMode)
        ));
    }

    private BailianRlDatasetService.TrainingDatasetProfile datasetProfile(
            BailianDatasetRequest request,
            BailianRlDatasetService.TrainingApprovalMode approvalMode) {
        if (request.datasetProfile() == null
                || request.datasetProfile().isBlank()) {
            return approvalMode
                    == BailianRlDatasetService.TrainingApprovalMode.HUMAN_ONLY
                    ? BailianRlDatasetService.TrainingDatasetProfile.HUMAN_APPROVED
                    : BailianRlDatasetService.TrainingDatasetProfile
                    .FULL_TRAJECTORY_GUIDED;
        }
        try {
            return BailianRlDatasetService.TrainingDatasetProfile.valueOf(
                    request.datasetProfile().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "datasetProfile must be HUMAN_APPROVED, RLVR_ONLY, "
                            + "RLVR_RLAIF or FULL_TRAJECTORY_GUIDED",
                    exception
            );
        }
    }

    private BailianRlDatasetService.TrainingApprovalMode approvalMode(
            BailianDatasetRequest request) {
        if (request.approvalMode() != null && !request.approvalMode().isBlank()) {
            try {
                return BailianRlDatasetService.TrainingApprovalMode.valueOf(
                        request.approvalMode().trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "approvalMode must be HUMAN_ONLY or AUTOMATED_ALIGNMENT",
                        exception);
            }
        }
        if (request.requireHumanApproval() != null) {
            return request.requireHumanApproval()
                    ? BailianRlDatasetService.TrainingApprovalMode.HUMAN_ONLY
                    : BailianRlDatasetService.TrainingApprovalMode.AUTOMATED_ALIGNMENT;
        }
        return BailianRlDatasetService.TrainingApprovalMode.AUTOMATED_ALIGNMENT;
    }

    public record FeedbackRequest(String trajectoryId, int rating, String comment) {
    }

    public record AlignmentAutomationControlRequest(
            boolean paused,
            boolean resetFailureCircuit,
            String reason
    ) {
    }

    public record BailianDatasetRequest(
            Double minimumReward,
            Double validationRatio,
            Integer expectedBatchSize,
            Boolean requireHumanApproval,
            String approvalMode,
            String datasetProfile
    ) {
    }
}
