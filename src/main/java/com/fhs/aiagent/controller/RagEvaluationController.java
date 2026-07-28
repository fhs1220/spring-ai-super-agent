package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.RagAbEvaluationJobService;
import com.fhs.aiagent.evaluation.AlignmentAblationReport;
import com.fhs.aiagent.evaluation.AlignmentAblationReportService;
import com.fhs.aiagent.evaluation.AlignmentExperiment;
import com.fhs.aiagent.evaluation.AlignmentExperimentService;
import com.fhs.aiagent.evaluation.RagEvaluationCase;
import com.fhs.aiagent.evaluation.RagAbEvaluationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent-evaluation")
@ConditionalOnProperty(name = "agent.evaluation.api-enabled", havingValue = "true")
public class RagEvaluationController {

    private final RagAbEvaluationJobService jobService;

    private final RagAbEvaluationService evaluationService;

    private final AlignmentAblationReportService alignmentAblationReportService;

    private final AlignmentExperimentService alignmentExperimentService;

    public RagEvaluationController(RagAbEvaluationJobService jobService,
                                   RagAbEvaluationService evaluationService,
                                   AlignmentAblationReportService
                                           alignmentAblationReportService,
                                   AlignmentExperimentService
                                           alignmentExperimentService) {
        this.jobService = jobService;
        this.evaluationService = evaluationService;
        this.alignmentAblationReportService = alignmentAblationReportService;
        this.alignmentExperimentService = alignmentExperimentService;
    }

    @PostMapping("/ab-runs")
    public RagAbEvaluationJobService.RunSnapshot start(
            @RequestBody(required = false) StartEvaluationRequest request) {
        return jobService.start(request == null || request.maximumCases() == null
                ? 0
                : request.maximumCases());
    }

    @GetMapping("/ab-runs/{runId}")
    public RagAbEvaluationJobService.RunSnapshot get(@PathVariable String runId) {
        return jobService.get(runId);
    }

    @GetMapping("/ab-runs")
    public List<RagAbEvaluationJobService.RunSnapshot> recent(
            @RequestParam(defaultValue = "20") int limit) {
        return jobService.recent(limit);
    }

    @DeleteMapping("/ab-runs/{runId}")
    public RagAbEvaluationJobService.RunSnapshot cancel(@PathVariable String runId) {
        return jobService.cancel(runId);
    }

    @GetMapping("/benchmark")
    public List<RagEvaluationCase> benchmark() {
        return evaluationService.loadCases();
    }

    @GetMapping("/benchmark/metadata")
    public RagAbEvaluationService.BenchmarkMetadata benchmarkMetadata() {
        return evaluationService.benchmarkMetadata();
    }

    @PostMapping("/alignment-ablation-reports")
    public AlignmentAblationReport createAlignmentAblationReport(
            @RequestBody AlignmentAblationRequest request) {
        if (request == null || request.arms() == null) {
            throw new IllegalArgumentException("arms are required");
        }
        return alignmentAblationReportService.create(request.arms());
    }

    @GetMapping("/alignment-ablation-reports/{reportId}")
    public AlignmentAblationReport alignmentAblationReport(
            @PathVariable String reportId) {
        return alignmentAblationReportService.get(reportId);
    }

    @PostMapping("/alignment-experiments")
    public AlignmentExperiment createAlignmentExperiment(
            @RequestBody AlignmentExperimentRequest request) {
        if (request == null || request.arms() == null) {
            throw new IllegalArgumentException("arms are required");
        }
        return alignmentExperimentService.create(request.arms());
    }

    @GetMapping("/alignment-experiments/{experimentId}")
    public AlignmentExperiment alignmentExperiment(
            @PathVariable String experimentId) {
        return alignmentExperimentService.get(experimentId);
    }

    @PostMapping("/alignment-experiments/{experimentId}/arms/{arm}/evidence")
    public AlignmentExperiment attachAlignmentEvidence(
            @PathVariable String experimentId,
            @PathVariable AlignmentAblationReport.ExperimentArm arm,
            @RequestBody AlignmentEvidenceRequest request) {
        if (request == null || request.evaluationRunId() == null) {
            throw new IllegalArgumentException("evaluationRunId is required");
        }
        return alignmentExperimentService.attachEvidence(
                experimentId, arm, request.evaluationRunId());
    }

    @PostMapping("/alignment-experiments/{experimentId}/finalize")
    public AlignmentExperiment finalizeAlignmentExperiment(
            @PathVariable String experimentId) {
        return alignmentExperimentService.finalizeExperiment(experimentId);
    }

    public record StartEvaluationRequest(Integer maximumCases) {
    }

    public record AlignmentAblationRequest(
            List<AlignmentAblationReport.ArmInput> arms
    ) {
    }

    public record AlignmentExperimentRequest(
            List<AlignmentExperiment.ArmPlan> arms
    ) {
    }

    public record AlignmentEvidenceRequest(String evaluationRunId) {
    }
}
