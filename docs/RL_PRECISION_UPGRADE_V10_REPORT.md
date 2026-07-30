# RL 精准度升级 v10 工程报告

## 结论

`agentic-rag-v10` 已完成离线工程修复，目标是关闭 v9 真实验证暴露的两条缺陷：

1. Reviewer 直接返回的修订稿可能绕过确定性契约修正；
2. Selector 的简化契约分数平分时无法稳定保持完整 RLVR v3 不退化。

本阶段只执行代码、单元测试、历史轨迹反事实审计和验证冻结，模型调用与费用均为 0。

## 控制流修复

v10 对 Reviewer、Revise 和 Selector 采用统一边界：

- Reviewer 修订稿在进入 Selector 前必须重新执行完整
  `answer-verification-contract-v1`；
- 任一候选仍有逐项遗漏时，进入最多两次的受限契约修正；
- 每次修正把 `attempt`、`maximumAttempts`、契约结果和缺失项写入轨迹；
- 两次后仍不满足时不隐藏失败，由最终 100% 契约门禁拒绝。

该实现不会通过盲目拼接关键词伪造通过；修正仍必须由 RAG 上下文支撑，并保留有效引用。

## Selector v3

`deterministic-rlvr-selector-v3` 先比较完整契约、引用、长度和任务完成确定性分数。基础分相同
时，再使用与 RLVR v3 相同权重的两个可在线计算信号：

- token F1 / reference proxy：0.10；
- grounding bigram coverage：0.20。

在线 reference proxy 使用本次检索上下文，不读取 Benchmark 的冻结参考答案。只有修订稿
综合排序严格高于初稿时才替换，否则保留初稿。

## v9 历史反事实审计

审计输入为 v9 已终结的 60 条真实轨迹，未调用模型：

| 指标 | 结果 |
|---|---:|
| v9 最终契约失败 | 34 |
| Reviewer 修订绕过路径 | 23 |
| 一次 Revise 后仍失败 | 11 |
| v10 受限修正覆盖 | 34/34 |
| v9 RLVR 退化选择 | 5 |
| 发生在基础分平分 | 5/5 |
| Selector v3 反事实退化 | 0 |

反事实结果只证明失败路径覆盖和排序规则修复，不代替新的真实回答质量验证。

本地审计证据：

`tmp/agent-rl/precision-validation/policy-v10-offline-audit.json`

## 工程验证

| 检查 | 结果 |
|---|---:|
| Java 全量测试 | 137/137 |
| Python 全量测试 | 102/102 |
| v10 历史轨迹离线审计 | 通过 |
| v10 30×2 Dry Run / Plan fingerprint | 通过 |
| `git diff --check` | 通过 |
| 模型调用 / 费用 | 0 / ¥0 |

## 工程边界

v10 尚未执行任何真实模型回放，不能宣称已经通过发布门禁。唯一后续动作是执行已经冻结的
30 题 × 2 轮真实验收；完整身份、预算和退出规则见
[`RL_V10_PRECISION_VALIDATION_PLAN.md`](RL_V10_PRECISION_VALIDATION_PLAN.md)。
