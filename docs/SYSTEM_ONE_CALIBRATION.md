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
