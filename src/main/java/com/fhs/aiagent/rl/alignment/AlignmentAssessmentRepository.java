package com.fhs.aiagent.rl.alignment;

import java.util.List;
import java.util.Optional;

public interface AlignmentAssessmentRepository {

    AutomatedAlignmentAssessment save(AutomatedAlignmentAssessment assessment);

    Optional<AutomatedAlignmentAssessment> findByTrajectoryId(String trajectoryId);

    List<AutomatedAlignmentAssessment> findAll();

    default String namespace() {
        return "in-memory";
    }
}
