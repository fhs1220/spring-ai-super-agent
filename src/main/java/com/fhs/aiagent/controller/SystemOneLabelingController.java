package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.SystemOneCounterfactualLabel;
import com.fhs.aiagent.evaluation.SystemOneLabelingJobService;
import com.fhs.aiagent.evaluation.SystemOneLabelReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/agent-evaluation/system-one-labeling")
@ConditionalOnProperty(name = "agent.evaluation.api-enabled", havingValue = "true")
public class SystemOneLabelingController {

    private final SystemOneLabelingJobService jobService;
    private final SystemOneLabelReviewService reviewService;

    public SystemOneLabelingController(SystemOneLabelingJobService jobService,
                                      SystemOneLabelReviewService reviewService) {
        this.jobService = jobService;
        this.reviewService = reviewService;
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

    @GetMapping("/labels/{sampleId}")
    public SystemOneCounterfactualLabel evidence(@PathVariable String sampleId) {
        return reviewService.get(sampleId);
    }

    @PostMapping("/labels/{sampleId}/reviews")
    public SystemOneCounterfactualLabel review(@PathVariable String sampleId,
            @RequestBody SystemOneLabelReviewService.ReviewRequest request) {
        return reviewService.review(sampleId, request);
    }

    @GetMapping("/exports/development")
    public SystemOneLabelReviewService.TrainingDataset exportDevelopment() {
        return reviewService.exportDevelopment();
    }

    public record StartRequest(Integer maximumCases, Double maximumCostCny) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException exception) {
        return ResponseEntity.status(404).body(Map.of("error", "Label or run not found"));
    }
}
