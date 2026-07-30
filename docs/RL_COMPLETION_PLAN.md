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

119×4 真实 Judge 已完成，执行完整性 119/119，但监督标签门禁未通过：118 条 HOLDOUT、
1 条 EXCLUDED、自动正向 0。根因是旧聚合器把四个不同评价维度的分数差异当成同类评委
分歧并重复惩罚置信度。不得直接进入训练；先完成 30 条真实人工锚点和离线聚合校准。详见
[`RL_STAGE4_JUDGE_REPORT.md`](RL_STAGE4_JUDGE_REPORT.md)。

30 条离线人工锚点已于 2026-07-29 完成并校验通过：23 条正向、6 条保留、1 条负向，
平均总体评分 4.033333。维度感知聚合器已离线拟合，但原 10 条留出集正向精度只有
0.666667，且误放行 1 条人工负向锚点，因此 Stage 4 仍未通过，不导出训练数据。

原留出集已经用于诊断，现已从 89 条从未人工查看的剩余轨迹冻结 10 条最终盲测，覆盖
5 种请求类型 × 2 种执行模式，不展示 Judge 意见且不调用模型。最终盲测已完成，但
v1 聚合器将 10 条全部判为正向，实际精度 0.60，并误放行 1 条人工 1 分样本。Stage 4
冻结为 `FAILED_AUTOMATED_ALIGNMENT_GATE`；不再用追加小批人工或移动阈值制造通过。
完整证据见
[`RL_STAGE4_ALIGNMENT_CALIBRATION_REPORT.md`](RL_STAGE4_ALIGNMENT_CALIBRATION_REPORT.md)。

Stage 5 已独立冻结不依赖 RLAIF 的两臂：119 条 RLVR v3 合格唯一轨迹按固定指纹切为
95 条训练、24 条验证，Benchmark 污染 0。静态 Reward 与 `RLVR_ONLY` 的百炼 Dry Run
均通过；`RLVR_RLAIF` 与 `FULL_TRAJECTORY_GUIDED` 继续由 Stage 4 门禁阻断。详见
[`RL_STAGE5_DATASET_FREEZE_REPORT.md`](RL_STAGE5_DATASET_FREEZE_REPORT.md)。

为恢复被阻断的两臂，Judge v2 的评分锚点、维度下限、独立存储 Namespace 和 40 条回归
验证计划已经零调用冻结。该计划最多调用 160 次 Judge，估算费用上界约 ¥0.1130556；
只有 v2 在固定 Final 10 条上达到正向精度 ≥ 0.80 且负向误放行为 0，才允许重新评审
119 条并恢复 RLAIF 数据导出。详见
[`RL_STAGE4_JUDGE_V2_VALIDATION_PLAN.md`](RL_STAGE4_JUDGE_V2_VALIDATION_PLAN.md)。

Judge v2 的 40×4 真实验证现已完成。Development 自动校准的正向-only 契约在 Final
10 条上放行 6 条、精度 1.0、负向误放行 0；负伪标签因精度不足被永久关闭。下一步只评审
剩余 79 条尚无 v2 结果的轨迹，复用已完成的 40 条，再检查 RLAIF 数据量门禁。详见
[`RL_STAGE4_JUDGE_V2_VALIDATION_REPORT.md`](RL_STAGE4_JUDGE_V2_VALIDATION_REPORT.md)。

剩余 79×4 扩量也已完成，新增 316 个完整 Judge 输出。全量 119 条得到 73 条正向、
46 条 HOLDOUT、0 条负标签；RLAIF 固定切分为 66 条训练、7 条验证，Batch Size 64
Readiness 通过。Stage 5 现有 A/B/C 三臂均可提交，只有 D
`FULL_TRAJECTORY_GUIDED` 仍需训练后跨策略回放。扩量证据见
[`RL_STAGE4_JUDGE_V2_EXPANSION_REPORT.md`](RL_STAGE4_JUDGE_V2_EXPANSION_REPORT.md)。

Stage 6 提交前零计费预检已完成：DashScope SDK 已从不包含 Agentic RL 模块的
1.25.16 修正为 1.25.23，SDK `resources` 参数契约和 24 个 MTU4 官方最低资源配置已
修正，A/B/C 三臂已按新配置离线重新冻结并逐文件验证通过，可恢复提交编排器已冻结。
预检未连接百炼、未创建云端任务，`billable_operations=0`。真实提交仍需公网检索环境、
明确费用确认和独立 `--execute` 授权。证据见
[`RL_STAGE6_PREFLIGHT_REPORT.md`](RL_STAGE6_PREFLIGHT_REPORT.md)。

## 授权边界

以下三类操作分别需要显式授权，不能由“继续开发”自动推定：

1. 设置 `AGENT_RL_REPLAY_ALLOW_MODEL_CALLS=true`：真实 Agent 回放；
2. 开启 Alignment Auto Evaluate 或手动批评审：四 Judge 调用；
3. 设置 `BAILIAN_RL_ALLOW_BILLING=true --execute`：百炼付费训练。

每个付费阶段都先用上一阶段的真实 Token、调用数和费用估算更新预算，再决定是否扩量。

## 低预算决策

由于 `qwen3.5-9b` 官方 Demo 最低 24 个 MTU4，Stage 6 云训练不满足“最多几十元”的
用户预算，A/B/C/D 云训练不得执行。Stage 6 保持 `TRAINING_READY_COST_BLOCKED`，
不视为技术失败，也不宣称完成百炼参数训练。

替代证据使用本地 `Qwen/Qwen2.5-0.5B-Instruct` DPO LoRA 代理实验：从 Stage 3
同题双轨迹中仅选择两轮硬门禁均通过、RLVR 分差至少 0.01、回答长度适合 M2 16GB 的
偏好对，并继续执行固定 Benchmark 污染审计。该实验云训练费用和模型 API 调用均为 0；
结果只用于证明真实参数更新和训练前后评测闭环，不冒充 9B Agentic RL。

本地代理实验已于 2026-07-30 完成。25 条冻结偏好对切为 20 条训练、5 条验证，
Benchmark 泄漏为 0；Qwen2.5-0.5B-Instruct 的 540,672 个 LoRA 参数完成真实更新，
训练耗时 82.344 秒，云训练费用和模型 API 调用均为 0。固定 36 题配对评测显示均分
从 0.724050 降至 0.721733，4 条安全题平均下降 0.020850，偏好准确率保持 0.20，
因此适配器被自动判为 `NOT_PROMOTED`，不得部署或用于 D 臂回放。完整证据见
[`RL_LOCAL_POLICY_PROXY_REPORT.md`](RL_LOCAL_POLICY_PROXY_REPORT.md)。

在当前预算约束下，RL 工程闭环和一次真实本地训练前后验证已经完成；云端 A/B/C/D
参数训练、正式四资产评测和灰度上线保持 `COST_BLOCKED`，不是待自动执行的下一阶段。
除非预算或云端资源条件变化，不继续用固定 Benchmark 反复调参，也不宣称百炼 RL 已完成。
