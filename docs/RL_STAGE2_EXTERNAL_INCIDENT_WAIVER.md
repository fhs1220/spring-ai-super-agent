# RL Stage 2 外部事件豁免

## 决策

- Waiver ID：`RL-S2-2026-07-29-001`
- 决策日期：2026-07-29
- 状态：`APPROVED`
- Stage 2 状态：`COMPLETED_WITH_EXTERNAL_INCIDENT_WAIVER`
- 适用 Batch：`policy-v7-v2-stage2-final`
- Policy：`agentic-rag-v7`
- 计划指纹：`5c33862fb433fc9248155bbb64561fa0a3a070a525f6c9006a36dcf093fce3af`

项目负责人确认接受一次已归因的外部计费事件豁免，并继续 Stage 3。该决定不修改原始
Manifest；其中 `replay_gate.passed=false` 和失败项 `timeouts_within_limit` 必须保持
原样。

## 事件与证据

首次执行完成 93/100 条后，百炼返回 `Arrearage`。SDK 重试占满阶段级 60 秒，使第 94 个
逻辑 Run 被记录为 `FAILED`。账户充值后，回放器验证同一 Batch、策略、计划指纹和 93 条
确定性结果前缀，从失败 Run 的第 2 次 attempt 恢复，最终完成 100/100 条。

最终证据：

| 指标 | 结果 |
|---|---:|
| 完整轨迹 | 100/100 |
| Single / Multi | 50 / 50 |
| 路由匹配 / 偏差 | 100 / 0 |
| RLVR 平均分 | 0.759502 |
| RLVR 硬门禁 | 100/100 |
| RLVR 违规 | 0 |
| 审计超时 | 1 |
| 严格 Release Gate | 失败：`timeouts_within_limit` |

详细证据见
[`RLVR_V3_STAGE2_50X2_REPORT.md`](RLVR_V3_STAGE2_50X2_REPORT.md)。

## 豁免范围

仅豁免以下一项：

- 已由后端日志归因为百炼 `Arrearage` 的 1 次恢复超时。

明确不豁免：

- RLVR 硬门禁或确定性契约失败；
- RLVR 平均分不足；
- 路由偏差；
- 未完成轨迹；
- 未知原因、模型质量或项目代码导致的超时；
- Stage 3 及后续阶段的新失败。

## 风险与补偿控制

接受的剩余风险是：本 Batch 没有证明 100 个 Run 能在完全无外部计费中断的单次窗口内
结束。该风险不改变 100 条完成轨迹的质量结论，但不能被描述为原始零超时门禁通过。

补偿控制：

1. Stage 3 继续逐 Run 原子保存 Manifest，并保留恢复前错误与超时；
2. 真实采集前确认百炼账户可用，先执行零调用 Dry Run；
3. Stage 3 使用独立候选采集门禁，任何超时轨迹及其配对问题均不得进入高置信训练集；
4. 报告和面试材料必须使用“Stage 2 经外部事件豁免完成”，不能写成“严格门禁全部通过”。

