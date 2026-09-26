package com.fhs.aiagent.evaluation;

import java.util.List;
import java.util.Optional;

public interface SystemOneCounterfactualLabelRepository {

    SystemOneCounterfactualLabel save(SystemOneCounterfactualLabel label);

    Optional<SystemOneCounterfactualLabel> findBySampleId(String sampleId);

    List<SystemOneCounterfactualLabel> findAll();
}
