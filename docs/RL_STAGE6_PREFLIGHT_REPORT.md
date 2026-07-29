# RL Stage 6 A/B/C 提交前预检报告

## 结论

Stage 6 的 A/B/C 三臂零计费预检通过，状态为 `DRY_RUN_PASSED`。本次没有连接百炼训练
服务，没有创建函数、数据上传或训练任务：

- 云端训练任务：0
- 模型调用：0
- 付费操作：0
- Stage 5 冻结指纹：
  `7ffc5536454259debf9fae0dd01a9008808b0726dc91434e139dc5e77c51b0cb`
- Stage 6 计划指纹：
  `c8d95784a7b6352eb786b26faae35dfb39da325a497f23d3330f897d7dbcf4b8`

预检 Manifest 位于：

`tmp/agent-rl/stage6/policy-v7-stage6-abc-v2/preflight.json`

## SDK 契约修正

原项目固定的公开 PyPI `dashscope==1.25.16` 不包含
`dashscope.finetune.agentic_rl`，无法执行项目中的 `AgenticRL.run()` 提交代码。根据
百炼当前 Agentic RL 示例并经离线 wheel 内容验证，项目修正为：

- `dashscope==1.25.23`
- `FC_PYPI_LIB=dashscope-1.25.23-py3-none-any.whl`
- 本地显式补齐 `pydantic`、`PyYAML`、`tenacity`

预检已真实导入：

- `dashscope.finetune.agentic_rl.AgenticRL`
- `dashscope.finetune.reinforcement.RolloutFunctionComponent`

提交脚本会拒绝错误 SDK 版本或错误 wheel 文件名。

SDK 1.25.23 的 `AgenticRL.run()` 使用 `resources` 参数；旧代码传入的
`resource_config` 可能被 `**kwargs` 吞掉，现已按真实 SDK 签名修正并加入回归测试。
配置也已升级为百炼官方 9B 快速验证档：24 个 MTU4、`n_rollouts=8`、
`learning_rate=2e-6`、`eval_steps=1`、1 个 epoch。Stage 5 因配置指纹变化已离线重新
冻结为 v2，训练数据与验证数据内容不变。

## 三臂冻结数据

| Arm | 训练 | 验证 | Batch Size | 状态 |
|---|---:|---:|---:|---|
| `BASELINE_STATIC_REWARD` | 95 | 24 | 64 | `DRY_RUN_PASSED` |
| `RLVR_ONLY` | 95 | 24 | 64 | `DRY_RUN_PASSED` |
| `RLVR_RLAIF` | 66 | 7 | 64 | `DRY_RUN_PASSED` |

每臂均重新验证了：

- 配置、训练集和验证集 SHA-256 与 Stage 5 Manifest 一致；
- 训练集数量严格大于 Batch Size；
- 验证集非空；
- 训练/验证问题无重叠；
- 固定 Benchmark 审计通过，泄漏数为 0；
- Reward Schema 与实验臂一致。

## 防重复计费编排

`bailian-agent-rl/run_stage6_training.py` 固定按 A → B → C 顺序提交。真实执行时：

1. 每臂发起请求前先原子写入 `SUBMITTING`；
2. 获得 Job ID 后立即写入 `SUBMITTED`；
3. 已有 Job ID 的臂不会重复提交；
4. 如果请求中断且无法确认云端是否创建任务，状态冻结为
   `SUBMISSION_OUTCOME_UNKNOWN` / `RECONCILIATION_REQUIRED`；
5. 未在百炼控制台人工核对前，编排器拒绝继续，避免重复创建付费任务。

真实执行仍同时要求 `--execute`、`BAILIAN_RL_ALLOW_BILLING=true`、四个运行环境变量和
正确的离线 wheel。创建第一个计费任务前还会实时探测公网检索环境：正确令牌必须返回
HTTP 200 和非空文档，随机错误令牌必须返回 HTTP 401；任何一项失败都会在零提交状态
终止。

## 尚未满足的真实提交条件

当前不应启动付费训练，仍需：

1. 部署百炼云端可访问且能覆盖完整训练时长的稳定 HTTPS 检索环境；匿名临时隧道仅用于
   连通性预检，不作为长时间训练承诺；
2. `AGENT_RL_RETRIEVAL_URL` 和独立检索令牌已完成一次公网 200/401 验证，正式提交前
   仍由编排器实时复验；
3. 确认接受 `qwen3.5-9b` 官方 Demo 最低 24 个 MTU4；按 2026-07-29 官方后付费
   单价估算为 ¥984/小时/任务，函数计算费用另计，提交时仍须复核控制台价格；
4. 获得 Stage 6 A/B/C 三个任务的单独付费执行授权。

上述条件不影响本报告的零计费 Dry Run 结论。
