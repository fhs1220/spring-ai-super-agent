# RL 完成执行计划

## 完成定义

“RL 完成”不是只有 Reward、轨迹和提交脚本，而是以下证据全部闭环：

1. 分层真实轨迹通过 RLVR 与路由门禁；
2. RLAIF 评审和人工锚点形成可审计标签；
3. 训练/验证数据包通过云端提交 Readiness；
4. 四个不同训练方案产生四个不可变模型资产；
5. 同一固定 Benchmark 的正式配对评测通过统计和成本门禁；
6. 胜出资产经过 SHADOW、Canary、监控和可回滚发布。

截至 2026-07-29，工程链路和 300 条 v2 离线种子已完成。v2 已真实执行 8 题 × 2 轮：
16/16 完成、Single/Multi 各 8、路由偏差和超时均为 0、RLVR 平均 0.714790，但因 5 条
引用格式违规未通过 Release Gate，尚不能扩到 50 题。失败证据见
[`RLVR_V3_STRATIFIED_PILOT_REPORT.md`](RLVR_V3_STRATIFIED_PILOT_REPORT.md)。
自动 Alignment Assessment 仍为 0，尚无百炼训练数据包、训练任务或训练后模型资产。

## 分阶段执行

| 阶段 | 工作 | 退出门禁 | 模型/费用 |
|---|---|---|---|
| 0 | 取消状态机、共享路由契约、回放自动门禁 | 全量单测通过 | 无 |
| 1 | v2 8 题 × 2 轮分层试回放 | 16/16 完成，单双各 8，路由偏差 0，超时 0，RLVR 硬门禁全过，平均分 ≥ 0.70，违规 0 | 本地真实模型 |
| 2 | v2 50 题 × 2 轮验证 | 100 条轨迹完成，覆盖关系/育儿/家务/财务，成本和失败率可接受 | 本地真实模型 |
| 3 | 扩充训练轨迹 | 至少 82 个合格唯一问题；建议先采 150 个，筛选不足再扩到 300 个，每题至少 2 轮 | 本地真实模型 |
| 4 | RLAIF 与人工锚点 | 四 Judge 一致性/置信度达标；主要任务组有人工 4–5 星锚点；人工抽查 20–30 条 | Judge 模型 |
| 5 | 导出 A/B/C 数据包 | `RLVR_ONLY`、`RLVR_RLAIF` 均 Readiness 通过；训练集 > 64、验证集非空、无 Benchmark 泄漏 | 无 |
| 6 | 训练 A/B/C | 静态 Reward、RLVR、RLVR+RLAIF 三个任务完成并部署为不同资产 | 百炼付费 |
| 7 | 跨策略匹配回放并训练 D | 同题至少两轮、包含不同策略版本的奖励轨迹；`FULL_TRAJECTORY_GUIDED` Readiness 通过并完成训练 | 本地模型 + 百炼付费 |
| 8 | 正式四臂评测 | 四臂同一 36 题 Benchmark，至少 30 个配对样本，质量非劣、成本和身份门禁通过 | 四个部署真实评测 |
| 9 | 灰度上线 | SHADOW → Canary → ACTIVE；OPE、漂移、质量守卫健康且可回滚 | 线上流量 |

数据导出会按规范化问题去重，并按默认 20% 划分验证集。因此 50 个唯一问题最多约
40 条训练样本，达不到 `batch_size=64`；82 个是全数合格时的理论下限，实际必须为
RLVR、Judge 和轨迹筛选淘汰预留余量。

完整轨迹引导臂不能仅靠同一 `agentic-rag-v5` 的随机重复回答来宣称“策略演化”。合理顺序
是先训练并部署 A/B/C，再用其中的新策略对匹配问题重新回放，最后生成 D 的跨策略奖励轨迹
数据。

## 下一次真实试回放

后端：

```bash
AGENT_RL_API_ENABLED=true \
AGENT_RL_POLICY_VERSION=agentic-rag-v5 \
sh mvnw spring-boot:run
```

另一个终端：

```bash
AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true \
python3 bailian-agent-rl/replay_training_seeds.py \
  --seeds tmp/agent-rl/seeds/training-seeds-v2.jsonl \
  --batch-id policy-v5-stratified-pilot-v2 \
  --policy-version agentic-rag-v5 \
  --rounds 2 \
  --limit 8 \
  --output tmp/agent-rl/replays/policy-v5-stratified-pilot-v2.json \
  --execute
```

回放器默认执行以下自动门禁：

- RLVR 平均分至少 0.70；
- 路由偏差、超时和 RLVR 违规均为 0；
- 所有计划运行完成且 RLVR 硬门禁全部通过；
- v2 路由预期必须逐条得到实际轨迹验证。

即使所有调用完成，只要门禁失败，进程也会以非零状态结束并把失败项写入结果 Manifest。

## 授权边界

以下三类操作分别需要显式授权，不能由“继续开发”自动推定：

1. 设置 `AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true`：真实 Agent 回放；
2. 开启 Alignment Auto Evaluate 或手动批评审：四 Judge 调用；
3. 设置 `BAILIAN_RL_ALLOW_BILLING=true --execute`：百炼付费训练。

每个付费阶段都先用上一阶段的真实 Token、调用数和费用估算更新预算，再决定是否扩量。
