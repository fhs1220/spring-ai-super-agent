package com.fhs.aiagent.evaluation;

import java.util.List;

/** Durable job metadata; model answers and review evidence belong to the label repository. */
public interface SystemOneLabelingRunRepository {
    void save(SystemOneLabelingJobService.RunSnapshot snapshot);
    List<SystemOneLabelingJobService.RunSnapshot> findAll();
}
