# RL v9 契约强制精准度升级报告

## 结论

v8 真实回放暴露的“RLVR 契约只用于事后评分、没有用于回答执行”缺口已经修复，默认策略
升级为 `agentic-rag-v9`：

```text
STATE: IMPLEMENTED_AND_OFFLINE_VALIDATED
POLICY: agentic-rag-v9
CONTRACT: answer-verification-contract-v1
SELECTOR: deterministic-rlvr-selector-v2
MODEL CALLS: 0
BILLABLE OPERATIONS: 0
REAL QUALITY DECISION: AWAITING_EXPLICIT_REPLAY_AUTHORIZATION
```

本阶段没有启动后端、没有请求模型，也没有重复执行已终结的 v8 批次。代码已经能够强制
修复 v8 首条失败的机制，但在新的真实回放完成前，不宣称 v9 已经提高平均 RLVR。

## 根因修复

v8 的 Python 种子已经包含完整 `verification_contract`，但真实回放请求只把问题发送给
Java。Generate、Reviewer、Revise 和在线 Reward 都不知道隐藏的逐项契约，只有请求完成后
Python RLVR 才发现缺失。于是出现：

```text
Reviewer: taskCompleted=true
Online Reward: 0.95
RLVR v3: 0.659771
Violation: instruction_contract_incomplete
```

v9 把同一份结构化契约贯穿整个回答生命周期：

1. 回放客户端把冻结的 `verification_contract` 随请求发送；
2. Durable Run 持久化契约，失败恢复或进程重启后不会丢失，也不能用相同 `runId` 替换契约；
3. Generate 在第一次生成前获得逐项检查清单；
4. Reviewer 必须逐项检查，而不再只判断宽泛的 `taskCompleted`；
5. Java 确定性检查独立验证长度、引用、必要概念、禁用短语、追问、假设和行动项数量；
6. 即使 Reviewer 错误返回“已完成”，只要确定性检查发现缺项，仍强制进入 Revise；
7. Revise 同时获得完整契约和本次明确缺失项；
8. `deterministic-rlvr-selector-v2` 用同一契约比较初稿与修订稿，防止修订退化；
9. Review、Revise、`RLVR_SELECT` 轨迹记录契约版本、通过状态和缺失项；
10. 在线静态 Reward 也服从最终契约结果，不再给确定性缺项的回答满任务完成分。

普通在线请求没有显式契约时，会从用户问题确定性提取十五分钟、话术、七天首尾、复盘、
优先级、禁止追问、合理假设和行动项数量等直接约束；训练与验证请求则使用数据集冻结的
完整契约，并与问题中的直接约束合并。

## v8 首条失败模式回归

新增端到端测试复现以下情况：

1. 初稿有 15 分钟流程、引用和三个行动项；
2. 初稿漏掉兴趣爱好、社交圈、独立和平衡；
3. 模型 Reviewer 仍返回 `grounded=true, taskCompleted=true, revisedAnswer=null`；
4. v9 确定性检查拒绝放行并触发 Revise；
5. 修订稿补齐所有概念；
6. v2 选择器选择修订稿，最终轨迹标记逐项契约通过。

该测试使用 Mockito 固定响应，只验证控制流和契约执行，不调用真实模型。

## 60 条历史轨迹离线审计

`audit_v9_contract_enforcement.py` 对 v8 冻结验证集对应的 60 条 v7 高风险历史轨迹进行了
零调用反事实审计：

| 指标 | 结果 |
|---|---:|
| 历史轨迹 | 60 |
| 历史初稿会被 v9 强制修订 | 60 |
| Reviewer 假阴性 | 54 |
| 历史最终答案仍未满足完整契约 | 47 |
| 历史 RLVR 违规轨迹 | 5 |
| v9 契约检测到的历史违规 | 5/5 |
| 模型调用 / 付费操作 | 0 / 0 |

这里的 `60/60` 不是预测 v9 每条都会多调用一次模型。历史初稿生成时根本没有收到契约；
v9 已经把契约前置到 Generate，目标是尽量在初稿完成。该数字只证明旧策略与冻结契约存在
系统性脱节，并估计最坏情况下的 Revise 成本风险。

`47` 条完整契约失败也比历史 5 条 RLVR 违规更严格：RLVR v3 的任务完成质量是多项平均后
以 `<0.6` 触发违规，而 v9 的发布目标是逐项为零遗漏。因此该审计用于证明拦截覆盖，不是
训练前后质量分。

本地审计证据位于：

`tmp/agent-rl/precision-validation/policy-v9-contract-audit.json`

## 工程验证

- Java 全量测试：134/134；
- Python 全量测试：101/101；
- Java v9 专项测试：26/26；
- Python v8/v9 契约与回放专项测试：22/22；
- `git diff --check`：通过；
- 真实模型调用：0；
- 估算费用：¥0。

## 当前边界与下一步

v9 已完成工程实现和离线反事实验证，但尚未完成真实质量验证。下一步只能是为
`agentic-rag-v9` 冻结一个新的、有独立身份和费用上限的真实验证；不得恢复、篡改或重跑
已经终结的 `policy-v8-precision-30x2`。

新的验证现已离线冻结为唯一一次 `policy-v9-precision-30x2`，完整身份、门禁、预算与命令见
[`RL_V9_PRECISION_VALIDATION_PLAN.md`](RL_V9_PRECISION_VALIDATION_PLAN.md)。它必须同时报告：

1. 初稿直接通过率与强制 Revise 率；
2. 修订后逐项契约通过率；
3. v2 选择器保留初稿/采用修订稿的比例；
4. RLVR v3、违规、路由、超时、Token 和费用；
5. 相对冻结历史基线的配对差值和置信区间。

真实回放已完成 60/60，最终结论为 `NOT_PROMOTED`。v9 的 RLVR v3 从历史 v7 的
0.697469 提升至 0.753685，配对差值为 +0.056216，95% CI 为
[+0.019671, +0.102561]；但最终逐项契约仅 26/60 通过，同时有 5 次 RLVR 退化选择和
1 次已恢复超时。完整证据与根因见
[`RL_V9_PRECISION_VALIDATION_REPORT.md`](RL_V9_PRECISION_VALIDATION_REPORT.md)。
