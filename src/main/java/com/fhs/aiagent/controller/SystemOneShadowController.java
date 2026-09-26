package com.fhs.aiagent.controller;

import com.fhs.aiagent.evaluation.SystemOneShadowSampleService;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowComparison;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent-evaluation/system-one-shadow")
@ConditionalOnProperty(name = "agent.evaluation.api-enabled", havingValue = "true")
public class SystemOneShadowController {

    private final SystemOneShadowSampleService service;
    private final SystemOneShadowComparison comparison;

    public SystemOneShadowController(
            SystemOneShadowSampleService service,
            SystemOneShadowComparison comparison) {
        this.service = service;
        this.comparison = comparison;
    }

    @PostMapping("/observations")
    public SystemOneShadowComparison.Submission observe(@RequestBody ObservationRequest request) {
        return comparison.submit(
                request.question(),
                request.authoritativeMultiAgent(),
                request.featureBucket());
    }

    @GetMapping("/samples")
    public List<SystemOneShadowSample> samples(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean reviewOnly) {
        return service.recent(limit, reviewOnly);
    }

    @GetMapping("/summary")
    public SystemOneShadowSampleService.Summary summary() {
        return service.summary();
    }

    public record ObservationRequest(
            String question,
            boolean authoritativeMultiAgent,
            String featureBucket
    ) {

        public ObservationRequest {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            question = question.trim();
            if (question.length() > 50_000) {
                throw new IllegalArgumentException("question is too large");
            }
            featureBucket = featureBucket == null ? "MANUAL" : featureBucket.trim();
        }
    }
}
