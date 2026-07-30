package com.fhs.aiagent.rag;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成、审查、修订和 RLVR 选择共享的答案契约。
 *
 * <p>训练/回放可传入数据集冻结的完整契约；普通在线请求未传入时，则从用户问题提取
 * 可确定执行的格式约束。所有字段都会做长度和数量限制，避免把任意请求内容直接扩展成
 * 无界提示词。</p>
 */
public record AnswerVerificationContract(
        @JsonAlias("required_concepts") List<String> requiredConcepts,
        @JsonAlias("forbidden_phrases") List<String> forbiddenPhrases,
        @JsonAlias("minimum_answer_chars") Integer minimumAnswerChars,
        @JsonAlias("maximum_answer_chars") Integer maximumAnswerChars,
        @JsonAlias("citation_required") Boolean citationRequired,
        @JsonAlias("no_follow_up") Boolean noFollowUp,
        @JsonAlias("must_mark_assumptions") Boolean mustMarkAssumptions,
        @JsonAlias("minimum_action_items") Integer minimumActionItems
) {

    public static final String VERSION = "answer-verification-contract-v4";

    public static final String STRUCTURE_NORMALIZER_VERSION =
            "deterministic-answer-structure-v1";

    private static final int MAXIMUM_LIST_SIZE = 24;

    private static final int MAXIMUM_ITEM_CHARS = 80;

    private static final Pattern NUMBERED_ACTION = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(10|[1-9])[.、)）](?!\\d)(?=\\s*\\S)");

    private static final Pattern BULLET_ACTION = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s*)?[-*]\\s+");

    private static final Pattern INLINE_ACTION_BOUNDARY = Pattern.compile(
            "([：；;])\\s*((?:10|[1-9])[.、)）])(?!\\d)\\s*");

    private static final List<List<String>> DAY_ACTION_MARKERS = List.of(
            List.of("周一", "星期一", "第一天", "第1天"),
            List.of("周二", "星期二", "第二天", "第2天"),
            List.of("周三", "星期三", "第三天", "第3天"),
            List.of("周四", "星期四", "第四天", "第4天"),
            List.of("周五", "星期五", "第五天", "第5天"),
            List.of("周六", "星期六", "第六天", "第6天"),
            List.of("周日", "周天", "星期日", "星期天",
                    "第七天", "第7天")
    );

    private static final Map<String, List<String>> VERIFIED_CONCEPT_ALIASES =
            Map.of(
                    "情绪管理", List.of(
                            "正视情绪", "缓解焦虑", "调节情绪", "应对焦虑"),
                    "原因", List.of("根源", "成因"),
                    "优先级|排序", List.of("优先处理", "按优先顺序"),
                    "今天|立即", List.of("今日", "今晚", "当日")
            );

    private static final List<String> FOLLOW_UP_PHRASES = List.of(
            "请你详细描述", "请详细描述", "请补充更多",
            "请提供更多", "接下来，请你", "以便我为你");

    private static final List<String> ASSUMPTION_MARKERS = List.of(
            "假设", "基于目前", "基于现有", "信息不足");

    public AnswerVerificationContract {
        requiredConcepts = sanitize(requiredConcepts);
        forbiddenPhrases = sanitize(forbiddenPhrases);
        minimumAnswerChars = bounded(minimumAnswerChars, 0, 8_000);
        maximumAnswerChars = bounded(maximumAnswerChars, 1, 8_000);
        minimumActionItems = bounded(minimumActionItems, 0, 20);
        if (minimumAnswerChars != null
                && maximumAnswerChars != null
                && minimumAnswerChars > maximumAnswerChars) {
            throw new IllegalArgumentException(
                    "minimumAnswerChars must not exceed maximumAnswerChars");
        }
    }

    public static AnswerVerificationContract inferred(String question) {
        String normalized = Objects.toString(question, "");
        List<String> concepts = new ArrayList<>();
        if (normalized.contains("十五分钟") || normalized.contains("15分钟")) {
            concepts.add("15分钟|十五分钟");
        }
        if (normalized.contains("话术")) {
            concepts.add("话术|可以这样说");
        }
        if (normalized.contains("七天")) {
            concepts.add("周一|星期一|第一天|第1天");
            concepts.add("周日|星期日|第七天|第7天");
        }
        if (normalized.contains("复盘") || normalized.contains("回顾")) {
            concepts.add("复盘|回顾");
        }
        if (normalized.contains("优先级") || normalized.contains("排序")) {
            concepts.add("优先级|排序");
        }
        boolean noFollowUp = normalized.contains("不要追问")
                || normalized.contains("不要继续追问")
                || normalized.contains("不要反复追问")
                || normalized.contains("直接给");
        boolean markAssumptions = normalized.contains("标明合理假设")
                || normalized.contains("标明假设");
        int actionItems = normalized.contains("七天")
                ? 7
                : normalized.contains("三项")
                || normalized.contains("三个")
                || normalized.contains("检查清单") ? 3 : 0;
        List<String> forbidden = normalized.contains("不要编造课程或案例")
                ? List.of("推荐课程", "课程链接", "真实案例")
                : List.of();
        return new AnswerVerificationContract(
                concepts,
                forbidden,
                AgenticRagService.minimumAnswerChars(normalized),
                AgenticRagService.maximumAnswerChars(normalized),
                null,
                noFollowUp,
                markAssumptions,
                actionItems
        );
    }

    /**
     * 显式契约优先，但仍合并用户问题中的直接约束，防止调用方漏传显式格式要求。
     */
    public AnswerVerificationContract mergeInferred(String question) {
        AnswerVerificationContract inferred = inferred(question);
        List<String> concepts = new ArrayList<>(requiredConcepts);
        concepts.addAll(inferred.requiredConcepts);
        List<String> forbidden = new ArrayList<>(forbiddenPhrases);
        forbidden.addAll(inferred.forbiddenPhrases);
        return new AnswerVerificationContract(
                concepts,
                forbidden,
                minimumAnswerChars == null
                        ? inferred.minimumAnswerChars : minimumAnswerChars,
                maximumAnswerChars == null
                        ? inferred.maximumAnswerChars : maximumAnswerChars,
                citationRequired,
                Boolean.TRUE.equals(noFollowUp)
                        || Boolean.TRUE.equals(inferred.noFollowUp),
                Boolean.TRUE.equals(mustMarkAssumptions)
                        || Boolean.TRUE.equals(inferred.mustMarkAssumptions),
                Math.max(
                        minimumActionItems == null ? 0 : minimumActionItems,
                        inferred.minimumActionItems == null
                                ? 0 : inferred.minimumActionItems)
        );
    }

    public ContractCheck check(String answer, String context) {
        String candidate = normalizeStructure(answer);
        List<String> missing = new ArrayList<>();
        int length = candidate.codePointCount(0, candidate.length());
        if (minimumAnswerChars != null && length < minimumAnswerChars) {
            missing.add("答案不少于 " + minimumAnswerChars + " 字符");
        }
        if (maximumAnswerChars != null && length > maximumAnswerChars) {
            missing.add("答案不超过 " + maximumAnswerChars + " 字符");
        }
        boolean hasSources = contextHasSources(context);
        boolean requireCitation = Boolean.TRUE.equals(citationRequired)
                || hasSources;
        if (Boolean.TRUE.equals(citationRequired) && !hasSources) {
            missing.add("没有可满足引用契约的知识证据");
        } else if (requireCitation
                && !AgenticRagService.satisfiesCitationContract(
                candidate, context)) {
            missing.add("使用有效的 [来源 n] 单编号引用");
        }
        int observedActions = observedActionItems(candidate);
        for (String expression : requiredConcepts) {
            if (!conceptSatisfied(candidate, expression, observedActions)) {
                missing.add("覆盖概念：" + expression);
            }
        }
        for (String phrase : forbiddenPhrases) {
            if (candidate.contains(phrase)) {
                missing.add("删除禁用短语：" + phrase);
            }
        }
        if (Boolean.TRUE.equals(noFollowUp)
                && FOLLOW_UP_PHRASES.stream().anyMatch(candidate::contains)) {
            missing.add("直接回答，不向用户追问");
        }
        int requiredActions = minimumActionItems == null ? 0 : minimumActionItems;
        if (requiredActions > 0 && observedActions < requiredActions) {
            missing.add("至少提供 " + requiredActions + " 个行动项");
        }
        if (Boolean.TRUE.equals(mustMarkAssumptions)
                && ASSUMPTION_MARKERS.stream().noneMatch(candidate::contains)) {
            missing.add("明确标注合理假设或信息边界");
        }
        return new ContractCheck(missing.isEmpty(), List.copyOf(missing));
    }

    public String promptChecklist() {
        List<String> checks = new ArrayList<>();
        if (minimumAnswerChars != null || maximumAnswerChars != null) {
            checks.add("长度：%s~%s 字符".formatted(
                    minimumAnswerChars == null ? "0" : minimumAnswerChars,
                    maximumAnswerChars == null ? "不限" : maximumAnswerChars));
        }
        if (Boolean.TRUE.equals(citationRequired)) {
            checks.add("引用：必须使用有效的 [来源 n] 单编号格式");
        }
        requiredConcepts.forEach(value -> checks.add("必须覆盖：" + value));
        forbiddenPhrases.forEach(value -> checks.add("禁止出现：" + value));
        if (Boolean.TRUE.equals(noFollowUp)) {
            checks.add("不得追问，必须直接完成任务");
        }
        if (Boolean.TRUE.equals(mustMarkAssumptions)) {
            checks.add("必须标明合理假设或信息边界");
        }
        if (minimumActionItems != null && minimumActionItems > 0) {
            checks.add("至少 " + minimumActionItems
                    + " 个行动项；每项单独成行并用 1.、2.、3. 编号");
        }
        String checklist = checks.isEmpty()
                ? "（无额外结构化约束）"
                : String.join("\n", checks.stream().map("- "::concat).toList());
        return checklist + "\n- 含“|”的契约表示任选其一，"
                + "答案只写自然表达，不得照抄竖线备选串";
    }

    /**
     * 为修正模型生成稳定、与自然语言表达解耦的要求 ID。
     */
    public List<RepairRequirement> repairRequirements() {
        List<RepairRequirement> requirements = new ArrayList<>();
        if (minimumAnswerChars != null) {
            requirements.add(new RepairRequirement(
                    "length-minimum",
                    "答案不少于 " + minimumAnswerChars + " 字符"));
        }
        if (maximumAnswerChars != null) {
            requirements.add(new RepairRequirement(
                    "length-maximum",
                    "答案不超过 " + maximumAnswerChars + " 字符"));
        }
        if (Boolean.TRUE.equals(citationRequired)) {
            requirements.add(new RepairRequirement(
                    "citation-evidence",
                    "没有可满足引用契约的知识证据"));
        }
        requirements.add(new RepairRequirement(
                "citation-format",
                "使用有效的 [来源 n] 单编号引用"));
        for (int index = 0; index < requiredConcepts.size(); index++) {
            requirements.add(new RepairRequirement(
                    conceptRequirementId(index),
                    "覆盖概念：" + requiredConcepts.get(index)));
        }
        for (int index = 0; index < forbiddenPhrases.size(); index++) {
            requirements.add(new RepairRequirement(
                    "forbidden-%02d".formatted(index + 1),
                    "删除禁用短语：" + forbiddenPhrases.get(index)));
        }
        if (Boolean.TRUE.equals(noFollowUp)) {
            requirements.add(new RepairRequirement(
                    "no-follow-up",
                    "直接回答，不向用户追问"));
        }
        if (minimumActionItems != null && minimumActionItems > 0) {
            requirements.add(new RepairRequirement(
                    "action-items",
                    "至少提供 " + minimumActionItems + " 个行动项"));
        }
        if (Boolean.TRUE.equals(mustMarkAssumptions)) {
            requirements.add(new RepairRequirement(
                    "assumptions",
                    "明确标注合理假设或信息边界"));
        }
        return List.copyOf(requirements);
    }

    public String repairPromptChecklist(List<String> missingRequirements) {
        List<String> missing = missingRequirements == null
                ? List.of() : missingRequirements;
        List<String> rows = repairRequirements().stream()
                .filter(value -> missing.contains(value.requirement()))
                .map(value -> "- " + value.id() + " => "
                        + value.requirement())
                .toList();
        if (rows.size() != missing.size()) {
            throw new IllegalArgumentException(
                    "every missing requirement must have a stable ID");
        }
        return rows.isEmpty()
                ? "（模型审查要求修订）"
                : String.join("\n", rows);
    }

    public List<String> repairRequirementIds(
            List<String> missingRequirements) {
        List<String> missing = missingRequirements == null
                ? List.of() : missingRequirements;
        return repairRequirements().stream()
                .filter(value -> missing.contains(value.requirement()))
                .map(RepairRequirement::id)
                .toList();
    }

    static String conceptRequirementId(int index) {
        return "concept-%02d".formatted(index + 1);
    }

    /**
     * 只规范答案中已经存在的结构，不补写概念、事实、来源或行动内容。
     */
    public String normalizeStructure(String answer) {
        String normalized = Objects.toString(answer, "");
        for (String expression : requiredConcepts) {
            if (!expression.contains("|") || !normalized.contains(expression)) {
                continue;
            }
            String firstAlternative = firstAlternative(expression);
            if (!firstAlternative.isEmpty()) {
                normalized = normalized.replace(expression, firstAlternative);
            }
        }
        if (minimumActionItems == null || minimumActionItems <= 0) {
            return normalized;
        }
        return INLINE_ACTION_BOUNDARY.matcher(normalized)
                .replaceAll("$1\n$2 ");
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim();
            if (normalized.codePointCount(0, normalized.length())
                    > MAXIMUM_ITEM_CHARS) {
                throw new IllegalArgumentException(
                        "verification contract item is too long");
            }
            result.add(normalized);
            if (result.size() >= MAXIMUM_LIST_SIZE) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static Integer bounded(Integer value, int minimum, int maximum) {
        if (value == null) {
            return null;
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "verification contract number is out of range");
        }
        return value;
    }

    private static boolean containsAlternative(String answer, String expression) {
        for (String alternative : expression.split("\\|")) {
            String value = alternative.trim();
            if (!value.isEmpty() && answer.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean conceptSatisfied(
            String answer,
            String expression,
            int observedActions) {
        if (containsAlternative(answer, expression)) {
            return true;
        }
        if (VERIFIED_CONCEPT_ALIASES.getOrDefault(expression, List.of())
                .stream().anyMatch(answer::contains)) {
            return true;
        }
        int requiredQuantity = requiredActionQuantity(expression);
        if (requiredQuantity > 0 && observedActions >= requiredQuantity) {
            return true;
        }
        int requiredDay = requiredDay(expression);
        return requiredDay > 0 && containsDay(answer, requiredDay);
    }

    private static int requiredActionQuantity(String expression) {
        if (List.of(expression.split("\\|")).stream().map(String::trim).anyMatch(
                value -> Set.of("三项", "3项", "三个", "3个").contains(value))) {
            return 3;
        }
        return 0;
    }

    private static int requiredDay(String expression) {
        List<String> alternatives = List.of(expression.split("\\|"));
        if (alternatives.stream().map(String::trim).anyMatch(
                value -> Set.of("周一", "星期一").contains(value))) {
            return 1;
        }
        if (alternatives.stream().map(String::trim).anyMatch(
                value -> Set.of("周日", "周天", "星期日", "星期天").contains(value))) {
            return 7;
        }
        return 0;
    }

    private static int observedActionItems(String answer) {
        LinkedHashSet<Integer> ordinals = new LinkedHashSet<>();
        Matcher matcher = NUMBERED_ACTION.matcher(answer);
        while (matcher.find()) {
            ordinals.add(Integer.parseInt(matcher.group(1)));
        }
        int contiguousOrdinals = 0;
        while (ordinals.contains(contiguousOrdinals + 1)) {
            contiguousOrdinals++;
        }
        int bulletActions = (int) BULLET_ACTION.matcher(answer).results().count();
        int dayActions = 0;
        for (int day = 1; day <= DAY_ACTION_MARKERS.size(); day++) {
            if (containsDay(answer, day)) {
                dayActions++;
            }
        }
        return Math.max(contiguousOrdinals, Math.max(bulletActions, dayActions));
    }

    private static boolean containsDay(String answer, int day) {
        return DAY_ACTION_MARKERS.get(day - 1).stream().anyMatch(answer::contains);
    }

    private static String firstAlternative(String expression) {
        String[] alternatives = expression.split("\\|");
        return alternatives.length == 0 ? "" : alternatives[0].trim();
    }

    private static boolean contextHasSources(String context) {
        return Objects.toString(context, "").contains("[来源 ");
    }

    public record ContractCheck(boolean passed, List<String> missingRequirements) {
    }

    public record RepairRequirement(String id, String requirement) {
    }
}
