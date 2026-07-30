package com.fhs.aiagent.rag;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 模型修正阶段返回的结构化载荷。
 *
 * <p>载荷中的内容仍由模型生成；确定性渲染器只负责把已有内容组织成契约要求的标题和
 * 编号，不会补写事实、来源或删除禁用内容。</p>
 */
public record StructuredContractRepair(
        String answer,
        List<Evidence> evidence,
        @JsonAlias("action_items") List<String> actionItems,
        String assumptions
) {

    private static final int MAXIMUM_EVIDENCE = 24;

    private static final int MAXIMUM_ACTION_ITEMS = 20;

    private static final int MAXIMUM_ANSWER_CHARS = 8_000;

    private static final int MAXIMUM_CONTENT_CHARS = 1_600;

    public StructuredContractRepair {
        answer = boundedText(answer, MAXIMUM_ANSWER_CHARS);
        evidence = sanitizeEvidence(evidence);
        actionItems = sanitizeStrings(actionItems, MAXIMUM_ACTION_ITEMS);
        assumptions = boundedText(assumptions, MAXIMUM_CONTENT_CHARS);
    }

    private static List<Evidence> sanitizeEvidence(List<Evidence> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.requirement().isBlank())
                .distinct()
                .limit(MAXIMUM_EVIDENCE)
                .toList();
    }

    private static List<String> sanitizeStrings(
            List<String> values,
            int limit) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = boundedText(value, MAXIMUM_CONTENT_CHARS);
            if (!normalized.isBlank()) {
                sanitized.add(normalized);
            }
            if (sanitized.size() >= limit) {
                break;
            }
        }
        return List.copyOf(sanitized);
    }

    private static String boundedText(String value, int maximumChars) {
        String normalized = Objects.toString(value, "").trim();
        if (normalized.codePointCount(0, normalized.length())
                > maximumChars) {
            throw new IllegalArgumentException(
                    "structured repair content is too long");
        }
        return normalized;
    }

    public record Evidence(String requirement, String content) {

        public Evidence {
            requirement = boundedText(requirement, MAXIMUM_CONTENT_CHARS);
            content = boundedText(content, MAXIMUM_CONTENT_CHARS);
        }
    }
}
