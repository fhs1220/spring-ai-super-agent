# RL Stage 4 Judge v2 隔离验证计划

## 目的

Judge v1 在 40 条人工证据上无法区分可用回答、简略回答和残缺回答，最终盲测正向精度只有
0.60，并误放行人工 1 分样本。Judge v2 不是降低聚合阈值，而是修复评审契约本身：

- 为 0～1 分数加入明确质量锚点；
- 要求逐项核对用户原子要求；
- 明确识别残句、截断、泛泛建议、无依据数字和虚构安排；
- `confidence` 只表示判断把握，不能掩盖已经发现的缺陷；
- 每个维度设置独立质量下限，不再要求不同维度分数彼此接近。

## 证据隔离

Judge v2 使用契约版本 `human-light-judge-v2`，Judge ID 也包含该版本。后端新增只读契约
端点，执行器会同时核对：

- Judge 契约版本；
- Assessment Namespace；
- 每个 Judge ID 的版本；
- 轨迹 ID、四维完整性和续跑前缀。

v2 的 Assessment Namespace 固定为
`policy-v7-stage4-judge-v2-validation`。后端必须把存储目录显式指向同名新目录；否则执行器
在任何模型调用前拒绝运行。现有 `tmp/agent-rl/alignment-assessments` 中 476 条 v1 原始
证据不会被读取、覆盖或迁移。

## 冻结验证集与门禁

本轮复用已经完成的 40 条人工标签作为 Judge 回归集，不再要求追加人工标注：

- Development：原 30 条；
- Final：新鲜盲测 10 条；
- Single/Multi、五种请求类型均已覆盖；
- 人工标签只用于本地评价，不发送给 Judge 模型。

预注册的正向决策要求：

- 指令遵循、可执行性、逻辑一致性分别至少 0.70；
- 严格反审至少 0.60；
- 四维平均至少 0.72；
- 每维置信度至少 0.65；
- 任一维度不高于 0.20 时判为负向，否则未达正向条件则 HOLDOUT。

Final 退出门禁固定为：正向精度至少 0.80、至少 3 个自动正/负决策、人工负向误放行数为
0。验证计划指纹：
`491b3cb5e8bf9e3d13278169b0ece61144810b05776115851028fbb498e77411`。

## 预算与授权

- 轨迹：40；
- 四维 Judge 上限：160 次；
- 输入 Token 规划上界：312,852；
- 输出 Token 规划上界：32,000；
- 估算费用上界：约 ¥0.1130556；
- 当前模型调用：0。

Dry Run 已生成：
`tmp/agent-rl/alignment-plans/policy-v7-stage4-judge-v2-validation-40.json`。
真实执行仍要求 `--execute` 与 `AGENT_RL_JUDGE_ALLOW_MODEL_CALLS=true` 双重授权。

