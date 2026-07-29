# RL Stage 3 轨迹采集计划

## 结论

Stage 3 已完成离线冻结、Dry Run 和真实执行。150 个唯一问题每题 2 轮，共得到 300 条
候选轨迹；Single/Multi 各 150。最终 119 个问题通过高置信筛选，超过 82 个退出门槛。
完整结果见 [`RL_STAGE3_COLLECTION_REPORT.md`](RL_STAGE3_COLLECTION_REPORT.md)。

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

真实执行已按相同参数和显式授权完成。该命令与冻结身份保留在此，供审计复现；不得使用
相同 Batch 重写现有结果。
