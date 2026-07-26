package com.fhs.aiagent.rag.multiagent;

import java.time.Instant;
import java.util.List;

/**
 * 渐进式自动发布的持久化控制状态与有界审计历史。
 */
public record ProgressiveDeliveryAutomationState(
        boolean automationEnabled,
        boolean paused,
        Instant updatedAt,
        String reason,
        List<ExecutionAudit> history
) {

    public ProgressiveDeliveryAutomationState {
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        reason = normalize(reason);
        history = history == null ? List.of() : List.copyOf(history);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record ExecutionAudit(
            String executionId,
            String trigger,
            String sourceDeploymentVersion,
            String resultingDeploymentVersion,
            RoutingPolicyMode sourceMode,
            double sourceTrafficRate,
            RoutingPolicyMode targetMode,
            double targetTrafficRate,
            String policyArtifactVersion,
            ExecutionOutcome outcome,
            Instant executedAt,
            String reason
    ) {

        public ExecutionAudit {
            executionId = normalize(executionId);
            trigger = normalize(trigger);
            sourceDeploymentVersion = normalize(sourceDeploymentVersion);
            resultingDeploymentVersion = normalize(resultingDeploymentVersion);
            sourceMode = sourceMode == null ? RoutingPolicyMode.OFF : sourceMode;
            targetMode = targetMode == null ? RoutingPolicyMode.OFF : targetMode;
            policyArtifactVersion = normalize(policyArtifactVersion);
            outcome = outcome == null ? ExecutionOutcome.FAILED : outcome;
            executedAt = executedAt == null ? Instant.now() : executedAt;
            reason = normalize(reason);
        }
    }

    public enum ExecutionOutcome {
        APPLIED,
        FAILED,
        EMERGENCY_ROLLBACK,
        MANUAL_ROLLBACK
    }
}
