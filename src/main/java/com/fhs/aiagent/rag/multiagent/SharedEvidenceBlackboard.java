package com.fhs.aiagent.rag.multiagent;

import java.util.List;
import java.util.Objects;

/**
 * 多 Agent 的共享证据黑板。
 * <p>
 * 只保存强类型专业贡献与统一的知识库上下文，避免 Agent 之间自由对话造成上下文污染。
 */
public record SharedEvidenceBlackboard(
        String question,
        String evidenceContext,
        List<SpecialistContribution> contributions
) {

    public String formatForSynthesis() {
        StringBuilder builder = new StringBuilder();
        for (SpecialistContribution contribution : contributions) {
            if (!contribution.success()) {
                continue;
            }
            builder.append("## ")
                    .append(contribution.agentName())
                    .append("（")
                    .append(contribution.domain())
                    .append("）\n")
                    .append("置信度：")
                    .append(String.format("%.2f", contribution.confidence()))
                    .append("\n关键判断：\n");
            contribution.findings().forEach(item -> builder.append("- ").append(item).append('\n'));
            builder.append("建议：\n");
            contribution.recommendations().forEach(item -> builder.append("- ").append(item).append('\n'));
            if (!contribution.citedSources().isEmpty()) {
                builder.append("使用来源：").append(contribution.citedSources()).append('\n');
            }
            if (!Objects.toString(contribution.uncertainty(), "").isBlank()) {
                builder.append("不确定性：").append(contribution.uncertainty()).append('\n');
            }
            builder.append('\n');
        }
        return builder.isEmpty() ? "（没有成功的专业 Agent 贡献）" : builder.toString();
    }
}
