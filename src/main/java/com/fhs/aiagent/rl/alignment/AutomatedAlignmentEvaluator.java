package com.fhs.aiagent.rl.alignment;

import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.RewardBreakdown;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AutomatedAlignmentEvaluator {

    private final int minimumJudgeCount;

    private final double minimumAgreement;

    private final double minimumConfidence;

    private final double positiveRewardThreshold;

    private final double negativeRewardThreshold;

    private final double minimumGroundingReward;

    public AutomatedAlignmentEvaluator(
            @Value("${agent.rl.alignment.minimum-judge-count:4}") int minimumJudgeCount,
            @Value("${agent.rl.alignment.minimum-agreement:0.7}") double minimumAgreement,
            @Value("${agent.rl.alignment.minimum-confidence:0.72}") double minimumConfidence,
            @Value("${agent.rl.alignment.positive-reward-threshold:0.75}") double positiveRewardThreshold,
            @Value("${agent.rl.alignment.negative-reward-threshold:0.35}") double negativeRewardThreshold,
            @Value("${agent.rl.alignment.minimum-grounding-reward:0.7}") double minimumGroundingReward) {
        if (minimumJudgeCount < 2) {
            throw new IllegalArgumentException("minimumJudgeCount must be at least 2");
        }
        this.minimumJudgeCount = minimumJudgeCount;
        this.minimumAgreement = probability(minimumAgreement, "minimumAgreement");
        this.minimumConfidence = probability(minimumConfidence, "minimumConfidence");
        this.positiveRewardThreshold = probability(positiveRewardThreshold, "positiveRewardThreshold");
        this.negativeRewardThreshold = probability(negativeRewardThreshold, "negativeRewardThreshold");
        this.minimumGroundingReward = probability(minimumGroundingReward, "minimumGroundingReward");
        if (negativeRewardThreshold >= positiveRewardThreshold) {
            throw new IllegalArgumentException(
                    "negativeRewardThreshold must be lower than positiveRewardThreshold");
        }
    }

    public AutomatedAlignmentAssessment evaluate(AgentTrajectory trajectory,
                                                  List<AiJudgeScore> rawScores,
                                                  boolean evaluationOnly) {
        if (trajectory == null) {
            throw new IllegalArgumentException("trajectory must not be null");
        }
        List<AiJudgeScore> scores = normalizeScores(rawScores);
        List<String> reasons = new ArrayList<>();
        double verifierReward = verifierReward(trajectory.reward());
        double aiReward = weightedJudgeReward(scores);
        double agreement = judgeAgreement(scores, aiReward);
        double confidence = combinedConfidence(scores, agreement);
        double totalReward = round(0.45 * verifierReward + 0.55 * aiReward);
        boolean hardChecksPassed = hardChecksPassed(trajectory, reasons);

        SupervisionLabel label;
        TrainingDecision decision;
        if (evaluationOnly) {
            label = SupervisionLabel.EVALUATION_ONLY;
            decision = TrainingDecision.EXCLUDED;
            reasons.add("固定评测数据与训练数据严格隔离");
        } else if (!hardChecksPassed) {
            label = SupervisionLabel.REJECTED;
            decision = TrainingDecision.EXCLUDED;
        } else if (trajectory.userRating() != null) {
            label = SupervisionLabel.HUMAN_LABELED;
            decision = humanDecision(trajectory.userRating());
            confidence = 1.0;
            reasons.add("使用显式人类反馈作为高置信锚点");
        } else if (scores.size() < minimumJudgeCount) {
            label = SupervisionLabel.UNLABELED;
            decision = TrainingDecision.HOLDOUT;
            reasons.add("有效 AI Judge 数量不足: " + scores.size() + "/" + minimumJudgeCount);
        } else if (agreement < minimumAgreement) {
            label = SupervisionLabel.UNLABELED;
            decision = TrainingDecision.HOLDOUT;
            reasons.add("AI Judge 分歧过大");
        } else if (confidence < minimumConfidence) {
            label = SupervisionLabel.UNLABELED;
            decision = TrainingDecision.HOLDOUT;
            reasons.add("自动评分置信度不足");
        } else if (totalReward >= positiveRewardThreshold) {
            label = SupervisionLabel.PSEUDO_LABELED;
            decision = TrainingDecision.POSITIVE;
            reasons.add("通过硬验证且 AI Judge 高置信一致");
        } else if (totalReward <= negativeRewardThreshold) {
            label = SupervisionLabel.PSEUDO_LABELED;
            decision = TrainingDecision.NEGATIVE;
            reasons.add("AI Judge 高置信判定为负样本");
        } else {
            label = SupervisionLabel.UNLABELED;
            decision = TrainingDecision.HOLDOUT;
            reasons.add("奖励位于正负阈值之间，暂不用于训练");
        }

        return new AutomatedAlignmentAssessment(
                trajectory.trajectoryId(),
                fingerprint(trajectory.question()),
                trajectory.policyVersion(),
                Instant.now(),
                label,
                decision,
                round(verifierReward),
                round(aiReward),
                totalReward,
                round(confidence),
                round(agreement),
                scores.size(),
                scores,
                List.copyOf(reasons),
                evaluationOnly
        );
    }

    private boolean hardChecksPassed(AgentTrajectory trajectory, List<String> reasons) {
        boolean passed = true;
        if (!"COMPLETED".equals(trajectory.status()) || hasText(trajectory.error())) {
            reasons.add("轨迹未正常完成");
            passed = false;
        }
        if (!hasText(trajectory.question()) || !hasText(trajectory.finalAnswer())) {
            reasons.add("问题或最终答案为空");
            passed = false;
        }
        if (trajectory.reward() == null) {
            reasons.add("缺少 RLVR 奖励");
            return false;
        }
        if (trajectory.reward().groundingQuality() < minimumGroundingReward) {
            reasons.add("证据忠实度未达到硬门槛");
            passed = false;
        }
        if (trajectory.reward().taskCompletionQuality() <= 0) {
            reasons.add("任务完成度硬验证失败");
            passed = false;
        }
        return passed;
    }

    private List<AiJudgeScore> normalizeScores(List<AiJudgeScore> rawScores) {
        if (rawScores == null || rawScores.isEmpty()) {
            return List.of();
        }
        Set<String> identities = new HashSet<>();
        List<AiJudgeScore> normalized = new ArrayList<>();
        for (AiJudgeScore score : rawScores) {
            if (score == null || score.dimension() == null || !hasText(score.judgeId())) {
                continue;
            }
            String identity = score.judgeId().trim() + ":" + score.dimension();
            if (!identities.add(identity)) {
                continue;
            }
            normalized.add(new AiJudgeScore(
                    score.judgeId().trim(),
                    score.dimension(),
                    clamp(score.score()),
                    clamp(score.confidence()),
                    score.rationale() == null ? "" : score.rationale().trim()
            ));
        }
        return List.copyOf(normalized);
    }

    private double verifierReward(RewardBreakdown reward) {
        if (reward == null) {
            return 0;
        }
        return clamp(
                0.20 * reward.retrievalQuality()
                        + 0.25 * reward.groundingQuality()
                        + 0.15 * reward.convergenceQuality()
                        + 0.25 * reward.taskCompletionQuality()
                        + 0.05 * reward.collaborationQuality()
                        + 0.10 * reward.efficiency()
        );
    }

    private double weightedJudgeReward(List<AiJudgeScore> scores) {
        double weightedTotal = 0;
        double totalWeight = 0;
        for (AiJudgeScore score : scores) {
            double weight = Math.max(0.05, score.confidence());
            weightedTotal += score.score() * weight;
            totalWeight += weight;
        }
        return totalWeight == 0 ? 0 : weightedTotal / totalWeight;
    }

    private double judgeAgreement(List<AiJudgeScore> scores, double mean) {
        if (scores.size() < 2) {
            return 0;
        }
        double meanAbsoluteDeviation = scores.stream()
                .mapToDouble(score -> Math.abs(score.score() - mean))
                .average()
                .orElse(0);
        return clamp(1.0 - 2.0 * meanAbsoluteDeviation);
    }

    private double combinedConfidence(List<AiJudgeScore> scores, double agreement) {
        if (scores.isEmpty()) {
            return 0;
        }
        double meanConfidence = scores.stream()
                .mapToDouble(AiJudgeScore::confidence)
                .average()
                .orElse(0);
        long dimensionCount = scores.stream().map(AiJudgeScore::dimension).distinct().count();
        double coverage = Math.min(1.0, dimensionCount / (double) minimumJudgeCount);
        return clamp(meanConfidence * agreement * coverage);
    }

    private TrainingDecision humanDecision(int rating) {
        if (rating >= 4) {
            return TrainingDecision.POSITIVE;
        }
        if (rating <= 2) {
            return TrainingDecision.NEGATIVE;
        }
        return TrainingDecision.HOLDOUT;
    }

    private String fingerprint(String question) {
        String normalized = question == null
                ? ""
                : question.trim().replaceAll("\\s+", " ").toLowerCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double probability(double value, String name) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
        return value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
