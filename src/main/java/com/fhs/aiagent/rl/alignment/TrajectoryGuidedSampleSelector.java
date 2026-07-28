package com.fhs.aiagent.rl.alignment;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TRAPO-inspired selector. It compares reward evolution instead of trusting one
 * pseudo label or one AI Judge call.
 */
@Component
public class TrajectoryGuidedSampleSelector {

    public SelectionReport select(List<RewardTrajectoryObservation> observations,
                                  SelectionOptions options) {
        validateOptions(options);
        List<RewardTrajectoryObservation> safeObservations =
                observations == null ? List.of() : observations.stream()
                        .filter(this::valid)
                        .toList();
        Set<String> evaluationIds = safeObservations.stream()
                .filter(RewardTrajectoryObservation::evaluationOnly)
                .map(RewardTrajectoryObservation::sampleId)
                .collect(Collectors.toSet());
        Map<String, List<RewardTrajectoryObservation>> bySample = safeObservations.stream()
                .filter(observation -> !evaluationIds.contains(observation.sampleId()))
                .collect(Collectors.groupingBy(
                        RewardTrajectoryObservation::sampleId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, List<List<Double>>> anchorTrajectoriesByGroup = new HashMap<>();
        int anchorSampleCount = 0;
        for (Map.Entry<String, List<RewardTrajectoryObservation>> entry : bySample.entrySet()) {
            List<RewardTrajectoryObservation> sample = sorted(entry.getValue());
            if (!sample.get(0).labeledAnchor()) {
                continue;
            }
            List<Double> trajectory = rewards(sample);
            if (trajectory.size() >= options.minimumRounds()) {
                anchorTrajectoriesByGroup
                        .computeIfAbsent(taskGroup(sample.get(0)), ignored -> new ArrayList<>())
                        .add(trajectory);
                anchorSampleCount++;
            }
        }

        List<CandidateScore> candidates = new ArrayList<>();
        int unscorable = 0;
        for (Map.Entry<String, List<RewardTrajectoryObservation>> entry : bySample.entrySet()) {
            List<RewardTrajectoryObservation> sample = sorted(entry.getValue());
            if (sample.get(0).labeledAnchor()) {
                continue;
            }
            String group = taskGroup(sample.get(0));
            List<List<Double>> anchors = anchorTrajectoriesByGroup.getOrDefault(group, List.of());
            if (sample.size() < options.minimumRounds() || anchors.isEmpty()) {
                candidates.add(CandidateScore.unscorable(
                        entry.getKey(), group, averageConfidence(sample),
                        "缺少足够轮次或同任务锚点"));
                unscorable++;
                continue;
            }
            int length = Math.min(
                    sample.size(),
                    anchors.stream().mapToInt(List::size).min().orElse(0));
            if (length < options.minimumRounds()) {
                candidates.add(CandidateScore.unscorable(
                        entry.getKey(), group, averageConfidence(sample),
                        "可对齐的奖励轨迹过短"));
                unscorable++;
                continue;
            }
            List<Double> candidateTrajectory = rewards(sample).subList(0, length);
            List<Double> anchorMean = meanTrajectory(anchors, length);
            double similarity = normalizedCosine(anchorMean, candidateTrajectory);
            if (Double.isNaN(similarity)) {
                candidates.add(CandidateScore.unscorable(
                        entry.getKey(), group, averageConfidence(sample),
                        "奖励轨迹无变化，无法计算趋势相似度"));
                unscorable++;
                continue;
            }
            candidates.add(new CandidateScore(
                    entry.getKey(),
                    group,
                    round(similarity),
                    round(averageConfidence(sample)),
                    false,
                    ""
            ));
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        Map<String, List<CandidateScore>> scorableByGroup = candidates.stream()
                .filter(candidate -> !candidate.unscorable())
                .filter(candidate -> candidate.averageConfidence() >= options.minimumConfidence())
                .filter(candidate -> candidate.trajectorySimilarity() >= 0)
                .collect(Collectors.groupingBy(
                        CandidateScore::taskGroup,
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (List<CandidateScore> groupCandidates : scorableByGroup.values()) {
            List<CandidateScore> ranked = groupCandidates.stream()
                    .sorted(Comparator
                            .comparingDouble(CandidateScore::trajectorySimilarity)
                            .reversed()
                            .thenComparing(CandidateScore::sampleId))
                    .toList();
            int topCount = Math.max(1,
                    (int) Math.ceil(ranked.size() * options.topRatio()));
            ranked.subList(0, Math.min(topCount, ranked.size())).stream()
                    .map(CandidateScore::sampleId)
                    .forEach(selectedIds::add);
            ranked.stream()
                    .filter(candidate ->
                            candidate.trajectorySimilarity() >= options.similarityThreshold())
                    .map(CandidateScore::sampleId)
                    .forEach(selectedIds::add);
        }

        List<CandidateDecision> decisions = candidates.stream()
                .map(candidate -> decision(candidate, selectedIds, options))
                .toList();
        List<CandidateDecision> selected = decisions.stream()
                .filter(CandidateDecision::selected)
                .toList();
        long candidateCount = decisions.size();
        double meanSimilarity = selected.stream()
                .mapToDouble(CandidateDecision::trajectorySimilarity)
                .average().orElse(0);
        double meanConfidence = selected.stream()
                .mapToDouble(CandidateDecision::averageConfidence)
                .average().orElse(0);
        return new SelectionReport(
                anchorSampleCount,
                (int) candidateCount,
                selected.size(),
                evaluationIds.size(),
                unscorable,
                anchorTrajectoriesByGroup.size(),
                candidateCount == 0 ? 0 : round(selected.size() / (double) candidateCount),
                round(meanSimilarity),
                round(meanConfidence),
                decisions
        );
    }

    private CandidateDecision decision(CandidateScore candidate,
                                       Set<String> selectedIds,
                                       SelectionOptions options) {
        boolean selected = selectedIds.contains(candidate.sampleId());
        String reason;
        if (candidate.unscorable()) {
            reason = candidate.reason();
        } else if (candidate.averageConfidence() < options.minimumConfidence()) {
            reason = "平均评分置信度不足";
        } else if (selected) {
            reason = "进入同任务 Top-K 或达到轨迹相似度阈值";
        } else {
            reason = "轨迹相似度未达到选择条件";
        }
        return new CandidateDecision(
                candidate.sampleId(),
                candidate.taskGroup(),
                candidate.trajectorySimilarity(),
                candidate.averageConfidence(),
                selected,
                reason
        );
    }

    private List<RewardTrajectoryObservation> sorted(
            List<RewardTrajectoryObservation> observations) {
        return observations.stream()
                .sorted(Comparator
                        .comparingInt(RewardTrajectoryObservation::round)
                        .thenComparing(
                                RewardTrajectoryObservation::policyVersion,
                                Comparator.nullsFirst(String::compareTo)))
                .toList();
    }

    private List<Double> rewards(List<RewardTrajectoryObservation> observations) {
        return observations.stream()
                .map(RewardTrajectoryObservation::reward)
                .toList();
    }

    private List<Double> meanTrajectory(List<List<Double>> trajectories, int length) {
        List<Double> mean = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            double total = 0;
            for (List<Double> trajectory : trajectories) {
                total += trajectory.get(index);
            }
            mean.add(total / trajectories.size());
        }
        return List.copyOf(mean);
    }

    private double normalizedCosine(List<Double> left, List<Double> right) {
        List<Double> normalizedLeft = zScore(left);
        List<Double> normalizedRight = zScore(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return Double.NaN;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < normalizedLeft.size(); index++) {
            double leftValue = normalizedLeft.get(index);
            double rightValue = normalizedRight.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm < 1e-12 || rightNorm < 1e-12) {
            return Double.NaN;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    private List<Double> zScore(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average().orElse(0);
        double standardDeviation = Math.sqrt(variance);
        if (standardDeviation < 1e-8) {
            return List.of();
        }
        return values.stream()
                .map(value -> (value - mean) / standardDeviation)
                .toList();
    }

    private double averageConfidence(List<RewardTrajectoryObservation> observations) {
        return observations.stream()
                .mapToDouble(RewardTrajectoryObservation::confidence)
                .average().orElse(0);
    }

    private String taskGroup(RewardTrajectoryObservation observation) {
        return observation.taskGroup() == null || observation.taskGroup().isBlank()
                ? "default"
                : observation.taskGroup().trim();
    }

    private boolean valid(RewardTrajectoryObservation observation) {
        return observation != null
                && observation.sampleId() != null
                && !observation.sampleId().isBlank()
                && observation.round() >= 0
                && observation.reward() >= 0
                && observation.reward() <= 1
                && observation.confidence() >= 0
                && observation.confidence() <= 1;
    }

    private void validateOptions(SelectionOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (options.minimumRounds() < 2) {
            throw new IllegalArgumentException("minimumRounds must be at least 2");
        }
        if (options.topRatio() <= 0 || options.topRatio() > 1) {
            throw new IllegalArgumentException("topRatio must be greater than 0 and at most 1");
        }
        if (options.similarityThreshold() < -1 || options.similarityThreshold() > 1) {
            throw new IllegalArgumentException(
                    "similarityThreshold must be between -1 and 1");
        }
        if (options.minimumConfidence() < 0 || options.minimumConfidence() > 1) {
            throw new IllegalArgumentException(
                    "minimumConfidence must be between 0 and 1");
        }
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record SelectionOptions(
            int minimumRounds,
            double topRatio,
            double similarityThreshold,
            double minimumConfidence
    ) {
    }

    public record CandidateDecision(
            String sampleId,
            String taskGroup,
            double trajectorySimilarity,
            double averageConfidence,
            boolean selected,
            String reason
    ) {
    }

    public record SelectionReport(
            int labeledAnchorSampleCount,
            int unlabeledCandidateSampleCount,
            int selectedSampleCount,
            int evaluationOnlyExcludedCount,
            int unscorableSampleCount,
            int taskGroupCount,
            double selectionRate,
            double meanSelectedSimilarity,
            double meanSelectedConfidence,
            List<CandidateDecision> decisions
    ) {
    }

    private record CandidateScore(
            String sampleId,
            String taskGroup,
            double trajectorySimilarity,
            double averageConfidence,
            boolean unscorable,
            String reason
    ) {
        private static CandidateScore unscorable(String sampleId,
                                                 String taskGroup,
                                                 double confidence,
                                                 String reason) {
            return new CandidateScore(sampleId, taskGroup, 0, confidence, true, reason);
        }
    }
}
