package com.fhs.aiagent.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * 可持久化、可审计的四臂实验清单。每个实验臂必须先声明模型资产，
 * 之后只能挂接身份和 benchmark 完全匹配的真实评测证据。
 */
public record AlignmentExperiment(
        String experimentId,
        String experimentFingerprint,
        String benchmarkVersion,
        String benchmarkFingerprint,
        int benchmarkCaseCount,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Status status,
        List<ArmEvidence> arms,
        String ablationReportId,
        String ablationReportPath,
        String ablationMarkdownReportPath
) {
    public AlignmentExperiment {
        arms = arms == null ? List.of() : List.copyOf(arms);
        ablationReportId = normalize(ablationReportId);
        ablationReportPath = normalize(ablationReportPath);
        ablationMarkdownReportPath = normalize(ablationMarkdownReportPath);
    }

    public enum Status {
        PLANNED,
        COLLECTING,
        READY_TO_FINALIZE,
        COMPLETED
    }

    public record ArmPlan(
            AlignmentAblationReport.ExperimentArm arm,
            String modelVersion,
            String modelArtifactFingerprint,
            String trainingConfigFingerprint,
            String rewardSchemaVersion,
            String sourceDeployment
    ) {
        public ArmPlan {
            modelVersion = normalize(modelVersion);
            modelArtifactFingerprint = normalize(modelArtifactFingerprint)
                    .toLowerCase();
            trainingConfigFingerprint = normalize(trainingConfigFingerprint)
                    .toLowerCase();
            rewardSchemaVersion = normalize(rewardSchemaVersion);
            sourceDeployment = normalize(sourceDeployment);
        }

        public RagAbReport.RuntimeIdentity runtimeIdentity() {
            return new RagAbReport.RuntimeIdentity(
                    modelVersion,
                    modelArtifactFingerprint,
                    trainingConfigFingerprint,
                    rewardSchemaVersion,
                    sourceDeployment
            );
        }
    }

    public record ArmEvidence(
            ArmPlan plan,
            String evaluationRunId,
            Instant attachedAt,
            RagAbReport report
    ) {
        public ArmEvidence {
            evaluationRunId = normalize(evaluationRunId);
        }

        public boolean attached() {
            return !evaluationRunId.isBlank() && report != null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
