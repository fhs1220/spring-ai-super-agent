# RL Stage 4 人工锚点评审说明

## 目的

Stage 4 四 Judge 已经留下 476 个完整原始输出，但旧聚合器不能直接生成可信伪标签。人工
锚点用于校准四个维度，而不是为了事后调低阈值制造正样本。

冻结队列共 30 条：

- actions、checklist、decision、dialogue、weekly_plan 各 6 条；
- Single Agent 与 Adaptive Multi Agent 各 15 条；
- 强制包含恢复轨迹和唯一 EXCLUDED 轨迹；
- 不向评审人展示种子参考答案，避免参考答案诱导。

## 人工需要填写什么

每条样本需要填写：

1. 总体评分 1～5；
2. 指令遵循 1～5；
3. 可执行性 1～5；
4. 逻辑一致性 1～5；
5. 严格反审后仍稳健 1～5；
6. 至少 8 个字的人工理由。

评分含义：1 为不可用，2 为明显较差，3 为有好有坏需保留，4 为可用但有小问题，5 为
高质量可直接使用。人工应先独立阅读问题与回答，再展开 Judge 原始意见，不能机械照抄
Judge 分数。

## 审计与隐私

审阅页完全离线，不请求网络、不调用模型。进度保存在当前浏览器的 Local Storage。导出的
JSON 会绑定 Stage 4 Batch、计划指纹、审阅契约、30 个轨迹 ID 以及问题/回答 SHA-256。
导入器会拒绝缺项、乱序、内容变更或其他 Batch 的标签。

审阅包和导出标签都保存在 `tmp/` 或评审人的下载目录，不提交 Git。只有校验通过的标签
才能进入后续离线聚合校准；在此之前不会更新训练集或启动百炼任务。

## 生成与校验命令

生成离线页面：

```bash
python3 bailian-agent-rl/prepare_stage4_human_review.py \
  --manifest tmp/agent-rl/alignment-plans/policy-v7-stage4-judge-119.json \
  --trajectories tmp/agent-rl/trajectories \
  --assessments tmp/agent-rl/alignment-assessments \
  --output tmp/agent-rl/human-review/policy-v7-stage4-human-30.html
```

人工完成后校验：

```bash
python3 bailian-agent-rl/prepare_stage4_human_review.py \
  --manifest tmp/agent-rl/alignment-plans/policy-v7-stage4-judge-119.json \
  --trajectories tmp/agent-rl/trajectories \
  --assessments tmp/agent-rl/alignment-assessments \
  --labels policy-v7-stage4-human-labels-30.json \
  --validated-output tmp/agent-rl/human-review/policy-v7-stage4-human-labels-30.validated.json
```

## 校准后的最终盲测

30 条锚点校准已完成，但原 10 条留出集没有通过精度和负样本保护门禁。结果与后续冻结的
新鲜 10 条盲测契约见
[`RL_STAGE4_ALIGNMENT_CALIBRATION_REPORT.md`](RL_STAGE4_ALIGNMENT_CALIBRATION_REPORT.md)。

生成最终盲测页：

```bash
python3 bailian-agent-rl/prepare_stage4_final_holdout.py \
  --manifest tmp/agent-rl/alignment-plans/policy-v7-stage4-judge-119.json \
  --calibration-report tmp/agent-rl/human-review/policy-v7-stage4-alignment-calibration-v1.json \
  --prior-labels tmp/agent-rl/human-review/policy-v7-stage4-human-labels-30.validated.json \
  --trajectories tmp/agent-rl/trajectories \
  --assessments tmp/agent-rl/alignment-assessments \
  --output tmp/agent-rl/human-review/policy-v7-stage4-final-holdout-10.html
```

最终盲测页不携带四 Judge 原始意见，防止评审被旧自动评分诱导。完成后使用同一脚本的
`--labels` 和 `--validated-output` 参数校验导出文件。
