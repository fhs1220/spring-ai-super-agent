# RL v10 最终精准度验收计划

## 当前状态

```text
STATE: READY_FOR_EXPLICIT_REPLAY_AUTHORIZATION
VALIDATION: policy-v10-precision-30x2
PLANNED TRAJECTORIES: 60
MODEL CALLS DURING IMPLEMENTATION/FREEZE: 0
BILLABLE OPERATIONS DURING IMPLEMENTATION/FREEZE: 0
```

这是 v9 `NOT_PROMOTED` 后的唯一修复验收，不增加 Judge、人工标注、云训练或额外试跑。
真实执行结束后直接给出 `PRECISION_UPGRADE_VALIDATED` 或 `NOT_PROMOTED`。

## 冻结身份

| 项目 | 冻结值 |
|---|---|
| Validation / Batch | `policy-v10-precision-30x2` |
| 候选 / 配对基线 | `agentic-rag-v10` / `agentic-rag-v9` |
| Selector | `deterministic-rlvr-selector-v3` |
| 答案契约 | `answer-verification-contract-v1` |
| 问题 / 轮次 / 轨迹 | 30 / 2 / 60 |
| Single / Multi 轨迹 | 30 / 30 |
| Freeze fingerprint | `51df7296a65c1cbd1ef0bb39abe57b1c5b17d24453630c9ad0a31c9eb90abc57` |
| Plan fingerprint | `a500b84da155b96acc6d45741b3bbaa6552e09a940da146a1f528c306bff30e4` |
| Plan file fingerprint | `0bb6927028c66784f7f67349e4ec8bd62707f80f68cbe8aa987266bd435864c5` |
| Selected seeds fingerprint | `d048cda142daff4f8d8aa5b3495679725359a3650c8742a61f585c896300ee28` |

v10 与 v9 使用完全相同的 30 个高风险问题集合，但按 v9 失败风险重新确定顺序，并生成全新
Batch、runId、Plan 和 Freeze 身份；这不是恢复或改写 v9。

本地冻结证据位于：

`tmp/agent-rl/precision-validation/policy-v10-precision-30x2/`

## v9 配对基线

| 指标 | 历史值 |
|---|---:|
| 轨迹 | 60 |
| RLVR v3 平均分 | 0.753685 |
| 最低 / 最高 | 0.687795 / 0.843035 |
| 硬门禁失败 / 违规 | 0 / 0 |
| 模型调用 | 369 |
| Token | 669,193 |
| 估算费用 | ¥0.2223954 |

## 授权上限

受限修正最多执行两次。按 v9 完整成本增加 30% 余量：

| 指标 | 授权上限 |
|---|---:|
| 模型调用 | 480 |
| Token | 869,951 |
| 估算费用 | ¥0.289114 |

达到任一上限必须停止，不扩大预算。

## 一次性退出门禁

`PRECISION_UPGRADE_VALIDATED` 必须同时满足：

1. 60/60 完成且策略、Batch、Freeze、Plan 身份一致；
2. v10 平均 RLVR v3 ≥ 0.75；
3. 相对同题 v9 的平均差 ≥ 0；
4. 30 个逐题差值的配对 Bootstrap 95% CI 下界 ≥ -0.02；
5. 路由偏差、超时、RLVR 违规均为 0；
6. 最终逐项契约通过率为 100%；
7. Review、全部 Revise 和 Selector 契约轨迹完整；
8. 每条轨迹 Revise 最多 2 次且 attempt 序号完整；
9. Selector 版本全部为 `deterministic-rlvr-selector-v3`；
10. 完整 RLVR 退化选择为 0。

任一失败即 `NOT_PROMOTED`，不移动阈值、不删除失败轨迹、不追加新批次。

## 冻结执行命令

获得明确授权后只执行：

```bash
AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true \
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/precision-validation/policy-v10-precision-30x2/selected-seeds.jsonl \
  --batch-id policy-v10-precision-30x2 \
  --policy-version agentic-rag-v10 \
  --rounds 2 \
  --minimum-rlvr-average 0.75 \
  --maximum-route-mismatches 0 \
  --maximum-timeouts 0 \
  --maximum-rlvr-violations 0 \
  --output tmp/agent-rl/precision-validation/policy-v10-precision-30x2/replay.json \
  --execute
```

完成后执行零调用评测：

```bash
python3 bailian-agent-rl/evaluate_v10_precision_validation.py \
  --manifest tmp/agent-rl/precision-validation/policy-v10-precision-30x2/manifest.json \
  --replay tmp/agent-rl/precision-validation/policy-v10-precision-30x2/replay.json \
  --trajectories tmp/agent-rl/trajectories \
  --output tmp/agent-rl/precision-validation/policy-v10-precision-30x2/evaluation-report.json
```

当前 Dry Run 已验证 Plan fingerprint 一致，评测器会拒绝 0/60 的不完整计划。

## 授权边界

真实执行需要用户明确回复：

```text
授权执行 v10 精准度 30×2 真实回放
```

