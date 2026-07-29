# RL Stage 3 轨迹采集计划

## 结论

Stage 3 已完成离线冻结和 Dry Run，未启动后端、未调用模型、未产生费用。首批计划采集
150 个唯一问题，每题 2 轮，共 300 条候选轨迹；Single/Multi 各 150。候选中的坏样本
会被逐题筛除，不再沿用 Stage 2“首个违规即终止整批”的 Release 验收逻辑。

## 冻结身份

| 项目 | 冻结值 |
|---|---|
| Batch | `policy-v7-v2-stage3-150x2` |
| Policy | `agentic-rag-v7` |
| Seed schema | `agent-rl-trajectory-seed-v2` |
| Reward schema | `human-light-rlvr-v3` |
| Gate profile | `collection` |
| 计划指纹 | `3230c4faf444af17f09aa70f3b44f0cca311e119729132dd373775cae2347c94` |
| 唯一问题 / 轮次 / 候选轨迹 | 150 / 2 / 300 |
| Single / Multi | 150 / 150 |
| 关系 / 育儿 / 家务 / 财务标签运行 | 252 / 108 / 108 / 70 |
| Dry-run 调用 / 付费操作 | 0 / 0 |

本地 Dry Run Manifest：
`tmp/agent-rl/replays/policy-v7-v2-stage3-150x2.json`。`tmp/` 不提交 Git。

## 高置信问题资格

资格契约版本为 `paired-high-confidence-v1`。一个问题只有在两轮轨迹都同时满足以下条件时，
才计入高置信训练候选：

1. 状态为 `COMPLETED`；
2. 实际路由与冻结预期一致；
3. RLVR 硬门禁通过且违规为 0；
4. 每轮 RLVR 均不低于 0.70；
5. 当前轨迹和恢复审计的超时均为 0。

退出门禁是至少 82 个合格唯一问题，并且 300 个计划 Run 全部得到评估。Manifest 会输出
合格 Seed ID、淘汰 Seed 及逐项原因计数，供后续 Judge 和训练数据导出使用。

Stage 2 的 50 个问题按同一资格契约离线回算为 41/50 合格，经验合格率约 82%。若该比例
保持，150 个问题预计约留下 123 个，已为 20% 验证集和 Judge 淘汰预留余量。该值只是
预算预测，不是 Stage 3 结果。

## 实测预算

按 v7 Stage 2 的 100 条完成轨迹线性估算：

| 指标 | Stage 2 实测均值 | Stage 3 300 条预测 |
|---|---:|---:|
| 底层模型调用 | 6.34 / 条 | 约 1,902 |
| Token | 11,059.73 / 条 | 约 3,317,919 |
| 估算费用 | ¥0.003616176 / 条 | 约 ¥1.0848528 |

建议真实授权预算按 20% 余量控制在约 ¥1.31。该数字不是云端硬限额；后端启动调用、
失败后未返回的用量和模型价格变化可能使实际账单不同。

## 已冻结命令

Dry Run：

```bash
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/seeds/training-seeds-v2.jsonl \
  --batch-id policy-v7-v2-stage3-150x2 \
  --policy-version agentic-rag-v7 \
  --rounds 2 \
  --limit 150 \
  --minimum-qualified-seeds 82 \
  --minimum-qualified-rlvr 0.70 \
  --output tmp/agent-rl/replays/policy-v7-v2-stage3-150x2.json
```

真实执行需在相同参数后增加 `--execute`，同时设置
`AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true` 并启动 v7 后端。它会产生约 300 条真实模型
操作，必须再次取得明确授权；本次仅完成离线准备。

