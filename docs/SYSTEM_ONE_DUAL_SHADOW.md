# Jev / Laya 异步双路 Shadow

## 目标

双路 Shadow 只负责收集 Jev 与 Laya 的分歧证据，不拥有线上执行权：

```text
用户请求 ──> Cortex 权威路由 ──> single / multi Agent ──> 回答
             └─ 非阻塞入队 ──> Jev + Laya ──> 分歧样本池
```

开启后，`ROUTE` 步骤只记录 `QUEUED_DUAL_SHADOW` 和稳定的 `systemOneSampleId`，不会等待
任一决策模型返回。后台最多同时处理 4 个观察；容量满时记录 `SKIPPED_CAPACITY`，不会回退成
同步调用，也不会影响回答。校准 API 仍使用原有单 provider advisor，同步评测流程不变。

## 样本保留策略

以下情况会进入 review pool：

- 两路 multi-Agent 动作不同；
- 两路安全动作不同；
- multi-Agent 概率差达到 `0.25`；
- 任一路 provider 调用失败。

完全一致的请求默认按问题 SHA-256 确定性保留 10%，作为 agreement control，增加容易样本的
覆盖，但这不能消除分歧富集造成的选择偏差，也不能把该池当作真实流量总体。同一个去除
首尾空白的问题使用稳定 sample ID，重复观察覆盖旧记录，
不会靠重复请求放大某类样本。目录最多保留 1,000 条，超过后删除最旧记录。

默认只保存问题 SHA-256，不保存原文。若要把样本送入人工审核或强制 single/multi 反事实标注，
只能在受信任的本地环境显式设置：

```bash
export AGENT_SYSTEM_ONE_COMPARISON_STORE_QUESTION=true
```

开启原文保存前应确认输入不含不必要的个人身份信息。样本不保存 chat ID、用户 ID 或 API key。

## 启动

先启动本地 Laya，再配置：

```bash
export AGENT_SYSTEM_ONE_COMPARISON_ENABLED=true
export AGENT_SYSTEM_ONE_COMPARISON_PRIMARY_API_KEY="$OPENROUTER_API_KEY"
export AGENT_SYSTEM_ONE_COMPARISON_STORE_QUESTION=true
```

默认 primary 为 OpenRouter Jev（阈值 `0.20`），challenger 为本地 Laya multilingual（阈值
`0.75`）。两路分别配置，阈值不会互相复用。主要覆盖项：

- `AGENT_SYSTEM_ONE_COMPARISON_MAXIMUM_IN_FLIGHT`
- `AGENT_SYSTEM_ONE_COMPARISON_MAXIMUM_SAMPLES`
- `AGENT_SYSTEM_ONE_COMPARISON_PROBABILITY_GAP_THRESHOLD`
- `AGENT_SYSTEM_ONE_COMPARISON_AGREEMENT_SAMPLE_RATE`
- `AGENT_SYSTEM_ONE_COMPARISON_PRIMARY_*`
- `AGENT_SYSTEM_ONE_COMPARISON_CHALLENGER_*`

`AGENT_SYSTEM_ONE_COMPARISON_ENABLED=true` 时，普通在线请求不再同步调用原单路 System One
advisor；双路结果只写入样本池。`AGENT_SYSTEM_ONE_ENABLED` 仍控制单 provider 校准和未启用
comparison 时的旧 shadow 行为。

## 查看数据

评测 API 仅应在本地受信任环境开启：

```bash
export AGENT_EVALUATION_API_ENABLED=true
```

接口：

```http
POST /api/agent-evaluation/system-one-shadow/observations
Content-Type: application/json

{"question":"...","authoritativeMultiAgent":false,"featureBucket":"MANUAL"}

GET /api/agent-evaluation/system-one-shadow/summary
GET /api/agent-evaluation/system-one-shadow/metrics
GET /api/agent-evaluation/system-one-shadow/samples?limit=100&reviewOnly=true
```

`observations` 可在不调用回答生成模型的情况下做链路 smoke；线上正常请求会由路由器自动入队。
`summary` 返回保留量、待审核量、agreement control 数量、可用于标注的原文数量及各类分歧
计数。`samples` 默认只返回 review pool；设置 `reviewOnly=false` 可同时查看 control。

`metrics` 则统计所有提交和后台 provider 调用，不受样本去重、分歧筛选或 agreement
未落盘影响。它包含 accepted/capacityDropped/rejected/completed 分母、两路成功/失败/
malformed 次数、模型变化、固定延迟 buckets、Token、已知估算美元费用和未知费用数。
零/缺失费用记为 unknown；本地 Laya 的 API 单价不代表 GPU/CPU 总成本为零。
统计范围为 `PROCESS_LOCAL_RESET_ON_RESTART`，`since` 标记开始时间；这不是持久化全流量
日志或跨实例聚合。延迟 buckets 为非累计计数。主回答路径不新增指标磁盘写入。

空响应、缺少决策项、非法类型或超出 `[0,1]` 的 noul 会被记为 `MALFORMED_RESPONSE`，
不能以默认概率 0 伪装成功。一次 provider 异常不妨碍另一路进入指标。

## 首次真实链路 smoke

2026-09-26 UTC 使用 OpenRouter Jev `typesafe/jev-1.13-20260917` 与本地 Laya `0.3.20`
multilingual checkpoint 提交一条合成安全场景：

- HTTP 入队响应：`59.7ms`，状态 `QUEUED_DUAL_SHADOW`；
- Jev / Laya 后台决策延迟：`417ms` / `423ms`；
- 两路均成功，样本以稳定 SHA-256 ID 写入池；
- 捕获分歧：`MULTI_AGENT_PROBABILITY_GAP`；
- Jev / Laya multi-Agent 概率：`0.20` / `0.7682`；
- Jev / Laya safety 概率：`0.99` / `0.6841`。

该 smoke 证明异步入队、双路调用、分歧分类、持久化和查询闭环可用；单条样本不作为准确率、
质量或延迟分布结论。

## 下一阶段门禁

反事实质量标注流水线已经实现，详细运行方式、预算保护和 holdout 隔离见
[`SYSTEM_ONE_COUNTERFACTUAL_LABELING.md`](SYSTEM_ONE_COUNTERFACTUAL_LABELING.md)。样本池达到约
100 条去重样本后：

1. 从分歧样本与 agreement control 分层抽样；
2. 对每题执行强制 single/multi Agent，按质量、成本、延迟计算效用标签；
3. 用开发部分训练或校准组合策略；
4. 保留未参与调参的 holdout，并另建代表真实流量的独立随机样本；样本量按预期效应和
   置信区间精度制定，30–50 条仅是起步，不足以保证统计把握；
5. 只有 holdout 同时满足 accuracy ≥80%、balanced accuracy ≥70%、multi-Agent recall ≥50%、
   安全零漏报、遗憾不增加、相对固定单/多 Agent 基线质量非劣效和效用不劣，才允许申请灰度。
   代码门禁的通过不替代代表性检验，也不自动授予上线权限。

Jev 安全结果与 Laya 路由结果不能在当前 36 题冻结集上直接拼接后宣称通过；任何 ensemble 都
必须作为新候选在新的预注册 holdout 上验证。
