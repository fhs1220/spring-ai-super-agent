package com.fhs.aiagent.rl.alignment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryAlignmentAssessmentRepository implements AlignmentAssessmentRepository {

    private final ConcurrentMap<String, AutomatedAlignmentAssessment> assessments =
            new ConcurrentHashMap<>();

    @Override
    public AutomatedAlignmentAssessment save(AutomatedAlignmentAssessment assessment) {
        assessments.put(assessment.trajectoryId(), assessment);
        return assessment;
    }

    @Override
    public Optional<AutomatedAlignmentAssessment> findByTrajectoryId(String trajectoryId) {
        return Optional.ofNullable(assessments.get(trajectoryId));
    }

    @Override
    public List<AutomatedAlignmentAssessment> findAll() {
        return assessments.values().stream()
                .sorted(Comparator.comparing(
                        AutomatedAlignmentAssessment::evaluatedAt,
                        Comparator.reverseOrder()))
                .toList();
    }
}
