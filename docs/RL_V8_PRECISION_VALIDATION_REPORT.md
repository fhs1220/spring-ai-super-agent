# RL v8 精准度真实验证报告

## 最终结论

`agentic-rag-v8` 未通过冻结的精准度门禁，结论为：

```text
DECISION: NOT_PROMOTED
REPLAY STATE: STOPPED_BY_FROZEN_RLVR_GATE
COMPLETED: 1 / 60
MODEL CALLS: 4
TOKENS: 5,995
ESTIMATED COST: ¥0.0018879
TIMEOUTS: 0
```

这不是服务故障或评分器误报。唯一完成的轨迹已经暴露真实回答缺陷，回放器按照执行前冻结
的 `maximum_rlvr_violations=0` 自动止损。没有放宽门禁、删除失败样本或继续消耗剩余
59 条预算，也没有追加第二批回放。

## 冻结身份

| 项目 | 值 |
|---|---|
| Batch | `policy-v8-precision-30x2` |
| 策略 | `agentic-rag-v8` |
| 计划 | 30 题 × 2 轮，共 60 条 |
| Freeze fingerprint | `8a777df77ff905eec8529121ce8d847094ba21e9cda0733db0699fa00496c746` |
| Plan fingerprint | `84465fd9d038ebfbe57806d9f6b1a0f32b76340d9431b811ae2db7bbe9c8e070` |
| 实际完成 | 1 条 |
| 停止原因 | `rlvr_violations_exceeded` |

本地原始证据保存在
`tmp/agent-rl/precision-validation/policy-v8-precision-30x2/replay.json` 和
`tmp/agent-rl/trajectories/6b37a583-661e-45bc-a52f-82a274751064.json`。

## 首条轨迹结果

问题要求提供一次十五分钟沟通流程和可直接使用的话术，并只使用有依据的建议。

| 指标 | 结果 |
|---|---:|
| 执行模式 | `SINGLE_AGENT` |
| 路由预期 | 匹配 |
| 在线 Reward | 0.95 |
| RLVR v3 | 0.659771 |
| RLVR 硬门禁 | 通过 |
| RLVR 违规 | `instruction_contract_incomplete` |
| 模型调用 | 4 |
| Token | 5,995 |
| 估算费用 | ¥0.0018879 |
| 超时 | 0 |

回答确实给出了 2+3+4+3+3 分钟的流程和部分直接话术，但没有覆盖冻结验证契约要求的
“兴趣爱好、社交圈、独立、平衡”等核心概念，任务完成质量只有 `0.583333`。因此
`instruction_contract_incomplete` 是由真实内容缺失触发，不是引用格式或评分解析错误。

## v8 为什么没有纠正

v8 的确定性 RLVR 选择器只在 Review/Revise 已经产生初稿和修订稿两份候选时工作。本条
轨迹的 Reviewer 判断“无需修正”，没有生成修订候选，也就没有 `RLVR_SELECT` 事件。
因此 v8 能阻止“较差修订稿覆盖较好初稿”，但不能纠正 Reviewer 未发现的契约遗漏。

这次真实回放证明了 v8 的能力边界：

1. 路由、检索、引用和服务稳定性正常；
2. 在线静态 Reward 的 0.95 未能识别逐项契约缺失；
3. 离线 RLVR v3 正确识别缺失并触发止损；
4. 仅做候选选择不足以稳定提高最终回答精准度，生成或 Review 阶段必须获得同一份可执行
   契约并逐项检查。

## 一次性门禁处置

冻结计划要求 60/60 完成、平均 RLVR 至少 0.70、违规为 0，才能运行完整配对评估并判定
`PRECISION_UPGRADE_VALIDATED`。首条已经使“违规为 0”不可满足，回放器因此立即终止。

零调用评测器也按冻结契约拒绝把 1/60 的不完整样本伪装成完整验证，返回：

```text
Precision validation evaluation failed: Precision replay is not fully completed
```

所以本报告不提供没有统计意义的均值差或 Bootstrap 置信区间，也不宣称完成 60 条验证。

## 后续工程方向

下一版不应重跑 v8 或移动门禁，而应创建新的策略身份：

1. 将 `verification_contract` 转成 Generate 与 Review 都能逐项执行的检查清单；
2. 当确定性检查发现缺项时，即使模型 Reviewer 说“无需修正”，也强制进入一次受限修订；
3. 修订后继续使用 v8 的非退化候选选择器，避免修订造成倒退；
4. 先通过零模型调用的契约单元测试和历史轨迹反事实评估，再另行冻结新的真实验证。

本次 `policy-v8-precision-30x2` 已经终结，不重复执行。
