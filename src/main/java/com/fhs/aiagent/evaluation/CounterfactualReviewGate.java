package com.fhs.aiagent.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative review triage, never an automated safety/grounding certification. */
final class CounterfactualReviewGate {
    static final String VERSION = "counterfactual-review-gate-v2";
    private static final List<String> SAFETY_TERMS = List.of(
            "尾随", "跟踪", "蹲守", "家暴", "人身安全", "威胁", "自杀", "自伤", "暴力",
            "堵门", "不让我联系", "夺走手机", "stalking", "stalker", "domestic violence",
            "suicide", "self-harm", "threaten");
    private static final Pattern FOLLOWING_TO_HOME = Pattern.compile(
            "(?:跟着|跟随|尾随).{0,8}(?:进楼|进门|上楼|回家)");

    static List<String> reasons(SystemOneCounterfactualLabel.Evidence evidence) {
        var reasons = new ArrayList<String>();
        inspect(evidence.single(), "SINGLE", reasons);
        inspect(evidence.multi(), "MULTI", reasons);
        String question = java.util.Objects.toString(evidence.question(), "").toLowerCase(Locale.ROOT);
        if (SAFETY_TERMS.stream().anyMatch(question::contains)
                || FOLLOWING_TO_HOME.matcher(question).find()
                || (evidence.observation() != null
                && evidence.observation().disagreementTypes().contains("SAFETY_ACTION_DISAGREEMENT"))) {
            reasons.add("SAFETY_AND_SOURCE_SUPPORT_REVIEW_REQUIRED");
        }
        return List.copyOf(reasons);
    }

    private static void inspect(RagVariantExecution execution, String mode, List<String> reasons) {
        var check = execution == null || execution.trace() == null ? null : execution.trace().finalAnswerContract();
        if (check == null || check.version() == null || check.version().isBlank()) {
            reasons.add("FINAL_CONTRACT_UNKNOWN_" + mode);
        } else if (!check.passed() || !check.missingRequirements().isEmpty()) {
            reasons.add("FINAL_CONTRACT_FAILED_" + mode);
        }
    }
}
