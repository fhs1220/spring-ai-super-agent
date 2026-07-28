package com.fhs.aiagent.rl.bailian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.alignment.AlignmentAssessmentRepository;
import com.fhs.aiagent.rl.alignment.AutomatedAlignmentAssessment;
import com.fhs.aiagent.rl.alignment.InMemoryAlignmentAssessmentRepository;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 将本地 Agent 轨迹转换为阿里云百炼 Agentic RL 接受的 JSONL 格式。
 *
 * <p>默认采用通过 RLVR 硬门禁和多 AI Judge 高置信一致评审的轨迹；
 * 仍可切换回仅使用 4～5 星人工反馈的保守模式。</p>
 */
@Service
public class BailianRlDatasetService {

    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AgentTrajectoryRepository repository;

    private final ObjectMapper objectMapper;

    private final Path exportRoot;

    private final double defaultMinimumReward;

    private final double defaultValidationRatio;

    private final int defaultExpectedBatchSize;

    private final AlignmentAssessmentRepository assessmentRepository;

    private final TrainingApprovalMode defaultApprovalMode;

    @Autowired
    public BailianRlDatasetService(
            AgentTrajectoryRepository repository,
            AlignmentAssessmentRepository assessmentRepository,
            ObjectMapper objectMapper,
            @Value("${agent.rl.bailian.export-directory:tmp/agent-rl/bailian}") String exportDirectory,
            @Value("${agent.rl.bailian.minimum-reward:0.7}") double minimumReward,
            @Value("${agent.rl.bailian.validation-ratio:0.2}") double validationRatio,
            @Value("${agent.rl.bailian.expected-batch-size:64}") int expectedBatchSize,
            @Value("${agent.rl.bailian.approval-mode:AUTOMATED_ALIGNMENT}") String approvalMode) {
        this.repository = repository;
        this.assessmentRepository = assessmentRepository;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportDirectory).toAbsolutePath().normalize();
        this.defaultMinimumReward = minimumReward;
        this.defaultValidationRatio = validationRatio;
        this.defaultExpectedBatchSize = expectedBatchSize;
        this.defaultApprovalMode = parseApprovalMode(approvalMode);
    }

    /**
     * 兼容现有单元测试和外部调用；显式使用该构造器时维持原先的人工审批默认值。
     */
    public BailianRlDatasetService(
            AgentTrajectoryRepository repository,
            ObjectMapper objectMapper,
            String exportDirectory,
            double minimumReward,
            double validationRatio,
            int expectedBatchSize) {
        this.repository = repository;
        this.assessmentRepository = new InMemoryAlignmentAssessmentRepository();
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportDirectory).toAbsolutePath().normalize();
        this.defaultMinimumReward = minimumReward;
        this.defaultValidationRatio = validationRatio;
        this.defaultExpectedBatchSize = expectedBatchSize;
        this.defaultApprovalMode = TrainingApprovalMode.HUMAN_ONLY;
    }

    public DatasetExportResult exportDefault() {
        return export(new DatasetExportOptions(
                defaultMinimumReward, defaultValidationRatio, defaultExpectedBatchSize,
                defaultApprovalMode));
    }

    public DatasetReadiness readinessDefault() {
        return readiness(new DatasetExportOptions(
                defaultMinimumReward, defaultValidationRatio, defaultExpectedBatchSize,
                defaultApprovalMode));
    }

    public DatasetReadiness readiness(DatasetExportOptions options) {
        DatasetEvaluation evaluation = evaluate(options);
        return new DatasetReadiness(
                evaluation.totalTrajectoryCount(),
                evaluation.eligible().size(),
                evaluation.split().training().size(),
                evaluation.split().validation().size(),
                options.expectedBatchSize(),
                options.minimumReward(),
                options.validationRatio(),
                options.approvalMode(),
                evaluation.automatedApprovedCount(),
                evaluation.humanApprovedCount(),
                evaluation.averageAssessmentConfidence(),
                evaluation.averageJudgeAgreement(),
                evaluation.ready(),
                evaluation.warnings()
        );
    }

    public DatasetExportResult export(DatasetExportOptions options) {
        DatasetEvaluation evaluation = evaluate(options);
        List<AgentTrajectory> eligible = evaluation.eligible();
        Split split = evaluation.split();
        List<String> warnings = evaluation.warnings();
        boolean ready = evaluation.ready();

        try {
            Files.createDirectories(exportRoot);
            String packageName = DIRECTORY_TIME.format(Instant.now())
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
            Path packageDirectory = exportRoot.resolve(packageName);
            Files.createDirectory(packageDirectory);
            Path trainingFile = packageDirectory.resolve("rl-train.jsonl");
            Path validationFile = packageDirectory.resolve("rl-validation.jsonl");
            Path manifestFile = packageDirectory.resolve("manifest.json");

            writeJsonLines(trainingFile, split.training());
            writeJsonLines(validationFile, split.validation());

            DatasetManifest manifest = new DatasetManifest(
                    "bailian-agentic-rl-v2",
                    Instant.now(),
                    evaluation.totalTrajectoryCount(),
                    eligible.size(),
                    split.training().size(),
                    split.validation().size(),
                    options,
                    ready,
                    warnings
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
            return new DatasetExportResult(
                    packageDirectory.toString(),
                    trainingFile.toString(),
                    validationFile.toString(),
                    manifestFile.toString(),
                    split.training().size(),
                    split.validation().size(),
                    eligible.size(),
                    ready,
                    warnings
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to export Bailian RL dataset", exception);
        }
    }

    private DatasetEvaluation evaluate(DatasetExportOptions options) {
        validateOptions(options);
        List<AgentTrajectory> allTrajectories = repository.findAll();
        List<AgentTrajectory> eligible = selectEligible(allTrajectories, options);
        Split split = splitDeterministically(eligible, options.validationRatio());
        List<String> warnings = readinessWarnings(
                allTrajectories.size(), eligible.size(), split, options.expectedBatchSize(),
                options.approvalMode());
        boolean ready = split.training().size() > options.expectedBatchSize()
                && !split.validation().isEmpty();
        List<AutomatedAlignmentAssessment> assessments = assessmentRepository.findAll();
        long automatedApprovedCount = assessments.stream()
                .filter(AutomatedAlignmentAssessment::approvedPositive)
                .count();
        long humanApprovedCount = allTrajectories.stream()
                .filter(trajectory -> trajectory.userRating() != null
                        && trajectory.userRating() >= 4)
                .count();
        double averageAssessmentConfidence = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::confidence)
                .average().orElse(0);
        double averageJudgeAgreement = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::judgeAgreement)
                .average().orElse(0);
        return new DatasetEvaluation(
                allTrajectories.size(), eligible, split, ready, warnings,
                automatedApprovedCount, humanApprovedCount,
                round(averageAssessmentConfidence), round(averageJudgeAgreement));
    }

    private List<AgentTrajectory> selectEligible(List<AgentTrajectory> trajectories,
                                                  DatasetExportOptions options) {
        Map<String, AgentTrajectory> bestByQuestion = new LinkedHashMap<>();
        trajectories.stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status()))
                .filter(trajectory -> trajectory.reward() != null)
                .filter(trajectory -> trajectory.reward().total() >= options.minimumReward())
                .filter(trajectory -> hasText(trajectory.question()))
                .filter(trajectory -> hasText(trajectory.finalAnswer()))
                .filter(trajectory -> approvedForTraining(trajectory, options.approvalMode()))
                .sorted(Comparator
                        .comparingDouble((AgentTrajectory trajectory) -> trajectory.reward().total())
                        .reversed()
                        .thenComparing(AgentTrajectory::trajectoryId))
                .forEach(trajectory -> bestByQuestion.putIfAbsent(
                        normalizeQuestion(trajectory.question()), trajectory));

        return bestByQuestion.values().stream()
                .sorted(Comparator.comparing(trajectory -> stableHash(trajectory.trajectoryId())))
                .toList();
    }

    private Split splitDeterministically(List<AgentTrajectory> eligible, double validationRatio) {
        if (eligible.size() < 2) {
            return new Split(eligible, List.of());
        }
        int validationCount = Math.max(1, (int) Math.round(eligible.size() * validationRatio));
        validationCount = Math.min(validationCount, eligible.size() - 1);
        return new Split(
                List.copyOf(eligible.subList(validationCount, eligible.size())),
                List.copyOf(eligible.subList(0, validationCount))
        );
    }

    private void writeJsonLines(Path target, List<AgentTrajectory> trajectories) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            for (AgentTrajectory trajectory : trajectories) {
                writer.write(objectMapper.writeValueAsString(toTrainingSample(trajectory)));
                writer.newLine();
            }
        }
    }

    private Map<String, Object> toTrainingSample(AgentTrajectory trajectory) {
        Map<String, Object> rolloutExtra = new LinkedHashMap<>();
        rolloutExtra.put("solution", trajectory.finalAnswer());
        rolloutExtra.put("source_trajectory_id", trajectory.trajectoryId());
        rolloutExtra.put("source_policy_version", trajectory.policyVersion());
        rolloutExtra.put("source_reward", trajectory.reward().total());
        rolloutExtra.put("source_reward_dimensions", trajectory.reward());
        rolloutExtra.put("retrieved_document_ids", trajectory.retrievedDocumentIds());
        rolloutExtra.put("human_rating", trajectory.userRating());
        rolloutExtra.put("reward_schema_version", "human-light-rlvr-v2");
        rolloutExtra.put("verification_contract", Map.of(
                "minimum_answer_chars", 80,
                "maximum_answer_chars", 6000,
                "citation_required", !trajectory.retrievedDocumentIds().isEmpty()
        ));
        assessmentRepository.findByTrajectoryId(trajectory.trajectoryId())
                .ifPresent(assessment -> {
                    rolloutExtra.put("alignment_label", assessment.supervisionLabel());
                    rolloutExtra.put("alignment_reward", assessment.totalReward());
                    rolloutExtra.put("alignment_confidence", assessment.confidence());
                    rolloutExtra.put("judge_agreement", assessment.judgeAgreement());
                    rolloutExtra.put("judge_count", assessment.judgeCount());
                });

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("messages", List.of(Map.of(
                "role", "user",
                "content", trajectory.question().trim()
        )));
        sample.put("rollout_extra", rolloutExtra);
        return sample;
    }

    private List<String> readinessWarnings(int totalCount,
                                           int eligibleCount,
                                           Split split,
                                           int expectedBatchSize,
                                           TrainingApprovalMode approvalMode) {
        List<String> warnings = new ArrayList<>();
        if (eligibleCount < totalCount) {
            warnings.add((totalCount - eligibleCount)
                    + " 条轨迹因状态、奖励、重复问题或人工审核要求被排除");
        }
        if (eligibleCount == 0 && approvalMode == TrainingApprovalMode.HUMAN_ONLY) {
            warnings.add("没有符合条件的人工审核轨迹；请先收集 4～5 星反馈");
        }
        if (eligibleCount == 0 && approvalMode == TrainingApprovalMode.AUTOMATED_ALIGNMENT) {
            warnings.add("没有通过 RLVR 与多 AI Judge 高置信评审的轨迹；"
                    + "请先运行自动 Alignment 评测");
        }
        if (split.validation().isEmpty()) {
            warnings.add("验证集为空；至少需要 2 条合格且不重复的轨迹");
        }
        if (split.training().size() <= expectedBatchSize) {
            warnings.add("训练集必须大于 batch_size=" + expectedBatchSize
                    + "，当前为 " + split.training().size());
        }
        if (eligibleCount < 50 && approvalMode == TrainingApprovalMode.HUMAN_ONLY) {
            warnings.add("建议先准备至少 50 条人工审核样本用于奖励函数回归验证");
        }
        if (eligibleCount < 50 && approvalMode == TrainingApprovalMode.AUTOMATED_ALIGNMENT) {
            warnings.add("建议先准备至少 50 条高置信自动评审样本，并保留独立固定评测集");
        }
        return List.copyOf(warnings);
    }

    private void validateOptions(DatasetExportOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (options.minimumReward() < 0 || options.minimumReward() > 1) {
            throw new IllegalArgumentException("minimumReward must be between 0 and 1");
        }
        if (options.validationRatio() <= 0 || options.validationRatio() > 0.5) {
            throw new IllegalArgumentException("validationRatio must be greater than 0 and at most 0.5");
        }
        if (options.expectedBatchSize() < 1) {
            throw new IllegalArgumentException("expectedBatchSize must be positive");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean approvedForTraining(AgentTrajectory trajectory,
                                        TrainingApprovalMode approvalMode) {
        if (approvalMode == TrainingApprovalMode.HUMAN_ONLY) {
            return trajectory.userRating() != null && trajectory.userRating() >= 4;
        }
        return assessmentRepository.findByTrajectoryId(trajectory.trajectoryId())
                .filter(AutomatedAlignmentAssessment::approvedPositive)
                .filter(assessment -> assessment.confidence() > 0)
                .isPresent();
    }

    private TrainingApprovalMode parseApprovalMode(String value) {
        try {
            return TrainingApprovalMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Unsupported Bailian approval mode: " + value, exception);
        }
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private String normalizeQuestion(String question) {
        return question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record Split(List<AgentTrajectory> training, List<AgentTrajectory> validation) {
    }

    private record DatasetEvaluation(
            int totalTrajectoryCount,
            List<AgentTrajectory> eligible,
            Split split,
            boolean ready,
            List<String> warnings,
            long automatedApprovedCount,
            long humanApprovedCount,
            double averageAssessmentConfidence,
            double averageJudgeAgreement
    ) {
    }

    public record DatasetExportOptions(
            double minimumReward,
            double validationRatio,
            int expectedBatchSize,
            TrainingApprovalMode approvalMode
    ) {
        public DatasetExportOptions(double minimumReward,
                                    double validationRatio,
                                    int expectedBatchSize,
                                    boolean requireHumanApproval) {
            this(minimumReward, validationRatio, expectedBatchSize,
                    requireHumanApproval
                            ? TrainingApprovalMode.HUMAN_ONLY
                            : TrainingApprovalMode.AUTOMATED_ALIGNMENT);
        }

        public boolean requireHumanApproval() {
            return approvalMode == TrainingApprovalMode.HUMAN_ONLY;
        }
    }

    public record DatasetExportResult(
            String packageDirectory,
            String trainingFile,
            String validationFile,
            String manifestFile,
            int trainingCount,
            int validationCount,
            int eligibleCount,
            boolean readyForCloudSubmission,
            List<String> warnings
    ) {
    }

    public record DatasetReadiness(
            int totalTrajectoryCount,
            int eligibleTrajectoryCount,
            int trainingCount,
            int validationCount,
            int expectedBatchSize,
            double minimumReward,
            double validationRatio,
            TrainingApprovalMode approvalMode,
            long automatedApprovedCount,
            long humanApprovedCount,
            double averageAssessmentConfidence,
            double averageJudgeAgreement,
            boolean readyForCloudSubmission,
            List<String> warnings
    ) {
    }

    public record DatasetManifest(
            String schemaVersion,
            Instant generatedAt,
            int totalTrajectoryCount,
            int eligibleTrajectoryCount,
            int trainingCount,
            int validationCount,
            DatasetExportOptions options,
            boolean readyForCloudSubmission,
            List<String> warnings
    ) {
    }

    public enum TrainingApprovalMode {
        HUMAN_ONLY,
        AUTOMATED_ALIGNMENT
    }
}
