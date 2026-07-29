# RL Stage 5 静态 Reward / RLVR 数据冻结报告

## 结论

静态 Reward、`RLVR_ONLY` 与 `RLVR_RLAIF` 三臂的数据包已经离线冻结并通过百炼提交前
检查；`FULL_TRAJECTORY_GUIDED` 仍需跨策略奖励轨迹。
本轮没有调用模型、没有创建云资源、没有产生计费操作。

## 冻结来源

- Stage 4 Batch：`policy-v7-stage4-judge-119`；
- Policy：`agentic-rag-v7`；
- Stage 4 计划指纹：
  `d40dfa5753a44c5f988e83003b72f780973e8a71d2141e876da725bce650c998`；
- Stage 3 回放计划指纹：
  `3230c4faf444af17f09aa70f3b44f0cca311e119729132dd373775cae2347c94`；
- 数据冻结指纹：
  `d378f0b12796a4a70a315e715098cb37313064a721793515b5f0d5d6065de188`。

冻结器逐条绑定 Stage 3 真实回放结果和 RLVR v3 明细，不使用轨迹文件中的旧在线 Reward
替代 RLVR v3。119 条唯一问题全部满足完成状态、RLVR ≥ 0.70、硬门禁通过、违规为空、
Policy 和轨迹身份一致。

## 数据切分与污染检查

- 唯一轨迹：119；
- 训练集：95；
- 验证集：24；
- Batch Size：64；
- 训练/验证问题重叠：0；
- 固定 Benchmark：36 条；
- Benchmark 最大 3-gram Jaccard：0.172131；
- 相似或包含污染：0；
- Benchmark 指纹：
  `da9b7e624747e1c3301ac423999e74c014d637dc9753c9d3c227b718d167f07b`。

静态 Reward 与 `RLVR_ONLY` 有意使用完全相同的训练/验证样本，后续分别训练成不同模型
资产，以隔离 Reward 函数变化。两臂数据指纹相同，但训练配置指纹不同：

| 实验臂 | 训练/验证 | 配置指纹 | Readiness |
|---|---:|---|---|
| `BASELINE_STATIC_REWARD` | 95 / 24 | `122fcf7395f6e00d1e4a96aa65c7b4760b2e7391efbf54fd7ae4ca2c37a771af` | 通过 |
| `RLVR_ONLY` | 95 / 24 | `91f090141e81314a9a2059e392050c4979a009b37ab893462845d2a902f29de5` | 通过 |
| `RLVR_RLAIF` | 66 / 7 | `b20ae29fc1004dbca341224c754ba0cb67f6d1f34f9da0b82cc7d4900494c388` | 通过 |

三次 `submit_job.py` Dry Run 均通过；未提供 `--execute`，所以没有启动付费训练。

## 被阻断的实验臂

`FULL_TRAJECTORY_GUIDED` 仍被阻断。它必须在 A/B/C 产生不同的新模型资产后，对匹配
问题执行跨策略回放并形成真实奖励轨迹；不能把同一 v7 策略的随机重复回答改名为策略演化。
