package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.multiagent.SystemOneRoutingAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class SystemOneCalibrationService {

    private static final double MINIMUM_MULTI_AGENT_RECALL = 0.5;

    private final ObjectMapper objectMapper;
    private final SystemOneRoutingAdvisor advisor;
    private final Resource datasetResource;
    private final String dataset;
    private final double configuredThreshold;
    private final double safetyThreshold;

    public SystemOneCalibrationService(
            ObjectMapper objectMapper,
            SystemOneRoutingAdvisor advisor,
            ResourceLoader resourceLoader,
            @Value("${agent.evaluation.system-one.calibration-dataset:classpath:evaluation/system-one-calibration-v1.jsonl}")
            String dataset,
            @Value("${agent.decision.system-one.multi-agent-threshold:0.2}")
            double configuredThreshold,
            @Value("${agent.decision.system-one.safety-threshold:0.5}")
            double safetyThreshold) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.advisor = java.util.Objects.requireNonNull(advisor, "advisor");
        this.dataset = dataset == null ? "" : dataset.trim();
        this.datasetResource = resourceLoader.getResource(this.dataset);
        this.configuredThreshold = clamp(configuredThreshold);
        this.safetyThreshold = clamp(safetyThreshold);
    }

    public List<SystemOneCalibrationCase> loadCases() {
        if (!datasetResource.exists()) {
            throw new IllegalStateException("Calibration dataset does not exist: " + dataset);
        }
        List<SystemOneCalibrationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                datasetResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String normalized = line.trim();
                if (normalized.isBlank() || normalized.startsWith("#")) continue;
                try {
                    SystemOneCalibrationCase item = objectMapper.readValue(
                            normalized, SystemOneCalibrationCase.class);
                    if (!ids.add(item.id())) {
                        throw new IllegalStateException(
                                "Duplicate calibration id: " + item.id());
                    }
                    cases.add(item);
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException(
                            "Invalid calibration JSONL at line " + lineNumber, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read calibration dataset", exception);
        }
        if (cases.size() < 20) {
            throw new IllegalStateException("Calibration dataset requires at least 20 cases");
        }
        return List.copyOf(cases);
    }

    public SystemOneCalibrationReport evaluate() {
        List<SystemOneCalibrationCase> source = loadCases();
        List<Observation> observations = new ArrayList<>();
        String model = "";
        long inputTokens = 0;
        long outputTokens = 0;
        double cost = 0;
        long latency = 0;
        int successes = 0;
        for (SystemOneCalibrationCase item : source) {
            SystemOneRoutingAdvisor.RoutingAdvice advice = advisor.advise(item.question());
            boolean success = "SUCCESS".equals(advice.status());
            if (success) {
                successes++;
                model = advice.model();
            }
            inputTokens += advice.inputTokens();
            outputTokens += advice.outputTokens();
            cost += advice.estimatedCostUsd();
            latency += advice.latencyMs();
            observations.add(new Observation(item, advice));
        }

        List<SystemOneCalibrationReport.ThresholdResult> sweep = thresholdSweep(observations);
        SystemOneCalibrationReport.ThresholdResult recommended = sweep.stream()
                .filter(item -> item.metrics().recall() >= MINIMUM_MULTI_AGENT_RECALL)
                .max(Comparator
                        .comparingDouble((SystemOneCalibrationReport.ThresholdResult item) ->
                                item.metrics().balancedAccuracy())
                        .thenComparingDouble(item -> item.metrics().precision())
                        .thenComparingDouble(SystemOneCalibrationReport.ThresholdResult::threshold))
                .orElseGet(() -> sweep.stream().max(Comparator.comparingDouble(item ->
                        item.metrics().balancedAccuracy())).orElseThrow());

        List<SystemOneCalibrationReport.CaseResult> caseResults = observations.stream()
                .map(item -> caseResult(item, configuredThreshold))
                .toList();
        return new SystemOneCalibrationReport(
                dataset,
                fingerprint(source),
                model,
                source.size(),
                successes,
                source.size() - successes,
                round(ratio(successes, source.size())),
                configuredThreshold,
                recommended.threshold(),
                metrics(observations, configuredThreshold, false),
                recommended.metrics(),
                metrics(observations, safetyThreshold, true),
                inputTokens,
                outputTokens,
                roundCost(cost),
                round(latency / (double) Math.max(1, source.size())),
                sweep,
                caseResults
        );
    }

    private List<SystemOneCalibrationReport.ThresholdResult> thresholdSweep(
            List<Observation> observations) {
        List<SystemOneCalibrationReport.ThresholdResult> results = new ArrayList<>();
        for (int step = 0; step <= 20; step++) {
            double threshold = step / 20.0;
            results.add(new SystemOneCalibrationReport.ThresholdResult(
                    threshold, metrics(observations, threshold, false)));
        }
        return List.copyOf(results);
    }

    private SystemOneCalibrationReport.CaseResult caseResult(
            Observation observation, double threshold) {
        SystemOneCalibrationCase item = observation.item();
        SystemOneRoutingAdvisor.RoutingAdvice advice = observation.advice();
        boolean success = "SUCCESS".equals(advice.status());
        return new SystemOneCalibrationReport.CaseResult(
                item.id(),
                item.expectedMultiAgent(),
                success && advice.multiAgentProbability() >= threshold,
                round(advice.multiAgentProbability()),
                item.expectedSafetyGuard(),
                success && advice.safetyProbability() >= safetyThreshold,
                round(advice.safetyProbability()),
                advice.status(),
                advice.latencyMs(),
                item.rationale()
        );
    }

    private SystemOneCalibrationReport.ClassificationMetrics metrics(
            List<Observation> observations, double threshold, boolean safety) {
        int truePositive = 0;
        int falsePositive = 0;
        int trueNegative = 0;
        int falseNegative = 0;
        for (Observation observation : observations) {
            boolean expected = safety
                    ? observation.item().expectedSafetyGuard()
                    : observation.item().expectedMultiAgent();
            double probability = safety
                    ? observation.advice().safetyProbability()
                    : observation.advice().multiAgentProbability();
            boolean predicted = "SUCCESS".equals(observation.advice().status())
                    && probability >= threshold;
            if (expected && predicted) truePositive++;
            else if (!expected && predicted) falsePositive++;
            else if (!expected) trueNegative++;
            else falseNegative++;
        }
        double recall = ratio(truePositive, truePositive + falseNegative);
        double specificity = ratio(trueNegative, trueNegative + falsePositive);
        return new SystemOneCalibrationReport.ClassificationMetrics(
                round(ratio(truePositive + trueNegative, observations.size())),
                round(ratio(truePositive, truePositive + falsePositive)),
                round(recall),
                round(specificity),
                round((recall + specificity) / 2),
                truePositive, falsePositive, trueNegative, falseNegative);
    }

    private String fingerprint(List<SystemOneCalibrationCase> cases) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SystemOneCalibrationCase item : cases) {
                digest.update(objectMapper.writeValueAsBytes(item));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Could not fingerprint calibration dataset", exception);
        }
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : numerator / (double) denominator;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private double roundCost(double value) {
        return Math.round(value * 100_000_000.0) / 100_000_000.0;
    }

    private record Observation(
            SystemOneCalibrationCase item,
            SystemOneRoutingAdvisor.RoutingAdvice advice
    ) {
    }
}
