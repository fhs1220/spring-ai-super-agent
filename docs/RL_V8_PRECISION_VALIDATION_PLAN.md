# RL v8 精准度最终验证计划

## 结论

`agentic-rag-v8` 的最终精准度验证集、真实回放计划和回放后评测器已经离线冻结，并于
2026-07-30 获得授权后按冻结命令执行。当前状态：

```text
STATE: STOPPED_BY_FROZEN_RLVR_GATE
DECISION: NOT_PROMOTED
COMPLETED: 1 / 60
MODEL CALLS: 4
TOKENS: 5,995
ESTIMATED COST: ¥0.0018879
```

首条轨迹出现真实的 `instruction_contract_incomplete`，RLVR v3 为 `0.659771`。
回放器按预先冻结的零违规门禁自动止损，没有强行执行剩余 59 条，也不重复本批次。
完整处置和根因见
[`RL_V8_PRECISION_VALIDATION_REPORT.md`](RL_V8_PRECISION_VALIDATION_REPORT.md)。

## 冻结身份

| 项目 | 冻结值 |
|---|---|
| Validation / Batch | `policy-v8-precision-30x2` |
| 候选策略 | `agentic-rag-v8` |
| 历史基线 | `agentic-rag-v7` |
| Selector | `deterministic-rlvr-selector-v1` |
| 问题 / 轮次 / 计划轨迹 | 30 / 2 / 60 |
| Single / Multi 问题 | 15 / 15 |
| Single / Multi 计划轨迹 | 30 / 30 |
| Freeze fingerprint | `8a777df77ff905eec8529121ce8d847094ba21e9cda0733db0699fa00496c746` |
| Plan fingerprint | `84465fd9d038ebfbe57806d9f6b1a0f32b76340d9431b811ae2db7bbe9c8e070` |

本地不可变证据位于
`tmp/agent-rl/precision-validation/policy-v8-precision-30x2/`，包含：

- `selected-seeds.jsonl`
- `plan.json`
- `manifest.json`
- `replay.json`（当前为零调用 Dry Run）

`tmp/` 按项目约定不提交 Git；仓库提交可复现脚本、配置、测试和本计划。

## 为什么选择这 30 个问题

这不是从 Stage 3 随机抽题，而是冻结的高风险分层验证：

1. 纳入全部 13 个历史上至少一轮触发 `REVISE` 的问题，共覆盖 14 条 Revise 轨迹；
2. Single/Multi 各选择 15 个问题；
3. 每种执行模式对 actions、weekly plan、checklist、dialogue、decision 至少覆盖 2 题；
4. 剩余名额按历史硬门禁失败、RLVR 违规、契约风险、低分和双轮波动确定性排序。

最终请求类型覆盖：

| 类型 | 问题数 |
|---|---:|
| actions | 5 |
| weekly_plan | 12 |
| checklist | 4 |
| dialogue | 5 |
| decision | 4 |

七天计划占比较高是有意的：它同时要求较长回答、七天完整结构、禁止追问、必要概念和复盘，
是最容易暴露 Generate/Revise 契约退化的任务，不是随机分布估计。

## 冻结的 v7 高风险基线

所选 30 题在 Stage 3 已有两轮 v7 真实轨迹：

| 指标 | v7 历史值 |
|---|---:|
| 轨迹 | 60 |
| RLVR v3 平均分 | 0.697469 |
| 最小 / 最大 | 0 / 0.851854 |
| 硬门禁失败 | 3 |
| 有违规轨迹 | 5 |
| 底层模型调用 | 365 |
| Token | 645,590 |
| 估算费用 | ¥0.2129019 |

该基线低于 Stage 3 全集不是退化，而是验证集主动集中在历史失败和修订风险上。

## 预算

真实 v8 计划与历史基线同为 60 条轨迹。按所选问题的实际 v7 消耗估算，并冻结 20% 授权
余量：

| 指标 | 历史估算 | 授权上限 |
|---|---:|---:|
| 模型调用 | 365 | 438 |
| Token | 645,590 | 774,708 |
| 估算费用 | ¥0.2129019 | ¥0.2554823 |

上限是本次授权范围，不代表云端硬停损能力；若出现外部欠费、持续超时或身份不一致，停止并
保留证据，不自动扩大样本。

## 一次性退出门禁

回放后由 `evaluate_v8_precision_validation.py` 离线重算初稿、修订稿和最终回答的 RLVR
v3，不再调用模型。`PRECISION_UPGRADE_VALIDATED` 必须同时满足：

1. 60/60 完成且策略、Batch、Plan 指纹一致；
2. v8 平均 RLVR ≥ 0.70；
3. 相对同题 v7 历史均值的平均差 ≥ 0；
4. 30 个逐题差值的配对 Bootstrap 95% CI 下界 ≥ -0.02；
5. 路由偏差、超时和 RLVR 违规均为 0；
6. `RLVR_SELECT` 轨迹字段完整；
7. 最终选择的完整 RLVR 不低于同次初稿和修订稿中的更高者，退化选择为 0。

如果没有足够的 `RLVR_SELECT` 事件，报告只限制“选择器直接收益”的结论，但不会再自动追加
一批回放；整体 v8 质量仍按固定门禁一次性判定。门禁失败则结论为 `NOT_PROMOTED`，不得
移动阈值或反复重跑制造通过。

## 已冻结命令

离线冻结和 Dry Run 已完成，两个 Plan fingerprint 完全一致。获得真实授权后只执行：

```bash
AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true \
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/precision-validation/policy-v8-precision-30x2/selected-seeds.jsonl \
  --batch-id policy-v8-precision-30x2 \
  --policy-version agentic-rag-v8 \
  --rounds 2 \
  --minimum-rlvr-average 0.70 \
  --maximum-route-mismatches 0 \
  --maximum-timeouts 0 \
  --maximum-rlvr-violations 0 \
  --output tmp/agent-rl/precision-validation/policy-v8-precision-30x2/replay.json \
  --execute
```

完成后执行零调用评测：

```bash
python3 bailian-agent-rl/evaluate_v8_precision_validation.py \
  --manifest tmp/agent-rl/precision-validation/policy-v8-precision-30x2/manifest.json \
  --replay tmp/agent-rl/precision-validation/policy-v8-precision-30x2/replay.json \
  --trajectories tmp/agent-rl/trajectories \
  --output tmp/agent-rl/precision-validation/policy-v8-precision-30x2/evaluation-report.json
```

当前 Dry Run Manifest 已验证评测器会拒绝未完成回放，不会把计划状态误判为真实证据。

## 工程验证

- Java 全量测试：129/129 通过；
- `AgenticRagServiceTest`：14/14 通过；
- Python 全量测试：96/96 通过；
- v8 冻结与评测专项测试：4/4 通过；
- Freeze / Dry Run 的 Plan fingerprint 完全一致；
- `git diff --check`：通过。
