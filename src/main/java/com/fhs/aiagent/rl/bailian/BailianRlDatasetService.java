package com.fhs.aiagent.rl.bailian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentTrajectory;
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
 * <p>默认只采用获得 4～5 星人工反馈的轨迹，避免把模型自己的错误回答再次当作标准答案。</p>
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

    public BailianRlDatasetService(
            AgentTrajectoryRepository repository,
            ObjectMapper objectMapper,
            @Value("${agent.rl.bailian.export-directory:tmp/agent-rl/bailian}") String exportDirectory,
            @Value("${agent.rl.bailian.minimum-reward:0.7}") double minimumReward,
            @Value("${agent.rl.bailian.validation-ratio:0.2}") double validationRatio,
            @Value("${agent.rl.bailian.expected-batch-size:64}") int expectedBatchSize) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportDirectory).toAbsolutePath().normalize();
        this.defaultMinimumReward = minimumReward;
        this.defaultValidationRatio = validationRatio;
        this.defaultExpectedBatchSize = expectedBatchSize;
    }

    public DatasetExportResult exportDefault() {
        return export(new DatasetExportOptions(
                defaultMinimumReward, defaultValidationRatio, defaultExpectedBatchSize, true));
    }

    public DatasetReadiness readinessDefault() {
        return readiness(new DatasetExportOptions(
                defaultMinimumReward, defaultValidationRatio, defaultExpectedBatchSize, true));
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
                options.requireHumanApproval(),
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
                    "bailian-agentic-rl-v1",
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
                options.requireHumanApproval());
        boolean ready = split.training().size() > options.expectedBatchSize()
                && !split.validation().isEmpty();
        return new DatasetEvaluation(
                allTrajectories.size(), eligible, split, ready, warnings);
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
                .filter(trajectory -> !options.requireHumanApproval()
                        || trajectory.userRating() != null && trajectory.userRating() >= 4)
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
                                           boolean requireHumanApproval) {
        List<String> warnings = new ArrayList<>();
        if (eligibleCount < totalCount) {
            warnings.add((totalCount - eligibleCount)
                    + " 条轨迹因状态、奖励、重复问题或人工审核要求被排除");
        }
        if (eligibleCount == 0 && requireHumanApproval) {
            warnings.add("没有符合条件的人工审核轨迹；请先收集 4～5 星反馈");
        }
        if (split.validation().isEmpty()) {
            warnings.add("验证集为空；至少需要 2 条合格且不重复的轨迹");
        }
        if (split.training().size() <= expectedBatchSize) {
            warnings.add("训练集必须大于 batch_size=" + expectedBatchSize
                    + "，当前为 " + split.training().size());
        }
        if (eligibleCount < 50) {
            warnings.add("建议先准备至少 50 条人工审核样本用于奖励函数回归验证");
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
            List<String> warnings
    ) {
    }

    public record DatasetExportOptions(
            double minimumReward,
            double validationRatio,
            int expectedBatchSize,
            boolean requireHumanApproval
    ) {
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
            boolean requireHumanApproval,
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
}
