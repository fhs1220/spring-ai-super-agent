# RL 完成执行计划

## 完成定义

“RL 完成”不是只有 Reward、轨迹和提交脚本，而是以下证据全部闭环：

1. 分层真实轨迹通过 RLVR 与路由门禁；
2. RLAIF 评审和人工锚点形成可审计标签；
3. 训练/验证数据包通过云端提交 Readiness；
4. 四个不同训练方案产生四个不可变模型资产；
5. 同一固定 Benchmark 的正式配对评测通过统计和成本门禁；
6. 胜出资产经过 SHADOW、Canary、监控和可回滚发布。

截至 2026-07-29，工程链路、300 条 v2 离线种子和 Stage 1 已完成。Stage 2 的 v7 Batch
已得到 100/100 条结果：Single/Multi 各 50、路由偏差 0、RLVR 平均 0.759502、硬门禁
100/100、违规 0。第 94 个逻辑运行时百炼返回 `Arrearage`，充值后从 93 条已完成前缀
恢复并完成剩余 7 条；原事件按审计规则计为 1 次恢复超时，所以严格 Release Gate 仅因
`timeouts_within_limit` 未通过。项目负责人已接受仅覆盖该外部计费中断的正式 waiver，
Stage 2 状态为 `COMPLETED_WITH_EXTERNAL_INCIDENT_WAIVER`。原 Manifest 的失败状态保持
不变。证据见
[`RLVR_V3_STAGE2_50X2_REPORT.md`](RLVR_V3_STAGE2_50X2_REPORT.md)；Stage 1 证据见
[`RLVR_V3_STRATIFIED_PILOT_REPORT.md`](RLVR_V3_STRATIFIED_PILOT_REPORT.md)。
Stage 3 随后完成 150 个问题 × 2 轮的真实采集，得到 300/300 条轨迹和 119 个高置信
问题，Collection Gate 通过。自动 Alignment Assessment 仍为 0，尚无百炼训练数据包、
训练任务或训练后模型资产。

## 分阶段执行

| 阶段 | 工作 | 退出门禁 | 模型/费用 |
|---|---|---|---|
| 0 | 取消状态机、共享路由契约、回放自动门禁 | 全量单测通过 | 无 |
| 1 | v2 8 题 × 2 轮分层试回放 | 16/16 完成，单双各 8，路由偏差 0，超时 0，RLVR 硬门禁全过，平均分 ≥ 0.70，违规 0 | 本地真实模型 |
| 2 | v2 50 题 × 2 轮验证 | 100 条轨迹完成，覆盖关系/育儿/家务/财务，路由偏差、超时、RLVR 违规为 0，硬门禁全过，平均分 ≥ 0.70 | 本地真实模型 |
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

完整轨迹引导臂不能仅靠同一策略版本的随机重复回答来宣称“策略演化”。合理顺序
是先训练并部署 A/B/C，再用其中的新策略对匹配问题重新回放，最后生成 D 的跨策略奖励轨迹
数据。

## Stage 2 决策

已完成的 v7 证据身份：

- Batch：`policy-v7-v2-stage2-final`
- Policy：`agentic-rag-v7`
- 计划指纹：`5c33862fb433fc9248155bbb64561fa0a3a070a525f6c9006a36dcf093fce3af`
- 完成运行：100/100（Single/Multi 各 50）
- 质量与路由：全部通过
- 严格门禁：仅 `timeout_count=1` 未通过，根因为有日志证据的百炼 `Arrearage`
- 治理状态：`COMPLETED_WITH_EXTERNAL_INCIDENT_WAIVER`

Waiver `RL-S2-2026-07-29-001` 只允许忽略该已归因的外部计费事件来推进阶段，不修改
Manifest，也不允许豁免质量、路由或后续阶段失败。完整决策见
[`RL_STAGE2_EXTERNAL_INCIDENT_WAIVER.md`](RL_STAGE2_EXTERNAL_INCIDENT_WAIVER.md)。

## Stage 3 当前状态

首批 150 个唯一问题 × 2 轮已完成，计划指纹为
`3230c4faf444af17f09aa70f3b44f0cca311e119729132dd373775cae2347c94`。计划共 300 条，
Single/Multi 各 150，路由偏差 0。119 个问题满足两轮均无违规、无超时、路由匹配且
每轮 RLVR ≥ 0.70 的高置信契约，超过 82 个门槛；31 个问题被淘汰。实际为 1,864 次调用、
3,233,393 Token、估算 ¥1.0572933。

Stage 3 已完成，不自动执行剩余 150 个种子。完整结果见
[`RL_STAGE3_COLLECTION_REPORT.md`](RL_STAGE3_COLLECTION_REPORT.md)。下一步是 Stage 4
的四 Judge RLAIF 与 20–30 条人工锚点；Judge 模型调用仍需单独授权。Stage 4 已完成
119 条显式轨迹白名单、476 次 Judge 调用上限和 30 条风险分层人工抽检的零调用冻结，
详见 [`RL_STAGE4_JUDGE_PLAN.md`](RL_STAGE4_JUDGE_PLAN.md)。

## 授权边界

以下三类操作分别需要显式授权，不能由“继续开发”自动推定：

1. 设置 `AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true`：真实 Agent 回放；
2. 开启 Alignment Auto Evaluate 或手动批评审：四 Judge 调用；
3. 设置 `BAILIAN_RL_ALLOW_BILLING=true --execute`：百炼付费训练。

每个付费阶段都先用上一阶段的真实 Token、调用数和费用估算更新预算，再决定是否扩量。
