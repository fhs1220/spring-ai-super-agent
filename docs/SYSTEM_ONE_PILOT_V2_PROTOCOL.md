# 五题开发回归 v2：运行前冻结协议

用户已同意进行下一步。沿用 [v1 预注册](SYSTEM_ONE_PILOT_PROTOCOL.md) 的五题、顺序、
模型、温度、Token 上限、价格、utility、量表及停止规则；只验证答案契约 v5 的生成链路。
不调用 Jev/Laya、不训练、不导出审批标签，不扩大样本量。

- 新目录：`tmp/system-one-bootstrap-pilot-v2`；旧 v1 原始数据和人评不覆盖。
- 代码先提交、重新打包，源码 SHA/JAR/语料/索引/五题集合指纹写入新 manifest。
- 本轮独立估算停止阈值仍为 **10 CNY**，不是硬消费封顶；逐题提交，检查上一题终态
  和账本后再继续。任何未知费用、失败、multi 降级或无效配对停止，不自动重试或替换题目。
- 本轮前核对 OpenRouter `/api/v1/models`：`openai/gpt-5.4` 输入/输出仍为
  USD 2.50/15.00 每百万 Token（超长上下文有另档价，本轮检查实际输入规模）。
  模型仍是别名，不宣称供应商权重完全固定；固定会计换算为 7.2 CNY/USD。
- 主要观察：四句任务格式、六周逐周检查点、负担对照结构，以及两路各自契约缺项。
  次要观察：逐路径延迟、输入/输出 Token、调用数、估算费用、失败/降级。
- 自动 Judge 分数单列为机器意见；本轮不代替真人审批。旧 AI 辅助评分不能作为独立人评。
- 旧答案可离线用同一 v5 检查器复核，但新生成结果与旧在线 v4 通过率不能直接混成
  “质量提升率”；旧轮次原始判断保持不变。五题已用于修复开发，不能做泛化或显著性结论。
- 新盲评材料保留 A/B，但已知五题及旧映射可能产生记忆影响，不称其为独立双盲实验。

运行入口统一使用 `SYSTEM_ONE_PILOT_ROUND=v2`：

```bash
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/prepare-system-one-pilot.mjs
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/system-one-pilot.mjs account-before
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/system-one-pilot.mjs serve
# 另一个终端；每次先检查上一题的状态和完整账本
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/system-one-pilot.mjs submit-one
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/system-one-pilot.mjs status
# 全部完成或触发停止条件后
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/system-one-pilot.mjs account-after
SYSTEM_ONE_PILOT_ROUND=v2 node scripts/render-system-one-pilot-review.mjs
```

未设置变量仍指向 v1；仅允许 v1/v2，禁止任意路径。已有目录/材料拒绝覆盖。
本轮完成后记录实际完成数、失败原因及费用，不因结果不理想中途改提示词再续跑。
