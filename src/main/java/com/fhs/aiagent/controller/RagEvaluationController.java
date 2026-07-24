package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.RagAbEvaluationJobService;
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

    public RagEvaluationController(RagAbEvaluationJobService jobService,
                                   RagAbEvaluationService evaluationService) {
        this.jobService = jobService;
        this.evaluationService = evaluationService;
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

    public record StartEvaluationRequest(Integer maximumCases) {
    }
}
