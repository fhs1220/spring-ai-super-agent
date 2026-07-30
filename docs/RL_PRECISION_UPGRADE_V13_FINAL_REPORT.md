# RL 精准度 v13 最终工程收尾报告

## 最终状态

```text
RL ENGINEERING: COMPLETE
QUANTIFIED REAL EVIDENCE: COMPLETE
CURRENT IMPLEMENTATION: agentic-rag-v13
V13 REAL REPLAY: NOT_EXECUTED
CLOUD A/B/C/D TRAINING: COST_BLOCKED
FURTHER PRECISION VERSION CHAIN: STOPPED
MODEL CALLS / COST IN THIS CHANGE: 0 / ¥0
```

v13 是一次零调用的工程收尾，不是新的真实提分声明。它针对 v12 定向回放已经暴露的协议
边界完善结构化修正、轨迹审计与安全回退；项目最终的真实量化结论仍来自已经完成并冻结的
Stage 2、Stage 3、Judge v2、本地参数训练前后对比和 v8–v12 精准度验证。

## 为什么到此停止继续加版本

项目原定的 RL 工程能力、真实数据闭环和低预算训练前后验证已经完成。v8–v12 属于额外的
精准度研究，每次失败都可能继续产生更窄的边界问题；这类研究没有自然终点，不应再被当作
项目完成条件。

因此本次采用以下固定完成边界：

1. 保留所有通过和失败的真实量化结果，不通过的候选不包装成成功；
2. 修复最后一次试验暴露的确定性工程缺陷；
3. 不再自动增加 Benchmark、Judge、人工标注、训练或回放阶段；
4. 云端四臂训练只在未来预算条件改变时作为可选扩展，不属于当前待办；
5. 不创建 v14，也不以重复运行固定问题制造更好的数字。

## v13 工程改进

### 稳定要求 ID

`answer-verification-contract-v4` 为长度、引用、每个概念、禁用短语、追问、行动项和假设
生成确定 ID，例如 `concept-01`、`action-items`。修正模型只返回 ID，不再逐字复写
“覆盖概念：共同目标|阶段计划”一类自然语言键。

未知 ID 不会被猜测或自动映射；对应内容不会进入答案，缺失要求继续由硬门禁拒绝。

### 可审计的逐项结果

REVISE 轨迹新增：

- 输入 `repairRequirementIds`；
- 输出 `unresolvedRepairRequirementIds`；
- 解析失败时仍记录 `deterministic-contract-repair-renderer-v2`；
- Selector 算法保持不变，轨迹身份升级为 `deterministic-rlvr-selector-v6`；
- 默认候选实现标识为 `agentic-rag-v13`。

因此结构化解析失败不再造成渲染器版本轨迹缺口，仍可精确定位未解决的契约 ID。

### 确定性格式与边界

- 模型自带的 `1.`、`2、`、`3)` 会先移除，再由渲染器统一编号，避免 `1. 1.`；
- 结构化字段超长时按 Unicode code point 安全截断，再由长度、引用和内容硬门禁复核；
- 旧 `requirement`、新 `requirementId` 和 snake_case `requirement_id` 均可读取；
- 不自动生成事实、概念证据、引用，不删除禁用内容。

## 离线量化

| 指标 | 结果 |
|---|---:|
| v13 专项 Java 回归 | 37/37 |
| Java 全量测试 | 153/153 |
| Python 3.12 全量测试 | 111/111 |
| 稳定 ID / 未知 ID / 旧协议兼容 | 全部通过 |
| 重复编号归一化 | 通过 |
| 超长 Unicode 字段边界 | 通过 |
| 禁用短语和引用防伪造 | 通过 |
| 本阶段模型调用 / 费用 | 0 / ¥0 |

这些数字只证明工程回归，不证明 v13 的真实回答质量提升。

## 最终真实量化快照

| 证据 | 真实结果 |
|---|---|
| Stage 2 分层验证 | 100/100 完成，Single/Multi 各 50，RLVR 0.759502，质量和路由通过；1 次外部欠费恢复事件有正式 waiver |
| Stage 3 数据闭环 | 300/300 轨迹，119 个高置信唯一问题，1,864 次调用，3,233,393 Token，约 ¥1.0572933 |
| Judge v2 | 119 条得到 73 条正向、46 条 HOLDOUT、0 条负标签；RLAIF 66/7 切分通过 Batch 64 Readiness |
| 本地真实参数训练 | 两次 LoRA/DPO 均完成真实参数更新；固定评测未提升，两个适配器均正确判为 `NOT_PROMOTED` |
| v12 定向精准度 | 12/12 完成，RLVR 0.758128，73 次调用，约 ¥0.0461085；最终契约 7/12，因此 `TARGETED_FIX_NOT_VALIDATED` |

项目有真实轨迹、自动评分、人工锚点、训练数据包、真实参数更新、训练前后固定评测和失败拒绝
证据。没有执行的部分是超过当前预算的百炼 A/B/C/D 云训练与四个云端资产灰度发布。

## 最终结论

当前项目可表述为：

> 已完成可审计的 Agent RL/RLVR/RLAIF 工程闭环和真实量化验证；低预算本地训练完成但未带来
> 质量提升，因此没有发布适配器。百炼四臂云训练已具备提交条件，但因最低资源成本超预算而
> 保持 `COST_BLOCKED`。v13 仅完善失败后的协议与轨迹，不宣称未经真实回放的提分。

这就是当前 RL 部分的终态。除非未来主动提高云预算或提出新的产品目标，否则没有后续
自动阶段。
