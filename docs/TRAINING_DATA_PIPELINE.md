# 训练数据生成与安全回放

这条流水线把“题目种子”“真实 Agent 轨迹”和“可提交训练样本”分成三个不同阶段，避免把
模板参考文本误当成模型真实输出，也避免固定 Benchmark 泄漏进训练集。

## 数据分层

1. **题目种子（非训练样本）**
   - 来源：项目内 3 份恋爱知识库、15 个核心问答；
   - 扩展维度：10 种场景约束、5 种任务形态、3 种开场方式；
   - 默认生成 300 个唯一问题，15 个主题各 20 个；
   - 每条记录保留知识章节与内容 SHA-256、验证契约和数据角色
     `trajectory_seed_only`；
   - 36 条 `love-rag-ab.jsonl` 固定 Benchmark 同时进行规范化精确匹配、包含匹配和
     3-gram Jaccard 近似匹配。

2. **真实 Agent 轨迹**
   - 用本地 Spring Boot 的 durable SSE 接口逐条回放题目；
- 回答、阶段 Trace、检索来源、奖励、遥测和 `policyVersion` 均由真实运行产生；
- 回放清单同时记录在线轨迹奖励、契约级 RLVR 分数、模型调用数、Token、估算费用、
  超时和单/多 Agent 执行模式；
   - 同一题默认回放 2 轮，为轨迹引导筛选提供同题多轮证据；
   - 首条轨迹会核对服务端真实 `policyVersion`，不一致立即停止。

3. **可训练样本**
   - 仍由 Java `BailianRlDatasetService` 从已完成轨迹中筛选、去重和导出；
   - `submit_job.py` 会明确拒绝 `trajectory_seed_only` 文件；
   - 固定 Benchmark 只用于最终评价，不能作为 train 或 validation 输入。

## 1. 生成 300 条离线种子

```bash
cd /Users/fhs1220/super-agent/spring-ai-super-agent
python3 bailian-agent-rl/generate_training_seeds.py \
  --output tmp/agent-rl/seeds/training-seeds-v1.jsonl \
  --manifest tmp/agent-rl/seeds/training-seeds-v1.manifest.json \
  --target 300
```

该命令不调用模型、不访问云端、不产生费用。Manifest 会记录：

- 数据、知识库和 Benchmark 指纹；
- 唯一问题数和每个知识主题的样本数；
- 污染检测阈值和拒绝数量；
- `model_calls=0`、`billable_operations=0`、`submission_allowed=false`。

## 2. 先预览回放计划

```bash
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/seeds/training-seeds-v1.jsonl \
  --batch-id policy-v5-seed-v1 \
  --policy-version agentic-rag-v5 \
  --rounds 2 \
  --limit 10
```

Dry Run 是默认模式。上面的命令只显示计划，既不会访问本地接口，也不会调用模型。
去掉 `--limit 10` 时，300 个问题、2 轮合计计划 600 次真实 Agent 请求。

## 3. 显式授权小批量真实回放

先用 5 条题目验证服务、策略版本、成本和轨迹完整性：

```bash
AGENT_RL_API_ENABLED=true \
AGENT_RL_POLICY_VERSION=agentic-rag-v5 \
sh mvnw spring-boot:run
```

这是受信任的本地轨迹读取接口，回放器会先做无模型调用的 API 预检。确认后在另一个终端运行：

```bash
AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true \
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/seeds/training-seeds-v1.jsonl \
  --batch-id policy-v5-pilot-01 \
  --policy-version agentic-rag-v5 \
  --rounds 2 \
  --limit 5 \
  --output tmp/agent-rl/replays/policy-v5-pilot-01.json \
  --execute
```

真实回放必须同时提供：

- `--execute`；
- `AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true`；
- 本地后端已用 `AGENT_RL_API_ENABLED=true` 启动；
- `--policy-version`；
- `--output` 持久化证据。

每个请求使用确定性 `runId`。进程中断后使用同一 `batch-id` 重跑，后端会重放已完成的
durable run，不会为同一个 `runId` 再生成一条新轨迹。

## 建议的样本规模

- 阶段 A：5 个种子 × 2 轮，验证接口、费用和策略版本；
- 阶段 B：50 个种子 × 2 轮，检查奖励分布、失败率和主题覆盖；
- 阶段 C：300 个种子 × 2 轮，形成约 600 条候选轨迹；
- 人工只抽查 20–30 条高价值或高分歧样本，其他样本由 RLVR 契约和 AI Judge Panel
  评分；
- 最终训练集由轨迹筛选器产生，不能直接提交种子 JSONL。

如果需要跨策略版本比较，应为每个真实部署使用不同的 `batch-id`，并把服务端实际
`policyVersion` 作为 `--policy-version`。不要仅修改文件名来伪造另一个策略版本。
