package com.fhs.aiagent.rl.bailian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.alignment.AlignmentAssessmentRepository;
import com.fhs.aiagent.rl.alignment.AutomatedAlignmentAssessment;
import com.fhs.aiagent.rl.alignment.InMemoryAlignmentAssessmentRepository;
import com.fhs.aiagent.rl.alignment.RewardTrajectoryObservation;
import com.fhs.aiagent.rl.alignment.TrajectoryGuidedSampleSelector;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.AgentStepType;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private final TrainingDatasetProfile defaultDatasetProfile;

    private final TrajectoryGuidedSampleSelector trajectorySelector;

    private final TrajectoryGuidedSampleSelector.SelectionOptions
            trajectorySelectionOptions;

    @Autowired
    public BailianRlDatasetService(
            AgentTrajectoryRepository repository,
            AlignmentAssessmentRepository assessmentRepository,
            ObjectMapper objectMapper,
            @Value("${agent.rl.bailian.export-directory:tmp/agent-rl/bailian}") String exportDirectory,
            @Value("${agent.rl.bailian.minimum-reward:0.7}") double minimumReward,
            @Value("${agent.rl.bailian.validation-ratio:0.2}") double validationRatio,
            @Value("${agent.rl.bailian.expected-batch-size:64}") int expectedBatchSize,
            @Value("${agent.rl.bailian.approval-mode:AUTOMATED_ALIGNMENT}")
            String approvalMode,
            @Value("${agent.rl.bailian.dataset-profile:FULL_TRAJECTORY_GUIDED}")
            String datasetProfile,
            @Value("${agent.rl.bailian.trajectory-selection.minimum-rounds:2}")
            int minimumRounds,
            @Value("${agent.rl.bailian.trajectory-selection.top-ratio:0.3}")
            double topRatio,
            @Value("${agent.rl.bailian.trajectory-selection.similarity-threshold:0.8}")
            double similarityThreshold,
            @Value("${agent.rl.bailian.trajectory-selection.minimum-confidence:0.72}")
            double minimumConfidence,
            TrajectoryGuidedSampleSelector trajectorySelector) {
        this.repository = repository;
        this.assessmentRepository = assessmentRepository;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportDirectory).toAbsolutePath().normalize();
        this.defaultMinimumReward = minimumReward;
        this.defaultValidationRatio = validationRatio;
        this.defaultExpectedBatchSize = expectedBatchSize;
        this.defaultApprovalMode = parseApprovalMode(approvalMode);
        this.defaultDatasetProfile = parseDatasetProfile(datasetProfile);
        this.trajectorySelector = trajectorySelector;
        this.trajectorySelectionOptions =
                new TrajectoryGuidedSampleSelector.SelectionOptions(
                        minimumRounds,
                        topRatio,
                        similarityThreshold,
                        minimumConfidence
                );
    }

    public BailianRlDatasetService(
            AgentTrajectoryRepository repository,
            AlignmentAssessmentRepository assessmentRepository,
            ObjectMapper objectMapper,
            String exportDirectory,
            double minimumReward,
            double validationRatio,
            int expectedBatchSize,
            String approvalMode) {
        this.repository = repository;
        this.assessmentRepository = assessmentRepository;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportDirectory).toAbsolutePath().normalize();
        this.defaultMinimumReward = minimumReward;
        this.defaultValidationRatio = validationRatio;
        this.defaultExpectedBatchSize = expectedBatchSize;
        this.defaultApprovalMode = parseApprovalMode(approvalMode);
        this.defaultDatasetProfile =
                TrainingDatasetProfile.RLVR_RLAIF;
        this.trajectorySelector = new TrajectoryGuidedSampleSelector();
        this.trajectorySelectionOptions =
                new TrajectoryGuidedSampleSelector.SelectionOptions(
                        2, 0.3, 0.8, 0.72);
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
        this.defaultDatasetProfile = TrainingDatasetProfile.HUMAN_APPROVED;
        this.trajectorySelector = new TrajectoryGuidedSampleSelector();
        this.trajectorySelectionOptions =
                new TrajectoryGuidedSampleSelector.SelectionOptions(
                        2, 0.3, 0.8, 0.72);
    }

    public DatasetExportResult exportDefault() {
        return export(new DatasetExportOptions(
                defaultMinimumReward, defaultValidationRatio, defaultExpectedBatchSize,
                defaultApprovalMode, defaultDatasetProfile));
    }

    public DatasetReadiness readinessDefault() {
        return readiness(new DatasetExportOptions(
                defaultMinimumReward, defaultValidationRatio, defaultExpectedBatchSize,
                defaultApprovalMode, defaultDatasetProfile));
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
                options.datasetProfile(),
                evaluation.automatedApprovedCount(),
                evaluation.humanApprovedCount(),
                evaluation.averageAssessmentConfidence(),
                evaluation.averageJudgeAgreement(),
                evaluation.selectionReport().selectedSampleCount(),
                evaluation.selectionReport().unscorableSampleCount(),
                evaluation.selectionReport().selectionRate(),
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
                    evaluation.selectionReport(),
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
        SelectionOutcome selection = selectEligible(allTrajectories, options);
        List<AgentTrajectory> eligible = selection.eligible();
        Split split = splitDeterministically(eligible, options.validationRatio());
        List<String> warnings = readinessWarnings(
                allTrajectories.size(), eligible.size(), split, options.expectedBatchSize(),
                options.datasetProfile());
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
                round(averageAssessmentConfidence),
                round(averageJudgeAgreement),
                selection.report());
    }

    private SelectionOutcome selectEligible(
            List<AgentTrajectory> trajectories,
            DatasetExportOptions options) {
        TrajectoryGuidedSampleSelector.SelectionReport report =
                options.datasetProfile()
                        == TrainingDatasetProfile.FULL_TRAJECTORY_GUIDED
                        ? trajectorySelector.select(
                                trajectoryObservations(trajectories),
                                trajectorySelectionOptions)
                        : trajectorySelector.select(
                                List.of(), trajectorySelectionOptions);
        Set<String> trajectorySelectedQuestions =
                options.datasetProfile()
                        == TrainingDatasetProfile.FULL_TRAJECTORY_GUIDED
                        ? trajectorySelectedQuestions(trajectories, report)
                        : Set.of();
        Map<String, AgentTrajectory> bestByQuestion = new LinkedHashMap<>();
        trajectories.stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status()))
                .filter(trajectory -> trajectory.reward() != null)
                .filter(trajectory -> trajectory.reward().total() >= options.minimumReward())
                .filter(trajectory -> hasText(trajectory.question()))
                .filter(trajectory -> hasText(trajectory.finalAnswer()))
                .filter(trajectory -> approvedForTraining(
                        trajectory, options))
                .filter(trajectory -> options.datasetProfile()
                        != TrainingDatasetProfile.FULL_TRAJECTORY_GUIDED
                        || trajectorySelectedQuestions.contains(
                                sampleId(trajectory.question())))
                .sorted(Comparator
                        .comparingDouble((AgentTrajectory trajectory) -> trajectory.reward().total())
                        .reversed()
                        .thenComparing(AgentTrajectory::trajectoryId))
                .forEach(trajectory -> bestByQuestion.putIfAbsent(
                        normalizeQuestion(trajectory.question()), trajectory));

        List<AgentTrajectory> eligible = bestByQuestion.values().stream()
                .sorted(Comparator.comparing(trajectory -> stableHash(trajectory.trajectoryId())))
                .toList();
        return new SelectionOutcome(eligible, report);
    }

    private Set<String> trajectorySelectedQuestions(
            List<AgentTrajectory> trajectories,
            TrajectoryGuidedSampleSelector.SelectionReport report) {
        Set<String> selected = report.decisions().stream()
                .filter(TrajectoryGuidedSampleSelector.CandidateDecision::selected)
                .map(TrajectoryGuidedSampleSelector.CandidateDecision::sampleId)
                .collect(Collectors.toSet());
        trajectories.stream()
                .filter(trajectory -> trajectory.userRating() != null
                        && trajectory.userRating() >= 4)
                .map(trajectory -> sampleId(trajectory.question()))
                .forEach(selected::add);
        return Set.copyOf(selected);
    }

    private List<RewardTrajectoryObservation> trajectoryObservations(
            List<AgentTrajectory> trajectories) {
        Map<String, List<AgentTrajectory>> byQuestion = trajectories.stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status()))
                .filter(trajectory -> trajectory.reward() != null)
                .filter(trajectory -> hasText(trajectory.question()))
                .collect(Collectors.groupingBy(
                        trajectory -> sampleId(trajectory.question()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<RewardTrajectoryObservation> observations = new ArrayList<>();
        byQuestion.forEach((sampleId, sample) -> {
            List<AgentTrajectory> ordered = sample.stream()
                    .sorted(Comparator
                            .comparing(
                                    AgentTrajectory::completedAt,
                                    Comparator.nullsLast(
                                            Comparator.naturalOrder()))
                            .thenComparing(AgentTrajectory::trajectoryId))
                    .toList();
            boolean labeledAnchor = ordered.stream()
                    .anyMatch(trajectory -> trajectory.userRating() != null
                            && trajectory.userRating() >= 4);
            for (int round = 0; round < ordered.size(); round++) {
                AgentTrajectory trajectory = ordered.get(round);
                observations.add(new RewardTrajectoryObservation(
                        sampleId,
                        taskGroup(trajectory),
                        trajectory.policyVersion(),
                        round,
                        trajectory.reward().total(),
                        assessmentRepository.findByTrajectoryId(
                                        trajectory.trajectoryId())
                                .map(AutomatedAlignmentAssessment::confidence)
                                .orElse(labeledAnchor ? 1.0 : 0.0),
                        labeledAnchor,
                        trajectory.chatId() != null
                                && trajectory.chatId().startsWith("eval-")
                ));
            }
        });
        return List.copyOf(observations);
    }

    private String taskGroup(AgentTrajectory trajectory) {
        return (trajectory.steps() == null
                ? List.<com.fhs.aiagent.rl.model.AgentStep>of()
                : trajectory.steps()).stream()
                .filter(step -> step.type() == AgentStepType.ROUTE)
                .findFirst()
                .map(step -> {
                    Object domains = step.output().get("selectedDomains");
                    if (domains instanceof List<?> list && !list.isEmpty()) {
                        return list.stream()
                                .map(String::valueOf)
                                .sorted()
                                .collect(Collectors.joining("+"));
                    }
                    return String.valueOf(
                            step.output().getOrDefault("mode", "default"));
                })
                .filter(this::hasText)
                .orElse("default");
    }

    private String sampleId(String question) {
        return stableHash(normalizeQuestion(question));
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
                                           TrainingDatasetProfile datasetProfile) {
        List<String> warnings = new ArrayList<>();
        if (eligibleCount < totalCount) {
            warnings.add((totalCount - eligibleCount)
                    + " 条轨迹因状态、奖励、重复问题或人工审核要求被排除");
        }
        if (eligibleCount == 0
                && datasetProfile == TrainingDatasetProfile.HUMAN_APPROVED) {
            warnings.add("没有符合条件的人工审核轨迹；请先收集 4～5 星反馈");
        }
        if (eligibleCount == 0
                && datasetProfile == TrainingDatasetProfile.RLVR_RLAIF) {
            warnings.add("没有通过 RLVR 与多 AI Judge 高置信评审的轨迹；"
                    + "请先运行自动 Alignment 评测");
        }
        if (eligibleCount == 0
                && datasetProfile
                == TrainingDatasetProfile.FULL_TRAJECTORY_GUIDED) {
            warnings.add("没有同时通过 AI Judge 和奖励轨迹筛选的样本；"
                    + "需要同任务多轮轨迹及少量人工锚点");
        }
        if (split.validation().isEmpty()) {
            warnings.add("验证集为空；至少需要 2 条合格且不重复的轨迹");
        }
        if (split.training().size() <= expectedBatchSize) {
            warnings.add("训练集必须大于 batch_size=" + expectedBatchSize
                    + "，当前为 " + split.training().size());
        }
        if (eligibleCount < 50
                && datasetProfile == TrainingDatasetProfile.HUMAN_APPROVED) {
            warnings.add("建议先准备至少 50 条人工审核样本用于奖励函数回归验证");
        }
        if (eligibleCount < 50
                && datasetProfile != TrainingDatasetProfile.HUMAN_APPROVED) {
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
        if (options.approvalMode() == null || options.datasetProfile() == null) {
            throw new IllegalArgumentException(
                    "approvalMode and datasetProfile must not be null");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean approvedForTraining(
            AgentTrajectory trajectory,
            DatasetExportOptions options) {
        return switch (options.datasetProfile()) {
            case HUMAN_APPROVED -> trajectory.userRating() != null
                    && trajectory.userRating() >= 4;
            case RLVR_ONLY -> true;
            case RLVR_RLAIF, FULL_TRAJECTORY_GUIDED ->
                    assessmentRepository.findByTrajectoryId(
                                    trajectory.trajectoryId())
                            .filter(AutomatedAlignmentAssessment::approvedPositive)
                            .filter(assessment -> assessment.confidence() > 0)
                            .isPresent();
        };
    }

    private TrainingApprovalMode parseApprovalMode(String value) {
        try {
            return TrainingApprovalMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Unsupported Bailian approval mode: " + value, exception);
        }
    }

    private TrainingDatasetProfile parseDatasetProfile(String value) {
        try {
            return TrainingDatasetProfile.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Unsupported Bailian dataset profile: " + value,
                    exception
            );
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
            double averageJudgeAgreement,
            TrajectoryGuidedSampleSelector.SelectionReport selectionReport
    ) {
    }

    private record SelectionOutcome(
            List<AgentTrajectory> eligible,
            TrajectoryGuidedSampleSelector.SelectionReport report
    ) {
    }

    public record DatasetExportOptions(
            double minimumReward,
            double validationRatio,
            int expectedBatchSize,
            TrainingApprovalMode approvalMode,
            TrainingDatasetProfile datasetProfile
    ) {
        public DatasetExportOptions(double minimumReward,
                                    double validationRatio,
                                    int expectedBatchSize,
                                    boolean requireHumanApproval) {
            this(minimumReward, validationRatio, expectedBatchSize,
                    requireHumanApproval
                            ? TrainingApprovalMode.HUMAN_ONLY
                            : TrainingApprovalMode.AUTOMATED_ALIGNMENT,
                    requireHumanApproval
                            ? TrainingDatasetProfile.HUMAN_APPROVED
                            : TrainingDatasetProfile.RLVR_RLAIF);
        }

        public DatasetExportOptions(double minimumReward,
                                    double validationRatio,
                                    int expectedBatchSize,
                                    TrainingApprovalMode approvalMode) {
            this(
                    minimumReward,
                    validationRatio,
                    expectedBatchSize,
                    approvalMode,
                    approvalMode == TrainingApprovalMode.HUMAN_ONLY
                            ? TrainingDatasetProfile.HUMAN_APPROVED
                            : TrainingDatasetProfile.RLVR_RLAIF
            );
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
            TrainingDatasetProfile datasetProfile,
            long automatedApprovedCount,
            long humanApprovedCount,
            double averageAssessmentConfidence,
            double averageJudgeAgreement,
            int trajectorySelectedCount,
            int trajectoryUnscorableCount,
            double trajectorySelectionRate,
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
            TrajectoryGuidedSampleSelector.SelectionReport selectionReport,
            boolean readyForCloudSubmission,
            List<String> warnings
    ) {
    }

    public enum TrainingApprovalMode {
        HUMAN_ONLY,
        AUTOMATED_ALIGNMENT
    }

    public enum TrainingDatasetProfile {
        HUMAN_APPROVED,
        RLVR_ONLY,
        RLVR_RLAIF,
        FULL_TRAJECTORY_GUIDED
    }
}
