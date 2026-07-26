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

## 两层门禁

无模型费用的 CI 门禁运行 89 项可复现测试、基准目录校验和前端构建。真实模型、MCP、
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
