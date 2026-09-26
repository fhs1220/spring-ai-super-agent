package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.SystemOneCounterfactualLabel;
import com.fhs.aiagent.evaluation.SystemOneLabelingJobService;
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
@RequestMapping("/agent-evaluation/system-one-labeling")
@ConditionalOnProperty(name = "agent.evaluation.api-enabled", havingValue = "true")
public class SystemOneLabelingController {

    private final SystemOneLabelingJobService jobService;

    public SystemOneLabelingController(SystemOneLabelingJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/runs")
    public SystemOneLabelingJobService.RunSnapshot start(
            @RequestBody(required = false) StartRequest request) {
        return jobService.start(
                request == null ? null : request.maximumCases(),
                request == null ? null : request.maximumCostCny());
    }

    @GetMapping("/runs/{runId}")
    public SystemOneLabelingJobService.RunSnapshot get(@PathVariable String runId) {
        return jobService.get(runId);
    }

    @DeleteMapping("/runs/{runId}")
    public SystemOneLabelingJobService.RunSnapshot cancel(@PathVariable String runId) {
        return jobService.cancel(runId);
    }

    @GetMapping("/labels")
    public List<SystemOneCounterfactualLabel> labels(
            @RequestParam(defaultValue = "DEVELOPMENT") String split) {
        return jobService.labels(split);
    }

    public record StartRequest(Integer maximumCases, Double maximumCostCny) {
    }
}
