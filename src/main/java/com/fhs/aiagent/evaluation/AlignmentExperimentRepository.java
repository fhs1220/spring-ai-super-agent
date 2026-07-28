package com.fhs.aiagent.evaluation;

import java.util.Optional;

public interface AlignmentExperimentRepository {

    Optional<AlignmentExperiment> find(String experimentId);

    AlignmentExperiment save(AlignmentExperiment experiment);
}
