# Jev 冻结基准决策回放报告

## 结论

候选提示词与阈值 `0.20` 在独立开发集锁定后，使用首次完整 36 题运行中已经实测的强制单/
多 Agent 结果作为固定反事实，只重新调用 Jev。回放 36/36 可用，安全识别 4/4 且零误报；
multi-Agent 路由准确率从当前规则的 47.22% 提升到 75.00%，但只召回 8 个效用 oracle 正例中的
1 个，balanced accuracy 仅 52.68%，未通过发布门禁。

因此 Jev 目前适合作为独立安全护栏和保守的多 Agent 成本过滤器，尚不能接管 Cortex 的完整
执行路由。

## 证据身份

- 冻结 Benchmark：`love-rag-benchmark-v2`
- Benchmark SHA-256：`f27bb073a32093756a73e2b6f0255da5f3d4184c951dfa3529a12f39e1c044b1`
- 固定反事实来源 Run ID：`rag-ab-1e95a150-dafb-4148-be1c-2135dc33d1b2`
- 回放数据 SHA-256：`bfe6819ca9196c1752331f6c20b0be7c499a7feee73b1d32340d6f9d0dcf9246`
- Jev：`OPENROUTER_JEV:typesafe/jev-1.13-20260917`
- 样本：36（效用 oracle multi-Agent positive 8，safety positive 4）
- 候选阈值：0.20（回放前已在独立开发集锁定）

## 路由指标

| 指标 | 当前路由 | Jev 0.20 | 变化 |
|---|---:|---:|---:|
| Accuracy | 47.22% | 75.00% | +27.78pp |
| Multi-Agent precision | 21.05% | 33.33% | +12.28pp |
| Multi-Agent recall | 50.00% | 12.50% | -37.50pp |
| Specificity | 46.43% | 92.86% | +46.43pp |
| Balanced accuracy | 48.21% | 52.68% | +4.47pp |

Jev 选择 3/36 走多 Agent，其中 1 个 true positive、2 个 false positive、7 个 false negative。
阈值扫描没有找到同时达到 70% balanced accuracy 和 50% recall 的切分点，因此不能再靠调阈值
解决；继续用冻结结果选阈值也会构成数据泄漏。

## 独立安全指标

- Precision：100%（4/4）
- Recall：100%（4/4）
- False positive / false negative：0 / 0
- 四个安全样本概率：0.93–0.98

安全概率不参与 multi-Agent 执行动作，证明本次解耦生效。

## 固定反事实下的工程结果

| 指标 | 当前路由 | Jev 0.20 | 变化 |
|---|---:|---:|---:|
| 平均质量 | 0.9077 | 0.9403 | +0.0326 |
| 平均效用遗憾 | 0.0690 | 0.0268 | -61.1% |
| 路径总成本 | CNY 19.4680 | CNY 10.8489 | -44.3% |
| 平均路径延迟 | 35.24s | 21.29s | -39.6% |

这些数值来自同一批已生成答案的反事实重放，适合说明策略选择的潜在工程收益；它们不是新一轮
独立生成实验，也没有证明统计显著的线上质量提升。

Jev 决策自身使用输入 31,318 Token、输出 3,888 Token，估算费用 USD 0.00131536，平均延迟
174.31ms，可用率 100%。

## 无效运行记录

两次完整重跑均遭遇宿主网络 `No route to host`，分别导致 19–20 和 27–28 条生成路径失败，且
批量 Jev 阶段均为 0/36 可用。Run ID 为：

- `rag-ab-303160bf-5944-418f-9580-919564e625b1`
- `rag-ab-302df7c4-041e-44f6-bc77-129f3532513f`

两次运行只证明 fail-open 与发布门禁有效，不能用于模型效果或简历数据。
