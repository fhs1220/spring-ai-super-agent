# 四路离线回放与回归门禁

固定基准位于 `src/main/resources/evaluation/love-rag-ab.jsonl`。每个样本声明任务标签、
必须覆盖的概念、禁止表达、长度范围、引用要求和预期路由。加载时会检查最小数量、唯一 ID、
合法路由标签和非空评测要点，并对规范化样本计算 SHA-256 指纹。

每条样本依次执行四种变体，且按样本序号轮换执行顺序以降低位置偏差：

1. `TRADITIONAL_RAG`
2. `AGENTIC_SINGLE_AGENT`
3. `AGENTIC_MULTI_AGENT`
4. `AGENTIC_RAG_V5`（自适应路由）

强制路由只存在于隔离评测入口。评测不会写入 Chat Memory，也不会写入 Agent RL 轨迹仓库，
因此不会让测试数据污染训练与策略注册中心。

## 报告

每次运行同时生成：

- `tmp/evaluation/<runId>.json`：机器可读完整结果；
- `tmp/evaluation/<runId>.md`：适合面试展示的汇总、门禁和最大回归样本。

报告包含四路平均分、通过率、延迟、Token、成本、失败数、标签切片、候选胜平负、Router
准确率和自适应/同路由强制基线成本比。Token 使用不可测时会标记不可比较，不会以零冒充。
报告同时记录模型版本、模型资产 SHA-256、训练配置 SHA-256、Reward Schema 和部署来源。
缺少有效指纹的本地 smoke 报告仍可用于调试，但不能作为正式四臂消融证据。

RLAIF/RLVR 四臂消融报告不是只比较四个均值。它会按 `caseId` 对齐逐样本结果，使用固定
种子的配对 percentile bootstrap 计算 95% 置信区间，同时给出标准化效应量、精确符号
检验、改善概率和胜平负。完整方案必须至少包含 30 个配对样本，且置信区间下界不得穿过
允许回退的非劣界；因此两条烟雾运行永远不会误通过统计发布门禁。

正式四臂实验先通过 `/api/agent-evaluation/alignment-experiments` 声明四个不同模型资产，
再逐臂挂接已完成的 `runId`。服务端会同时核验运行身份、benchmark 和样本数，并禁止
`runId` 或模型资产跨臂复用；全部证据齐备后才允许 `finalize`。实验清单使用文件原子写入，
损坏文件会隔离备份，实验及报告 ID 均为确定性指纹，支持安全重试。

`bailian-agent-rl/experiment_manifest.py` 负责从四个真实百炼产物生成上述请求，并把训练
配置、训练/验证数据及云端 Reward/Rollout 代码纳入来源指纹。三种数据 Profile
`RLVR_ONLY`、`RLVR_RLAIF`、`FULL_TRAJECTORY_GUIDED` 在 Java 导出阶段分别执行“仅硬规则”、
“硬规则 + AI Judge”和“硬规则 + AI Judge + 多轮奖励趋势”筛选。

## 两层门禁

无模型费用的 CI 门禁运行 100+ 项可复现测试、基准目录校验和前端构建。真实模型、MCP、
外部网络与 PGVector 测试标记为 JUnit `integration`，不会伪装成稳定单元测试：

```bash
bash verify-interview-v1.sh
```

外部依赖就绪后可显式执行集成层：

```bash
./mvnw -DexcludedGroups= -Dgroups=integration test
```

真实模型回归门禁通过受保护的异步评测 API 运行：

```http
POST /api/agent-evaluation/ab-runs
Content-Type: application/json

{"maximumCases":2}
```

建议先运行 2 条烟雾评测，再根据费用预算运行 36 条全量评测。只有真实报告通过后，才应在
简历中填写具体质量提升、路由准确率、延迟或成本数字。
