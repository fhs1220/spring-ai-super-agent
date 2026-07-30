# RL v9 最终精准度验证计划

## 最终状态

本计划已于 2026-07-30 按冻结身份执行完毕：

```text
STATE: COMPLETED
DECISION: NOT_PROMOTED
COMPLETED TRAJECTORIES: 60/60
MODEL CALLS: 369
ESTIMATED COST: ¥0.2223954
```

v9 的 RLVR v3 相对 v7 平均提升 `+0.056216`，配对 95% CI 为
`[+0.019671, +0.102561]`；但最终契约通过率仅 `26/60`，出现 5 次 RLVR 退化选择和
1 次已恢复模型超时，因此严格门禁拒绝发布。完整结果见
[`RL_V9_PRECISION_VALIDATION_REPORT.md`](RL_V9_PRECISION_VALIDATION_REPORT.md)。

## 冻结前状态

`agentic-rag-v9` 的唯一一次最终真实验证已经离线冻结：

```text
STATE: READY_FOR_EXPLICIT_REPLAY_AUTHORIZATION
VALIDATION: policy-v9-precision-30x2
PLANNED TRAJECTORIES: 60
MODEL CALLS DURING FREEZE: 0
BILLABLE OPERATIONS DURING FREEZE: 0
```

本计划不增加 Judge、人工标注、云训练或另一批试跑。真实执行完成或按门禁提前止损后，
直接给出 `PRECISION_UPGRADE_VALIDATED` 或 `NOT_PROMOTED`。

## 冻结身份

| 项目 | 冻结值 |
|---|---|
| Validation / Batch | `policy-v9-precision-30x2` |
| 候选策略 | `agentic-rag-v9` |
| 历史基线 | `agentic-rag-v7` |
| 答案契约 | `answer-verification-contract-v1` |
| Selector | `deterministic-rlvr-selector-v2` |
| 问题 / 轮次 / 轨迹 | 30 / 2 / 60 |
| Single / Multi 问题 | 15 / 15 |
| Single / Multi 轨迹 | 30 / 30 |
| Freeze fingerprint | `02a1515abc914f7f8b5f54585703473170ae82612fb5a8cff4c1c1ad2c4b9e2f` |
| Plan fingerprint | `8ce34bd9cb79fd0290012fd0fac23052c54123add2fae229b7132fb9223802d1` |
| Selected seeds fingerprint | `d9228fec6bdcb5c38b14eba42b3587dbfbb6d1a4404818e7effd1afe6fe18a97` |

本地不可变证据位于
`tmp/agent-rl/precision-validation/policy-v9-precision-30x2/`：

- `selected-seeds.jsonl`
- `plan.json`
- `manifest.json`
- `replay.json`（当前为零调用 Dry Run）

Freeze 与 Dry Run 的 Plan fingerprint 完全一致。所选种子指纹与 v8 高风险集一致，但 Batch、
runId、策略和 Plan 指纹全部重新生成；这不是恢复或重跑已经终结的 v8 Batch，而是新策略
对同一冻结历史基线的配对验证。

## 样本与历史基线

请求类型覆盖：

| 类型 | 问题数 |
|---|---:|
| actions | 5 |
| weekly_plan | 12 |
| checklist | 4 |
| dialogue | 5 |
| decision | 4 |

同题 v7 历史基线：

| 指标 | 历史值 |
|---|---:|
| 轨迹 | 60 |
| RLVR v3 平均分 | 0.697469 |
| 最小 / 最大 | 0 / 0.851854 |
| 硬门禁失败 | 3 |
| 有违规轨迹 | 5 |
| 底层模型调用 | 365 |
| Token | 645,590 |
| 估算费用 | ¥0.2129019 |

## 预算与预计时间

v9 在最坏情况下可能比 v7 多一次受限 Revise，并且提示词包含完整契约，因此授权余量从
20% 调整为 30%：

| 指标 | 授权上限 |
|---|---:|
| 模型调用 | 475 |
| Token | 839,267 |
| 估算费用 | ¥0.2767725 |

该上限仍远低于“几十元”预算。正常执行预计约 20～40 分钟；若任何轨迹出现 RLVR 违规，
冻结的零违规门禁会立即止损，实际时间和费用更低。加上零调用评测、报告和推送，预计
30～60 分钟完成 RL 最终结论。

## 一次性退出门禁

`PRECISION_UPGRADE_VALIDATED` 必须同时满足：

1. 60/60 完成，策略、Batch、Freeze 与 Plan 身份一致；
2. v9 平均 RLVR v3 ≥ 0.70；
3. 相对同题 v7 历史均值的平均差 ≥ 0；
4. 30 个逐题差值的配对 Bootstrap 95% CI 下界 ≥ -0.02；
5. 路由偏差、超时和 RLVR 违规均为 0；
6. 最终逐项契约通过率为 100%；
7. Review、Revise 与 `RLVR_SELECT` 的契约轨迹字段完整；
8. 所有 Selector 事件版本均为 `deterministic-rlvr-selector-v2`；
9. 完整 RLVR 退化选择为 0。

评测器额外报告：

- 初稿直接契约通过数；
- 确定性契约强制 Revise 数；
- 最终契约通过数和通过率；
- Selector 保留初稿/采用修订稿的数量；
- RLVR、配对差值、置信区间、路由、超时、Token 和费用。

门禁失败即 `NOT_PROMOTED`，不移动阈值、不删除失败轨迹、不追加新批次。

## 已冻结命令

获得新的真实模型调用授权后，只执行：

```bash
AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true \
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/precision-validation/policy-v9-precision-30x2/selected-seeds.jsonl \
  --batch-id policy-v9-precision-30x2 \
  --policy-version agentic-rag-v9 \
  --rounds 2 \
  --minimum-rlvr-average 0.70 \
  --maximum-route-mismatches 0 \
  --maximum-timeouts 0 \
  --maximum-rlvr-violations 0 \
  --output tmp/agent-rl/precision-validation/policy-v9-precision-30x2/replay.json \
  --execute
```

完成后执行零调用评测：

```bash
python3 bailian-agent-rl/evaluate_v9_precision_validation.py \
  --manifest tmp/agent-rl/precision-validation/policy-v9-precision-30x2/manifest.json \
  --replay tmp/agent-rl/precision-validation/policy-v9-precision-30x2/replay.json \
  --trajectories tmp/agent-rl/trajectories \
  --output tmp/agent-rl/precision-validation/policy-v9-precision-30x2/evaluation-report.json
```

当前 Dry Run 已验证评测器会拒绝 0/60 的不完整回放，不会把计划误判成真实结果。

## 授权边界

离线冻结不构成模型调用授权。真实执行需要用户明确回复：

```text
授权执行 v9 精准度 30×2 真实回放
```
