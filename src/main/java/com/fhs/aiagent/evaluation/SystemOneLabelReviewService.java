package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Explicit human sign-off; AI confidence never authorizes a training export. */
@Service
public class SystemOneLabelReviewService {
    private final SystemOneCounterfactualLabelRepository repository;
    private final boolean exposeHoldout;
    private final ObjectMapper objectMapper;

    public SystemOneLabelReviewService(SystemOneCounterfactualLabelRepository repository,
            ObjectMapper objectMapper,
            @Value("${agent.evaluation.system-one.labeling.expose-holdout-labels:false}") boolean exposeHoldout) {
        this.repository = repository;
        this.exposeHoldout = exposeHoldout;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public SystemOneCounterfactualLabel get(String sampleId) {
        var label = repository.findBySampleId(sampleId)
                .orElseThrow(() -> new NoSuchElementException("Label not found"));
        if (!"DEVELOPMENT".equals(label.split()) && !exposeHoldout) {
            throw new IllegalArgumentException("Holdout labels are sealed");
        }
        return label;
    }

    public SystemOneCounterfactualLabel review(String sampleId, ReviewRequest request) {
        if (request == null || request.reviewer() == null || request.reviewer().isBlank()
                || request.reason() == null || request.reason().isBlank()
                || request.reviewer().length() > 120 || request.reason().length() > 2000
                || request.accepted() == null || request.expectedRevision() == null
                || (request.accepted() && request.expectedMultiAgent() == null)) {
            throw new IllegalArgumentException("Reviewer, reason, accepted and expectedRevision are required");
        }
        synchronized (repository) {
            var label = get(sampleId);
            if (request.expectedRevision() != label.reviews().size()) {
                throw new IllegalStateException("Review revision conflict; reload before submitting");
            }
            if ("IN_PROGRESS".equals(label.status())) {
                throw new IllegalStateException("Cannot review an active sample");
            }
            if (request.accepted() && (label.evidence() == null || !label.evidence().completePair()
                    || label.evidence().judgment() == null || !label.costAccountingComplete())) {
                throw new IllegalStateException("Approval requires a complete, cost-accounted pair and judgment");
            }
            return repository.save(label.reviewed(new SystemOneCounterfactualLabel.HumanReview(
                    label.reviews().size() + 1, Instant.now(), request.reviewer().trim(),
                    request.reason().trim(), request.accepted(), request.expectedMultiAgent())));
        }
    }

    public TrainingDataset exportDevelopment() {
        List<TrainingExample> samples = repository.findAll().stream()
                .filter(SystemOneCounterfactualLabel::trainingEligible)
                .sorted(Comparator.comparing(SystemOneCounterfactualLabel::sampleId))
                .map(label -> new TrainingExample(label.sampleId(), label.questionFingerprint(),
                        label.evidence().question(), label.expectedMultiAgent(), label.singleUtility(),
                        label.multiUtility(), label.evidence().utilityPolicy(),
                        label.evidence().provenance(), label.reviews().size()))
                .toList();
        try {
            String hash = SpringCounterfactualQualityJudge.fingerprint(objectMapper.writeValueAsString(samples));
            List<String> frames = samples.stream()
                    .map(sample -> sample.provenance().getOrDefault("samplingFrame", "UNKNOWN_NOT_POPULATION"))
                    .distinct().toList();
            String samplingFrame = frames.isEmpty() ? "EMPTY"
                    : frames.size() == 1 ? frames.getFirst() : "MIXED_NOT_POPULATION";
            return new TrainingDataset("system-one-reviewed-development-v1",
                    samplingFrame, hash, samples);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not fingerprint training export", exception);
        }
    }

    public record ReviewRequest(Integer expectedRevision, String reviewer, String reason,
                                Boolean accepted, Boolean expectedMultiAgent) { }
    public record TrainingExample(String sampleId, String questionFingerprint, String question,
                                  boolean expectedMultiAgent, double singleUtility, double multiUtility,
                                  SystemOneUtilityPolicy utilityPolicy, Map<String, String> provenance,
                                  int reviewRevision) { }
    public record TrainingDataset(String schemaVersion, String samplingFrame, String fingerprint,
                                  List<TrainingExample> samples) { }
}
