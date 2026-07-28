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
                .isEqualTo("human-light-rlvr-v3");
        assertThat(sample.at("/rollout_extra/verification_contract/citation_required")
                .asBoolean()).isTrue();
    }

    @Test
    void rlvrOnlyProfileDoesNotPretendAiJudgeFilteringWasApplied() {
        InMemoryAgentTrajectoryRepository repository =
                new InMemoryAgentTrajectoryRepository();
        for (int index = 1; index <= 5; index++) {
            repository.save(trajectory(index, null));
        }
        BailianRlDatasetService service = new BailianRlDatasetService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                tempDirectory.toString(),
                0.7,
                0.2,
                3
        );

        BailianRlDatasetService.DatasetReadiness readiness = service.readiness(
                new BailianRlDatasetService.DatasetExportOptions(
                        0.7,
                        0.2,
                        3,
                        BailianRlDatasetService.TrainingApprovalMode
                                .AUTOMATED_ALIGNMENT,
                        BailianRlDatasetService.TrainingDatasetProfile.RLVR_ONLY
                ));

        assertThat(readiness.eligibleTrajectoryCount()).isEqualTo(5);
        assertThat(readiness.datasetProfile())
                .isEqualTo(
                        BailianRlDatasetService.TrainingDatasetProfile.RLVR_ONLY);
        assertThat(readiness.automatedApprovedCount()).isZero();
    }

    @Test
    void fullProfileAppliesTrajectoryTrendSelectionAfterAiApproval() {
        InMemoryAgentTrajectoryRepository repository =
                new InMemoryAgentTrajectoryRepository();
        InMemoryAlignmentAssessmentRepository assessments =
                new InMemoryAlignmentAssessmentRepository();
        List<AgentTrajectory> trajectories = List.of(
                trajectory("anchor-1", "锚点问题", 0.4, 5, 1),
                trajectory("anchor-2", "锚点问题", 0.8, 5, 2),
                trajectory("candidate-1", "候选问题", 0.3, null, 3),
                trajectory("candidate-2", "候选问题", 0.7, null, 4)
        );
        trajectories.forEach(trajectory -> {
            repository.save(trajectory);
            assessments.save(approvedAssessment(trajectory));
        });
        BailianRlDatasetService service = new BailianRlDatasetService(
                repository,
                assessments,
                new ObjectMapper().findAndRegisterModules(),
                tempDirectory.toString(),
                0,
                0.5,
                1,
                "AUTOMATED_ALIGNMENT"
        );

        BailianRlDatasetService.DatasetReadiness readiness = service.readiness(
                new BailianRlDatasetService.DatasetExportOptions(
                        0,
                        0.5,
                        1,
                        BailianRlDatasetService.TrainingApprovalMode
                                .AUTOMATED_ALIGNMENT,
                        BailianRlDatasetService.TrainingDatasetProfile
                                .FULL_TRAJECTORY_GUIDED
                ));

        assertThat(readiness.eligibleTrajectoryCount()).isEqualTo(2);
        assertThat(readiness.trajectorySelectedCount()).isEqualTo(1);
        assertThat(readiness.trajectoryUnscorableCount()).isZero();
        assertThat(readiness.trajectorySelectionRate()).isEqualTo(1);
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

    private AgentTrajectory trajectory(String id,
                                       String question,
                                       double reward,
                                       Integer rating,
                                       int seconds) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z")
                .plusSeconds(seconds);
        return new AgentTrajectory(
                id,
                "chat-" + id,
                "policy-" + seconds,
                "qwen-plus",
                question,
                now,
                now,
                "COMPLETED",
                List.of(),
                List.of("doc-" + id),
                "答案 " + id,
                new RewardBreakdown(reward, 1, 1, 1, 1, 1, 1, 0),
                rating,
                rating == null ? null : "人工锚点",
                null,
                null
        );
    }
}
