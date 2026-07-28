package com.fhs.aiagent.rl.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.InMemoryAgentTrajectoryRepository;
import com.fhs.aiagent.rl.alignment.AutomatedAlignmentAssessment;
import com.fhs.aiagent.rl.alignment.InMemoryAlignmentAssessmentRepository;
import com.fhs.aiagent.rl.alignment.SupervisionLabel;
import com.fhs.aiagent.rl.alignment.TrainingDecision;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BailianRlDatasetServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void exportsHumanApprovedTrajectoriesInBailianFormat() throws Exception {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        for (int index = 1; index <= 5; index++) {
            repository.save(trajectory(index, 5));
        }
        repository.save(trajectory(6, null));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        BailianRlDatasetService service = new BailianRlDatasetService(
                repository, objectMapper, tempDirectory.toString(), 0.7, 0.2, 3);

        BailianRlDatasetService.DatasetExportResult result = service.exportDefault();
        BailianRlDatasetService.DatasetReadiness readiness = service.readinessDefault();

        assertThat(result.trainingCount()).isEqualTo(4);
        assertThat(result.validationCount()).isEqualTo(1);
        assertThat(result.eligibleCount()).isEqualTo(5);
        assertThat(result.readyForCloudSubmission()).isTrue();
        assertThat(readiness.totalTrajectoryCount()).isEqualTo(6);
        assertThat(readiness.trainingCount()).isEqualTo(4);
        assertThat(readiness.validationCount()).isEqualTo(1);
        assertThat(readiness.readyForCloudSubmission()).isTrue();
        assertThat(Path.of(result.trainingFile())).isRegularFile();
        assertThat(Path.of(result.validationFile())).isRegularFile();
        assertThat(Path.of(result.manifestFile())).isRegularFile();

        String firstLine = Files.readAllLines(Path.of(result.trainingFile())).get(0);
        JsonNode sample = objectMapper.readTree(firstLine);
        assertThat(sample.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(sample.at("/messages/0/content").asText()).startsWith("问题");
        assertThat(sample.at("/rollout_extra/solution").asText()).startsWith("人工审核答案");
        assertThat(sample.at("/rollout_extra/human_rating").asInt()).isEqualTo(5);
    }

    @Test
    void reportsNotReadyWhenHumanApprovedDataIsMissing() {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        repository.save(trajectory(1, null));
        BailianRlDatasetService service = new BailianRlDatasetService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                tempDirectory.toString(),
                0.7,
                0.2,
                64
        );

        BailianRlDatasetService.DatasetExportResult result = service.exportDefault();

        assertThat(result.readyForCloudSubmission()).isFalse();
        assertThat(result.eligibleCount()).isZero();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("人工审核"));
    }

    @Test
    void exportsHighConfidenceAiApprovedDataWithoutHumanRatings() throws Exception {
        InMemoryAgentTrajectoryRepository repository = new InMemoryAgentTrajectoryRepository();
        InMemoryAlignmentAssessmentRepository assessments =
                new InMemoryAlignmentAssessmentRepository();
        for (int index = 1; index <= 5; index++) {
            AgentTrajectory trajectory = trajectory(index, null);
            repository.save(trajectory);
            assessments.save(approvedAssessment(trajectory));
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        BailianRlDatasetService service = new BailianRlDatasetService(
                repository,
                assessments,
                objectMapper,
                tempDirectory.toString(),
                0.7,
                0.2,
                3,
                "AUTOMATED_ALIGNMENT"
        );

        BailianRlDatasetService.DatasetExportResult result = service.exportDefault();
        BailianRlDatasetService.DatasetReadiness readiness = service.readinessDefault();

        assertThat(result.eligibleCount()).isEqualTo(5);
        assertThat(result.readyForCloudSubmission()).isTrue();
        assertThat(readiness.approvalMode())
                .isEqualTo(BailianRlDatasetService.TrainingApprovalMode.AUTOMATED_ALIGNMENT);
        assertThat(readiness.automatedApprovedCount()).isEqualTo(5);
        assertThat(readiness.humanApprovedCount()).isZero();
        String firstLine = Files.readAllLines(Path.of(result.trainingFile())).get(0);
        JsonNode sample = objectMapper.readTree(firstLine);
        assertThat(sample.at("/rollout_extra/human_rating").isNull()).isTrue();
        assertThat(sample.at("/rollout_extra/alignment_label").asText())
                .isEqualTo("PSEUDO_LABELED");
        assertThat(sample.at("/rollout_extra/alignment_confidence").asDouble())
                .isEqualTo(0.92);
        assertThat(sample.at("/rollout_extra/judge_agreement").asDouble())
                .isEqualTo(0.9);
        assertThat(sample.at("/rollout_extra/reward_schema_version").asText())
                .isEqualTo("human-light-rlvr-v2");
        assertThat(sample.at("/rollout_extra/verification_contract/citation_required")
                .asBoolean()).isTrue();
    }

    private AutomatedAlignmentAssessment approvedAssessment(AgentTrajectory trajectory) {
        return new AutomatedAlignmentAssessment(
                trajectory.trajectoryId(),
                "fingerprint-" + trajectory.trajectoryId(),
                trajectory.policyVersion(),
                Instant.parse("2026-07-28T00:00:00Z"),
                SupervisionLabel.PSEUDO_LABELED,
                TrainingDecision.POSITIVE,
                0.9,
                0.88,
                0.89,
                0.92,
                0.9,
                4,
                List.of(),
                List.of("自动评审通过"),
                false
        );
    }

    private AgentTrajectory trajectory(int index, Integer rating) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z").plusSeconds(index);
        return new AgentTrajectory(
                "trajectory-" + index,
                "chat-" + index,
                "agentic-rag-v1",
                "qwen-plus",
                "问题 " + index,
                now,
                now,
                "COMPLETED",
                List.of(),
                List.of("doc-" + index),
                "人工审核答案 " + index,
                new RewardBreakdown(0.9, 1, 1, 1, 1, 1, 1, rating == null ? 0 : 1),
                rating,
                rating == null ? null : "已审核",
                null,
                null
        );
    }
}
