package com.fhs.aiagent.evaluation;

import com.fhs.aiagent.rag.multiagent.SystemOneShadowSample;
import com.fhs.aiagent.rag.multiagent.SystemOneShadowSampleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SystemOneShadowSampleService {

    private final SystemOneShadowSampleRepository repository;

    public SystemOneShadowSampleService(SystemOneShadowSampleRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
    }

    public List<SystemOneShadowSample> recent(int limit, boolean reviewOnly) {
        return repository.findRecent(Math.max(1, Math.min(1000, limit))).stream()
                .filter(sample -> !reviewOnly || sample.reviewEligible())
                .toList();
    }

    public Summary summary() {
        List<SystemOneShadowSample> samples = repository.findRecent(1000);
        Map<String, Long> disagreementCounts = samples.stream()
                .flatMap(sample -> sample.disagreementTypes().stream())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        long reviewEligible = samples.stream()
                .filter(SystemOneShadowSample::reviewEligible)
                .count();
        long questionTextAvailable = samples.stream()
                .filter(sample -> !sample.question().isBlank())
                .count();
        return new Summary(
                samples.size(),
                reviewEligible,
                samples.size() - reviewEligible,
                questionTextAvailable,
                Map.copyOf(disagreementCounts)
        );
    }

    public record Summary(
            int retainedSamples,
            long reviewEligibleSamples,
            long agreementControlSamples,
            long questionTextAvailableSamples,
            Map<String, Long> disagreementCounts
    ) {
    }
}
