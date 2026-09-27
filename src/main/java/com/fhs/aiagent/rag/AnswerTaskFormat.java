package com.fhs.aiagent.rag;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded, deterministic format checks, not a semantic or safety certification. */
public record AnswerTaskFormat(
        @JsonAlias("exact_sentences") Integer exactSentences,
        @JsonAlias("weekly_checkpoints") Integer weeklyCheckpoints,
        @JsonAlias("comparable_workload") Boolean comparableWorkload,
        @JsonAlias("no_assistant_boilerplate") Boolean noAssistantBoilerplate) {

    public AnswerTaskFormat(Integer exactSentences, Integer weeklyCheckpoints, Boolean comparableWorkload) {
        this(exactSentences, weeklyCheckpoints, comparableWorkload, false);
    }

    private static final Pattern ASSISTANT_INTRO = Pattern.compile(
            "^(?:当然[。！!，,]?\\s*|好的[。！!，,]?\\s*)?(?:我是|我是一名|作为).{0,70}(?:助手|顾问|咨询|AI|人工智能)");
    private static final Pattern SERVICE_INVITATION = Pattern.compile(
            "(?m)^(?:\\s|\\*|#)*(?:如果你愿意|如果需要|如有需要|你愿意的话)[，,：:\\s]*(?:下一条|接下来|之后|后续|稍后)?[，,：:\\s]*我.{0,12}(?:可以|能够|能).{0,12}(?:帮你|为你)");

    private static final String NUMBER = "([1-9][0-9]?|[一二三四五六七八九十两]{1,3})";
    private static final Pattern SENTENCE_REQUEST = Pattern.compile(
            "(?:整理成|写成|只要|只用|用|给我|提供|输出|生成)\\s*" + NUMBER + "句");
    private static final Pattern WEEK_REQUEST = Pattern.compile("(?<![0-9一二三四五六七八九十两])" + NUMBER + "(?:个)?周");
    private static final Pattern WEEK_HEADING = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s*)?(?:\\|\\s*)?第" + NUMBER + "周\\s*(?:[：:|]|$)");
    private static final Pattern CHECKPOINT = Pattern.compile("(?:检查点|验收标准)\\s*[：:]");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?]+|(?<![0-9])\\.(?![0-9])");
    private static final Pattern PREAMBLE = Pattern.compile(
            "^(?:当然|好的|以下|下面|你可以|我是.{0,40}(?:助手|顾问|咨询))");

    public AnswerTaskFormat {
        bounded(exactSentences, 10, "exactSentences");
        bounded(weeklyCheckpoints, 12, "weeklyCheckpoints");
    }

    public static AnswerTaskFormat inferred(String question) {
        String text = Objects.toString(question, "");
        var sentenceMatch = SENTENCE_REQUEST.matcher(text);
        Integer sentences = null;
        while (sentenceMatch.find()) {
            if (text.substring(0, sentenceMatch.start()).matches("(?s).*(?:不|别|无需|不要|不用|不必)\\s*$")) continue;
            sentences = number(sentenceMatch.group(1));
            break;
        }
        if (sentences != null && (sentences < 1 || sentences > 10)) sentences = null;
        Integer weeks = null;
        if (text.matches("(?s).*(?:每周|逐周)(?:的)?检查点.*")) {
            var weekMatch = WEEK_REQUEST.matcher(text);
            if (weekMatch.find()) weeks = number(weekMatch.group(1));
            if (weeks != null && (weeks < 1 || weeks > 12)) weeks = null;
        }
        boolean workload = text.contains("双方") && text.contains("负担")
                && text.contains("可比较");
        boolean identityRequest = text.matches("(?s).*(?:你是谁|介绍(?:一下)?你自己|你的身份|你的功能).*");
        return new AnswerTaskFormat(sentences, weeks, workload, !identityRequest);
    }

    public AnswerTaskFormat merge(AnswerTaskFormat inferred) {
        return new AnswerTaskFormat(mergeCount(exactSentences, inferred.exactSentences),
                mergeCount(weeklyCheckpoints, inferred.weeklyCheckpoints),
                Boolean.TRUE.equals(comparableWorkload) || Boolean.TRUE.equals(inferred.comparableWorkload),
                Boolean.TRUE.equals(noAssistantBoilerplate) || Boolean.TRUE.equals(inferred.noAssistantBoilerplate));
    }

    public List<AnswerVerificationContract.RepairRequirement> requirements() {
        var result = new ArrayList<AnswerVerificationContract.RepairRequirement>();
        if (Boolean.TRUE.equals(noAssistantBoilerplate)) result.add(new AnswerVerificationContract.RepairRequirement(
                "no-assistant-boilerplate", "删除无关的助手身份介绍和结尾服务邀请，直接完成用户任务"));
        if (exactSentences != null) result.add(new AnswerVerificationContract.RepairRequirement(
                "format-exact-sentences", "只输出 " + exactSentences + " 句完整表达，不添加开场介绍、标题或结尾邀请"));
        if (weeklyCheckpoints != null) {
            result.add(new AnswerVerificationContract.RepairRequirement("format-week-order",
                    "按顺序分别列出第1周至第" + weeklyCheckpoints + "周，不合并、重复或增加周次"));
            for (int week = 1; week <= weeklyCheckpoints; week++) {
                result.add(new AnswerVerificationContract.RepairRequirement("format-week-" + week,
                        "第" + week + "周单独给出行动内容与检查点"));
            }
        }
        if (Boolean.TRUE.equals(comparableWorkload)) result.add(new AnswerVerificationContract.RepairRequirement(
                "format-workload-comparison", "提供双方同口径负担对照表（学习、夜间、家务照护）及明确调整条件"));
        return List.copyOf(result);
    }

    public List<String> missingRequirements(String answer) {
        String text = Objects.toString(answer, "").replace("**", "");
        var missing = new ArrayList<String>();
        List<AnswerVerificationContract.RepairRequirement> requirements = requirements();
        if (Boolean.TRUE.equals(noAssistantBoilerplate)
                && (ASSISTANT_INTRO.matcher(text.stripLeading()).find() || SERVICE_INVITATION.matcher(text).find())) {
            add(missing, requirements, "no-assistant-boilerplate");
        }
        if (exactSentences != null && !hasExactSentences(text)) add(missing, requirements, "format-exact-sentences");
        if (weeklyCheckpoints != null) {
            var headings = WEEK_HEADING.matcher(text).results().toList();
            boolean ordered = headings.size() == weeklyCheckpoints;
            for (int index = 0; index < headings.size(); index++) {
                ordered &= number(headings.get(index).group(1)) == index + 1;
            }
            if (!ordered) add(missing, requirements, "format-week-order");
            for (int week = 1; week <= weeklyCheckpoints; week++) {
                boolean found = false;
                for (int index = 0; index < headings.size(); index++) {
                    var heading = headings.get(index);
                    if (number(heading.group(1)) != week) continue;
                    int end = index + 1 < headings.size() ? headings.get(index + 1).start() : text.length();
                    String section = text.substring(heading.end(), end);
                    var checkpoint = CHECKPOINT.matcher(section);
                    found |= checkpoint.find()
                            && substantive(section.substring(0, checkpoint.start()))
                            && substantive(section.substring(checkpoint.end()));
                }
                if (!found) add(missing, requirements, "format-week-" + week);
            }
        }
        if (Boolean.TRUE.equals(comparableWorkload) && !WorkloadComparisonCheck.passed(text)) {
            add(missing, requirements, "format-workload-comparison");
        }
        return List.copyOf(missing);
    }

    public String promptChecklist() {
        String text = String.join("\n", requirements().stream().map(r -> "- " + r.requirement()).toList());
        if (exactSentences != null) text += "\n- 每句以句号、问号或感叹号结束；可编号，不额外增加解释。引用放在对应句旁。";
        if (weeklyCheckpoints != null) text += "\n- 每周使用独立的‘第N周：’标题；写明行动，再写‘检查点：具体可核验结果’。";
        if (Boolean.TRUE.equals(comparableWorkload)) text += "\n- 使用 Markdown 表格：指标 | A/甲/我 | B/乙/伴侣；三项指标建议写作学习小时/周、夜间次数/周、家务照护小时/周。"
                + "未知数值填‘待填’，不要编造；两人每项采用相同单位、周期，写明差异达到何种条件如何调整。"
                + "单元格允许数值或待填后附括号说明；不得混用小时与次数、每天与每周，单位未选定时不要宣称已可比较。"
                + "\n- 仍须遵守用户给出的帮手次数与费用上限；结构检查不能证明公平或可行，需结合已知信息说明假设。";
        return text;
    }

    private boolean hasExactSentences(String answer) {
        String text = answer.replaceAll("\\[来源\\s+\\d+\\]", "")
                .replaceAll("(?m)^\\s*(?:\\d+[.、)）]|[-*])\\s*", "")
                .replaceAll("[“”‘’\"]", "").trim();
        if (PREAMBLE.matcher(text).find() || text.matches("(?s).*如果你愿意.*")
                || text.contains("#") || text.contains("```")) return false;
        var ends = SENTENCE_END.matcher(text);
        int count = 0, end = 0;
        while (ends.find()) {
            if (text.substring(end, ends.start()).isBlank()) return false;
            count++;
            end = ends.end();
        }
        return count == exactSentences && text.substring(end).isBlank();
    }

    private static boolean substantive(String text) {
        return text.replaceAll("\\[来源\\s+\\d+\\]|[\\s\\p{P}\\p{S}0-9]", "").length() >= 4;
    }

    private static void add(List<String> missing, List<AnswerVerificationContract.RepairRequirement> requirements, String id) {
        missing.add(requirements.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow().requirement());
    }

    private static int number(String text) {
        if (text.matches("\\d+")) return Integer.parseInt(text);
        if (text.equals("两")) return 2;
        if (text.equals("十")) return 10;
        if (text.startsWith("十") && text.length() == 2) return 10 + "一二三四五六七八九".indexOf(text.charAt(1)) + 1;
        return text.length() == 1 ? "一二三四五六七八九".indexOf(text) + 1 : 0;
    }

    private static Integer mergeCount(Integer explicit, Integer inferred) {
        if (explicit != null && inferred != null && !explicit.equals(inferred)) {
            throw new IllegalArgumentException("Explicit task format conflicts with the user's requested count");
        }
        return explicit == null ? inferred : explicit;
    }

    private static void bounded(Integer value, int maximum, String name) {
        if (value != null && (value < 1 || value > maximum)) throw new IllegalArgumentException(name + " out of range");
    }
}
