# System One 反事实质量标注

## 目的

Jev 与 Laya 的分歧本身不是正确答案。本流水线对同一个真实问题分别强制执行 single-Agent
和 multi-Agent，再用盲化质量评审与实测成本、延迟生成效用标签。该标签用于识别“真正值得
multi-Agent”的场景，而不是把任一路由器的建议当作真值。

```text
Jev/Laya 分歧池
  └─ 分层抽样（分歧 + 约 10% agreement control）
      ├─ 强制 single-Agent ─┐
      └─ 强制 multi-Agent  ─┴─> 随机化 A/B 盲评 ─> 效用标签
                                                   ├─ development：调参
                                                   └─ holdout：最终验收
```

强制执行使用隔离评测入口，不写 Chat Memory 或训练轨迹。每个样本只保存一个标签文件；重启后
自动跳过已完成 sample ID，因此任务可恢复且不会因重试重复花费。

## 质量与效用

AI Judge 不知道 A/B 对应哪种架构，答案顺序由问题指纹确定性随机化。两路使用相同量表：

- 正确与安全：35%；
- 需求覆盖：25%；
- 可执行性：20%；
- 逻辑与依据：10%；
- 直接简洁：10%。

系统按下式比较两种执行策略：

```text
utility = quality
          - costWeight × min(1, measuredCost / costBudget)
          - latencyWeight × min(1, measuredLatency / latencyBudget)
```

当 `multiUtility - singleUtility > 0.01` 时标签为适合 multi-Agent。以下样本不会自动成为训练
真值，而是进入人工复核：安全动作分歧、Judge 置信度低于 `0.70`、或两路效用差绝对值小于
`0.03`。

Judge 当前复用主 ChatModel，但其 Token/费用尚未接入可靠的 usage 计量；所有标签与运行报告均
明确记录 `judgeCostMeasured=false`。报告里的总费用只包含强制 single/multi 两次生成费用，
不能声称是端到端完整费用。

## 数据隔离

问题 SHA-256 指纹确定性分为 70% development 和 30% holdout。development 可用于修改阈值、
特征或 Jev/Laya 组合策略；holdout 不能用于调参。查询接口默认封存 holdout 标签：

```bash
export AGENT_EVALUATION_SYSTEM_ONE_LABELING_EXPOSE_HOLDOUT_LABELS=false
```

只有候选策略和验收指标已预注册后，最终一次验收才临时设为 `true`。最终简历数字必须来自该
独立 holdout，不能使用 development 或当前 36 题冻结集上的调参结果。

## 运行 20 条 pilot

先开启双路 Shadow 的问题原文保留并积累去重样本。评测 API 只应在受信任的本地环境使用：

```bash
export AGENT_EVALUATION_API_ENABLED=true
export AGENT_SYSTEM_ONE_COMPARISON_STORE_QUESTION=true
```

启动任务：

```http
POST /api/agent-evaluation/system-one-labeling/runs
Content-Type: application/json

{"maximumCases":20,"maximumCostCny":10}
```

查看、取消和读取开发集标签：

```http
GET /api/agent-evaluation/system-one-labeling/runs/{runId}
DELETE /api/agent-evaluation/system-one-labeling/runs/{runId}
GET /api/agent-evaluation/system-one-labeling/labels?split=DEVELOPMENT
```

同一时间最多一个标注任务。`maximumCases` 与 `maximumCostCny` 不能超过服务端硬上限；0 元预算
不会调用模型。成本在一对 single/multi 完成后才能结算，因此最多可能超过上限最后一对的费用。
标签原子写入 `tmp/system-one-labels/{development,holdout}`。

## 结论门禁

20 条 pilot 只用于验证分布、费用、Judge 稳定性和人工复核比例，不能形成简历提升结论。扩大
样本后，至少应报告：

- 独立 holdout 样本数与正负类分布；
- 路由 accuracy、balanced accuracy、multi-Agent precision/recall；
- 相对当前路由的平均质量差及配对 95% 置信区间；
- 延迟、生成费用与效用遗憾；
- 安全样本漏报数；
- Judge 与人工复核的一致率。

只有预注册门禁全部通过，才把质量提升数字写入简历；否则保留为工程系统与实验设计成果。
