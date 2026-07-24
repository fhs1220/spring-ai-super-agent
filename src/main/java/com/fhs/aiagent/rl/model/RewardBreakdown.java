package com.fhs.aiagent.rl.model;

public record RewardBreakdown(
        double total,
        double retrievalQuality,
        double groundingQuality,
        // 保留字段名以兼容已经持久化的旧轨迹；它现在明确表示“检索证据充分度”。
        double convergenceQuality,
        double taskCompletionQuality,
        double collaborationQuality,
        double efficiency,
        double userFeedback
) {
}
