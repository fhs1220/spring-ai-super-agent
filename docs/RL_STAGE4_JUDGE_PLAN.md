# RL Stage 4 Judge 与人工锚点计划

## 结论

Stage 4 的零调用准备已冻结。输入严格限定为 Stage 3 通过
`paired-high-confidence-v1` 资格契约的 119 个唯一问题，不使用通用“全历史待评”队列。
每个问题从两轮中选择 RLVR 更高的一条代表轨迹，交给四个 Judge 维度评审，因此真实执行
上限为 119 条轨迹、476 次 Judge 调用。

这一阶段尚未执行 Judge 模型调用。真实执行仍需单独明确授权。

## 冻结契约

| 项目 | 值 |
|---|---|
| Stage 3 Batch | `policy-v7-v2-stage3-150x2` |
| Stage 4 Batch | `policy-v7-stage4-judge-119` |
| Policy | `agentic-rag-v7` |
| 合格唯一问题 | 119 |
| 代表轨迹规则 | 每题选择 RLVR 最高的一轮；同分时按轮次、轨迹 ID |
| Judge 维度 | 指令遵循、可执行性、逻辑一致性、严格反审 |
| 最大 Judge 调用 | 476 |
| 人工优先抽检 | 30 |
| 冻结计划指纹 | `d40dfa5753a44c5f988e83003b72f780973e8a71d2141e876da725bce650c998` |
| 模型调用授权环境变量 | `AGENT_RL_JUDGE_ALLOW_MODEL_CALLS=true` |

脚本会再次验证 Stage 3 collection gate、合格 Seed ID、每轮 RLVR 门禁、Policy、问题文本和
本地轨迹证据。任何身份不一致都会在产生调用前失败。

## 人工抽检

人工样本采用 `risk-stratified-human-anchor-v1`：

1. 恢复过的轨迹优先进入；
2. 两轮中较低 RLVR 越接近 0.70 边界，优先级越高；
3. 两轮 RLVR 差距越大，优先级越高；
4. 按 actions、checklist、decision、dialogue、weekly_plan 任务类型轮转抽取，避免单类偏置。

Judge 执行完成后，人工应优先复核这 30 条，并写入 4～5 星正向锚点、1～2 星负向锚点或
3 星保留样本。恢复轨迹只因审计风险进入人工队列，不预设其质量结论。

## 费用和恢复边界

Dry Run Manifest 会按实际选中答案长度给出输入、输出 Token 与费用规划上界。该估算使用
当前项目默认的 ¥0.3/百万输入 Token、¥0.6/百万输出 Token，只用于预算，不代表云账单。
本次 Dry Run 上界为 761,828 输入 Token、95,200 输出 Token，估算约 ¥0.2856684。

执行器逐条原子写入结果；重启后只接受冻结计划的连续前缀。每条轨迹先查询已有评审，
已有结果不会重复调用；新轨迹最多请求一次四 Judge 面板。不完整面板会进入
`REVIEW_REQUIRED`，不会自动重试并扩大预算。

## 命令

零调用 Dry Run：

```bash
python3 bailian-agent-rl/run_stage4_judges.py \
  --replay tmp/agent-rl/replays/policy-v7-v2-stage3-150x2.json \
  --seeds tmp/agent-rl/seeds/training-seeds-v2.jsonl \
  --trajectories tmp/agent-rl/trajectories \
  --batch-id policy-v7-stage4-judge-119 \
  --human-sample-size 30 \
  --output tmp/agent-rl/alignment-plans/policy-v7-stage4-judge-119.json
```

真实执行必须在后端以 `AGENT_RL_API_ENABLED=true` 启动后，增加 `--execute`，并显式设置：

```bash
AGENT_RL_JUDGE_ALLOW_MODEL_CALLS=true
```

在用户授权 Judge 预算前，不运行真实命令。
