# System One 路由校准方案

## 目标

Jev/Laya 不负责生成最终回答，只预测两个互不替代的动作：

1. `recommendedMultiAgent`：多 Agent 的协作收益是否足以覆盖额外成本与延迟；
2. `recommendedSafetyGuard`：是否需要触发独立安全处置。

核心目标是识别真正由多 Agent 获益的少数场景，而不是把“多个主题”或“安全风险”直接等价为
多 Agent。冻结的 36 题 Benchmark 只用于最终回归，不能用于提示词、阈值或校准器调参。

## 数据隔离

- Calibration/Development：新增且与冻结 Benchmark 在语义和模板上去重的样本，用于提示词与
  阈值选择；至少覆盖多 Agent positive、单 Agent hard negative、安全 positive/negative。
- Frozen Benchmark：现有 36 题，仅在候选方案确定后运行一次正式评测。
- Production Shadow：只记录输入特征、预测、最终执行与匿名化奖励，不自动改变生产路由。

当前 `system-one-calibration-v1.jsonl` 是 24 条人工审阅的 bootstrap 开发集：6 条多 Agent
positive、18 条 single/hard negative，其中 4 条需要安全护栏。它用于低成本筛选提示词与阈值，
后续应逐步用非冻结样本的强制单/多 Agent 实测效用标签替换人工标签。

多 Agent 标签由同题强制单/多 Agent 的实测效用决定：

```text
utility = quality - costWeight * normalizedCost - latencyWeight * normalizedLatency
multi = utility(forcedMulti) > utility(forcedSingle)
```

安全标签单独人工复核，不能从多 Agent 标签推导。

## 调整与选择

1. 在开发集收集 Jev 原始 `multiAgentProbability` 和 `safetyProbability`。
2. 只在开发集比较提示词版本、multi-Agent 阈值及概率校准方法。
3. 候选优先满足安全漏报为 0，然后最大化多 Agent 平衡准确率，并检查 precision、recall、效用
   遗憾、成本和延迟。
4. 锁定配置与数据指纹后再运行冻结 36 题；不根据冻结结果继续调参。若失败，建立新版本开发集，
   下一次冻结评测作为新的预注册运行。

默认发布门禁包括：可用率 ≥99%、路由准确率 ≥80%、平衡准确率 ≥70%、多 Agent recall ≥50%、
安全 false negative 为 0、效用遗憾不增加，以及配对质量通过 2% 非劣效检验。正式简历数字只能
来自带运行 ID、Benchmark SHA-256、模型版本和原始报告哈希的冻结评测。

启用评测 API 后执行：

```http
POST /api/agent-evaluation/system-one-calibration/runs
```

返回的推荐阈值来自 0.00–1.00、步长 0.05 的扫描：先要求 multi-Agent recall 不低于 50%，
再按平衡准确率、precision 和更高阈值依次择优。推荐值不会自动写入生产配置。

## Calibration v1 结果

2026-09-26 使用 `OPENROUTER_JEV:typesafe/jev-1.13-20260917` 完成 24 条真实判断：

- 数据集 SHA-256：`9fa28d51a0f42560b869516d0e1f1d004604ab798091e67225c597ea0f534291`
- 可用率：24/24（100%）
- 原阈值 0.65：accuracy 75%，multi-Agent precision/recall 为 0/0，balanced accuracy 50%
- 推荐阈值 0.20：accuracy/precision/recall/balanced accuracy 均为 100%
- 安全护栏：precision/recall 均为 100%，false positive/negative 均为 0
- 概率区间：single/hard negative 为 0.04–0.16，multi-Agent positive 为 0.23–0.36
- 用量与开销：输入 20,811 Token、输出 2,592 Token、估算费用 `$0.00087406`
- 平均决策延迟：171.04ms

因此候选默认阈值锁定为 `0.20`，下一步只允许用冻结 36 题做一次正式回归判断，不再根据该冻结
结果反向调整本候选。人工 bootstrap 集上的满分不能表述为生产准确率或简历效果数据。

## Laya challenger

同一 24 条开发集为本地 `laya-multilingual` 单独选择了 `0.75` 阈值。冻结回放中 Laya 的
multi-Agent recall / balanced accuracy 为 75.00% / 71.43%，但安全 recall 为 0%，且整体
accuracy 69.44% 未过门禁。因此 Laya 只作为影子 challenger，不复用 Jev 的 `0.20` 阈值，
也不接管安全护栏或生产执行路由。完整对照见
[`JEV_LAYA_SHADOW_COMPARISON.md`](JEV_LAYA_SHADOW_COMPARISON.md)。
