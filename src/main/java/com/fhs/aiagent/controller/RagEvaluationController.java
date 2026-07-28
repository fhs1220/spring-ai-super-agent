package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.RagAbEvaluationJobService;
import com.fhs.aiagent.evaluation.AlignmentAblationReport;
import com.fhs.aiagent.evaluation.AlignmentAblationReportService;
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

    public RagEvaluationController(RagAbEvaluationJobService jobService,
                                   RagAbEvaluationService evaluationService,
                                   AlignmentAblationReportService
                                           alignmentAblationReportService) {
        this.jobService = jobService;
        this.evaluationService = evaluationService;
        this.alignmentAblationReportService = alignmentAblationReportService;
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

    public record StartEvaluationRequest(Integer maximumCases) {
    }

    public record AlignmentAblationRequest(
            List<AlignmentAblationReport.ArmInput> arms
    ) {
    }
}
