package com.fhs.aiagent.rl.alignment;

import com.fhs.aiagent.rl.AgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AiJudgePanelService {

    private static final Logger log = LoggerFactory.getLogger(AiJudgePanelService.class);

    private final AgentTrajectoryRepository trajectoryRepository;

    private final AlignmentAssessmentRepository assessmentRepository;

    private final AiJudgeClient judgeClient;

    private final AutomatedAlignmentEvaluator evaluator;

    public AiJudgePanelService(AgentTrajectoryRepository trajectoryRepository,
                               AlignmentAssessmentRepository assessmentRepository,
                               AiJudgeClient judgeClient,
                               AutomatedAlignmentEvaluator evaluator) {
        this.trajectoryRepository = trajectoryRepository;
        this.assessmentRepository = assessmentRepository;
        this.judgeClient = judgeClient;
        this.evaluator = evaluator;
    }

    public AutomatedAlignmentAssessment assess(String trajectoryId) {
        AgentTrajectory trajectory = trajectoryRepository.findById(trajectoryId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Trajectory not found: " + trajectoryId));
        List<AiJudgeScore> scores = new ArrayList<>();
        for (AiJudgeDimension dimension : AiJudgeDimension.values()) {
            try {
                scores.add(judgeClient.judge(trajectory, dimension));
            } catch (RuntimeException exception) {
                log.warn("AI Judge {} failed for trajectory {}: {}",
                        dimension, trajectoryId, exception.getMessage());
            }
        }
        return assessmentRepository.save(evaluator.evaluate(trajectory, scores, false));
    }

    public BatchAssessmentResult assessPending(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        List<String> pendingIds = trajectoryRepository.findAll().stream()
                .filter(trajectory -> "COMPLETED".equals(trajectory.status()))
                .filter(trajectory -> assessmentRepository
                        .findByTrajectoryId(trajectory.trajectoryId()).isEmpty())
                .limit(limit)
                .map(AgentTrajectory::trajectoryId)
                .toList();
        List<AutomatedAlignmentAssessment> assessments = pendingIds.stream()
                .map(this::assess)
                .toList();
        long positive = assessments.stream()
                .filter(AutomatedAlignmentAssessment::approvedPositive)
                .count();
        long holdout = assessments.stream()
                .filter(assessment -> assessment.trainingDecision() == TrainingDecision.HOLDOUT)
                .count();
        long excluded = assessments.size() - positive - holdout;
        return new BatchAssessmentResult(
                assessments.size(), positive, holdout, excluded, assessments);
    }

    public AutomatedAlignmentAssessment get(String trajectoryId) {
        return assessmentRepository.findByTrajectoryId(trajectoryId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Alignment assessment not found: " + trajectoryId));
    }

    public AlignmentMetrics metrics() {
        List<AutomatedAlignmentAssessment> assessments = assessmentRepository.findAll();
        if (assessments.isEmpty()) {
            return new AlignmentMetrics(
                    0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long positive = assessments.stream()
                .filter(AutomatedAlignmentAssessment::approvedPositive)
                .count();
        long negative = assessments.stream()
                .filter(assessment -> assessment.trainingDecision() == TrainingDecision.NEGATIVE)
                .count();
        long holdout = assessments.stream()
                .filter(assessment -> assessment.trainingDecision() == TrainingDecision.HOLDOUT)
                .count();
        long excluded = assessments.stream()
                .filter(assessment -> assessment.trainingDecision() == TrainingDecision.EXCLUDED)
                .count();
        long pseudoLabeled = assessments.stream()
                .filter(assessment ->
                        assessment.supervisionLabel() == SupervisionLabel.PSEUDO_LABELED)
                .count();
        long highDisagreement = assessments.stream()
                .filter(assessment -> assessment.judgeAgreement() < 0.7)
                .count();
        double averageVerifierReward = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::verifierReward)
                .average().orElse(0);
        double averageAiReward = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::aiReward)
                .average().orElse(0);
        double averageReward = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::totalReward)
                .average().orElse(0);
        double averageConfidence = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::confidence)
                .average().orElse(0);
        double averageAgreement = assessments.stream()
                .mapToDouble(AutomatedAlignmentAssessment::judgeAgreement)
                .average().orElse(0);
        return new AlignmentMetrics(
                assessments.size(),
                positive,
                negative,
                holdout,
                excluded,
                pseudoLabeled,
                round(pseudoLabeled / (double) assessments.size()),
                round(positive / (double) assessments.size()),
                round(holdout / (double) assessments.size()),
                round(highDisagreement / (double) assessments.size()),
                round(averageVerifierReward),
                round(averageAiReward),
                round(averageReward),
                round(averageConfidence),
                round(averageAgreement)
        );
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record BatchAssessmentResult(
            int assessedCount,
            long positiveCount,
            long holdoutCount,
            long excludedOrNegativeCount,
            List<AutomatedAlignmentAssessment> assessments
    ) {
    }

    public record AlignmentMetrics(
            long assessmentCount,
            long positiveCount,
            long negativeCount,
            long holdoutCount,
            long excludedCount,
            long pseudoLabeledCount,
            double pseudoLabelCoverage,
            double autoApprovalRate,
            double estimatedReviewRate,
            double highDisagreementRate,
            double averageVerifierReward,
            double averageAiReward,
            double averageReward,
            double averageConfidence,
            double averageJudgeAgreement
    ) {
    }
}
