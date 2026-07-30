package com.fhs.aiagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 把模型提供的结构化修正内容确定性地组织为用户答案。
 */
final class DeterministicContractRepairRenderer {

    static final String VERSION = "deterministic-contract-repair-renderer-v2";

    private static final int MINIMUM_EVIDENCE_CHARS = 8;

    private static final int MINIMUM_ACTION_CHARS = 4;

    private static final Pattern LEADING_ACTION_MARKERS = Pattern.compile(
            "^\\s*(?:(?:10|[1-9])[.、)）]\\s*)+");

    private DeterministicContractRepairRenderer() {
    }

    static RenderResult render(
            StructuredContractRepair repair,
            AnswerVerificationContract contract,
            String context) {
        AnswerVerificationContract effectiveContract =
                Objects.requireNonNull(contract, "contract");
        if (repair == null || repair.answer().isBlank()) {
            AnswerVerificationContract.ContractCheck emptyCheck =
                    effectiveContract.check("", context);
            return new RenderResult(
                    "",
                    false,
                    0,
                    0,
                    false,
                    emptyCheck.missingRequirements(),
                    effectiveContract.repairRequirementIds(
                            emptyCheck.missingRequirements()));
        }

        String rendered = effectiveContract.normalizeStructure(repair.answer());
        int conceptSections = 0;
        for (int index = 0;
             index < effectiveContract.requiredConcepts().size();
             index++) {
            String expression =
                    effectiveContract.requiredConcepts().get(index);
            String missingRequirement = "覆盖概念：" + expression;
            if (!effectiveContract.check(rendered, context)
                    .missingRequirements().contains(missingRequirement)) {
                continue;
            }
            String evidence = evidenceFor(
                    repair,
                    AnswerVerificationContract.conceptRequirementId(index),
                    expression);
            if (!hasSubstantiveContent(evidence, MINIMUM_EVIDENCE_CHARS)) {
                continue;
            }
            rendered = appendSection(
                    rendered,
                    firstAlternative(expression),
                    evidence);
            conceptSections++;
        }

        int renderedActions = 0;
        int minimumActions = effectiveContract.minimumActionItems() == null
                ? 0 : effectiveContract.minimumActionItems();
        String actionRequirement =
                "至少提供 " + minimumActions + " 个行动项";
        if (minimumActions > 0
                && effectiveContract.check(rendered, context)
                .missingRequirements().contains(actionRequirement)) {
            List<String> actions = repair.actionItems().stream()
                    .map(DeterministicContractRepairRenderer
                            ::normalizeActionItem)
                    .filter(value -> hasSubstantiveContent(
                            value, MINIMUM_ACTION_CHARS))
                    .distinct()
                    .limit(minimumActions)
                    .toList();
            if (actions.size() >= minimumActions) {
                StringBuilder actionSection =
                        new StringBuilder(rendered.stripTrailing())
                                .append("\n\n**行动项**");
                for (int index = 0; index < actions.size(); index++) {
                    actionSection.append("\n")
                            .append(index + 1)
                            .append(". ")
                            .append(actions.get(index));
                }
                rendered = actionSection.toString();
                renderedActions = actions.size();
            }
        }

        boolean assumptionsRendered = false;
        String assumptionRequirement = "明确标注合理假设或信息边界";
        if (Boolean.TRUE.equals(effectiveContract.mustMarkAssumptions())
                && effectiveContract.check(rendered, context)
                .missingRequirements().contains(assumptionRequirement)
                && hasSubstantiveContent(
                repair.assumptions(), MINIMUM_EVIDENCE_CHARS)) {
            rendered = appendSection(
                    rendered,
                    "合理假设",
                    repair.assumptions());
            assumptionsRendered = true;
        }

        rendered = effectiveContract.normalizeStructure(rendered);
        AnswerVerificationContract.ContractCheck finalCheck =
                effectiveContract.check(rendered, context);
        boolean acceptedStructuredEvidence = conceptSections > 0
                || renderedActions > 0
                || assumptionsRendered;
        return new RenderResult(
                rendered,
                acceptedStructuredEvidence,
                conceptSections,
                renderedActions,
                assumptionsRendered,
                finalCheck.missingRequirements(),
                effectiveContract.repairRequirementIds(
                        finalCheck.missingRequirements()));
    }

    private static String evidenceFor(
            StructuredContractRepair repair,
            String requirementId,
            String expression) {
        return repair.evidence().stream()
                .filter(value -> value.requirementId().equals(requirementId)
                        || value.requirementId().equals(expression)
                        || value.requirementId().equals(
                        "覆盖概念：" + expression))
                .map(StructuredContractRepair.Evidence::content)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static boolean hasSubstantiveContent(
            String value,
            int minimumChars) {
        String normalized = Objects.toString(value, "").trim();
        return normalized.codePointCount(0, normalized.length())
                >= minimumChars;
    }

    private static String normalizeActionItem(String value) {
        return LEADING_ACTION_MARKERS.matcher(
                Objects.toString(value, "").trim()).replaceFirst("").trim();
    }

    private static String appendSection(
            String answer,
            String heading,
            String content) {
        return answer.stripTrailing()
                + "\n\n**" + heading + "**：" + content.trim();
    }

    private static String firstAlternative(String expression) {
        String[] alternatives = expression.split("\\|");
        return alternatives.length == 0
                ? expression : alternatives[0].trim();
    }

    record RenderResult(
            String answer,
            boolean acceptedStructuredEvidence,
            int renderedConceptSections,
            int renderedActionItems,
            boolean assumptionsRendered,
            List<String> missingRequirements,
            List<String> unresolvedRequirementIds
    ) {

        RenderResult {
            missingRequirements = List.copyOf(
                    new ArrayList<>(missingRequirements));
            unresolvedRequirementIds = List.copyOf(
                    new ArrayList<>(unresolvedRequirementIds));
        }

        boolean contractPassed() {
            return missingRequirements.isEmpty();
        }
    }
}
