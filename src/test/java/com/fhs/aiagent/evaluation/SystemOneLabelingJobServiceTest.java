package com.fhs.aiagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SystemOneLabelingJobServiceTest {

    @Test
    void zeroBudgetCompletesWithoutReadingSamplesOrCallingModels() throws Exception {
        SystemOneShadowSampleService sampleService = mock(SystemOneShadowSampleService.class);
        SystemOneCounterfactualLabelingService labelingService =
                mock(SystemOneCounterfactualLabelingService.class);
        EmptyRepository repository = new EmptyRepository();
        SystemOneLabelingJobService service = new SystemOneLabelingJobService(
                sampleService, labelingService, repository, 20, 10, false);
        try {
            SystemOneLabelingJobService.RunSnapshot started = service.start(20, 0.0);
            SystemOneLabelingJobService.RunSnapshot completed = await(service, started.runId());

            assertThat(completed.status()).isEqualTo("COMPLETED_BUDGET_LIMIT");
            assertThat(completed.completedCases()).isZero();
            assertThat(completed.report().budgetLimitReached()).isTrue();
            verifyNoInteractions(sampleService, labelingService);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void sealsHoldoutLabelsByDefault() {
        SystemOneLabelingJobService service = new SystemOneLabelingJobService(
                mock(SystemOneShadowSampleService.class),
                mock(SystemOneCounterfactualLabelingService.class),
                new EmptyRepository(), 20, 10, false);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.labels("HOLDOUT"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sealed");
            assertThat(service.labels("DEVELOPMENT")).isEmpty();
        } finally {
            service.shutdown();
        }
    }

    private SystemOneLabelingJobService.RunSnapshot await(
            SystemOneLabelingJobService service, String runId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            SystemOneLabelingJobService.RunSnapshot snapshot = service.get(runId);
            if (snapshot.completedAt() != null) return snapshot;
            Thread.sleep(10);
        }
        throw new AssertionError("labeling run did not finish");
    }

    private static final class EmptyRepository
            implements SystemOneCounterfactualLabelRepository {
        @Override
        public SystemOneCounterfactualLabel save(SystemOneCounterfactualLabel label) {
            return label;
        }

        @Override
        public Optional<SystemOneCounterfactualLabel> findBySampleId(String sampleId) {
            return Optional.empty();
        }

        @Override
        public List<SystemOneCounterfactualLabel> findAll() {
            return List.of();
        }
    }
}
