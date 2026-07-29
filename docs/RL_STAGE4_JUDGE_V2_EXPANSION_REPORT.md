# RL Stage 4 Judge v2 全量扩展报告

Judge v2 已从 40 条人工验证轨迹扩展到 Stage 4 全部 119 条白名单轨迹。执行复用已有
40 条结果，仅对剩余 79 条发起新评审：

- 复用轨迹 / Judge 输出：40 / 160；
- 新增轨迹 / Judge 输出：79 / 316；
- 最终轨迹 / Judge 输出：119 / 476；
- 不完整面板：0；
- Judge 契约：`human-light-judge-v2`；
- 扩展指纹：
  `4e5e7fc9b4534e5bf18e4a7c83c14080281626d214c5f3add90ad1830a298398`；
- 新增调用费用规划上界：约 ¥0.2261628。

正向-only 冻结契约得到 73 条 POSITIVE、46 条 HOLDOUT、0 条负标签。按 10% 固定验证
切分得到 66 条训练、7 条验证，训练数大于 Batch Size 64，Stage 5 RLAIF Readiness
通过。后端执行完成后已停止。

