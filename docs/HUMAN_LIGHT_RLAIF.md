# Human-light RLAIF 与半监督 RLVR

本项目采用 `RLVR + RLAIF + trajectory-guided semi-supervision`。目标不是让单个模型
无条件认可自己的答案，而是使用硬验证、多评审一致性和跨策略版本的学习轨迹共同决定
训练资格。

## 自动评分链路

每条正常完成的在线轨迹依次经过：

1. RLVR 硬门禁：运行状态、答案完整性、证据忠实度和任务完成度；
2. 四个 rubric Judge：指令遵循、可执行性、逻辑一致性、严格反向审查；
3. 聚合：计算 AI Reward、Judge Agreement、Confidence 和最终 Alignment Reward；
4. 半监督标签：
   - `PSEUDO_LABELED + POSITIVE`：允许进入正样本池；
   - `PSEUDO_LABELED + NEGATIVE`：保留为偏好负样本候选；
   - `UNLABELED + HOLDOUT`：分歧或置信度不足，不参与训练；
   - `REJECTED + EXCLUDED`：硬验证失败；
   - `EVALUATION_ONLY + EXCLUDED`：固定评测数据，永不进入训练。

默认阈值：

| 参数 | 默认值 |
|---|---:|
| Judge 数量 | 4 |
| 最低一致性 | 0.70 |
| 最低置信度 | 0.72 |
| 正样本奖励阈值 | 0.75 |
| 负样本奖励阈值 | 0.35 |
| 最低证据忠实度 | 0.70 |

自动定时评审默认关闭，避免无意产生模型费用。显式设置
`AGENT_RL_ALIGNMENT_AUTO_EVALUATE_ENABLED=true` 后，调度器才会周期性处理尚未评审的
在线轨迹。

## TRAPO-inspired 轨迹选择

`TrajectoryGuidedSampleSelector` 不根据单次高分直接选择无标签数据。它按照任务组比较
多个策略版本中的奖励变化，并使用标准化轨迹余弦相似度筛选样本：

- 有标签/高可信样本是 anchor；
- 固定评测样本按 sample ID 整体排除；
- 至少积累 3 轮观测后再比较；
- 低置信、无变化、缺少同任务 anchor 的轨迹不可选；
- 选择同任务 Top-K 与超过阈值的样本并集；
- 负相似度样本即使位于 Top-K 也不会进入训练。

这属于适配托管云训练的 TRAPO-inspired 离线 curriculum，不声称逐行复现 veRL Trainer。

## 云端 RLVR v2

百炼 Reward schema 固定为 `human-light-rlvr-v2`。参考答案 token-F1 权重由旧版 35%
降低为 10%，防止模型通过复述参考答案刷分。当前九维奖励为：

| Reward 维度 | 权重 |
|---|---:|
| 参考答案质量 | 0.10 |
| 检索证据支持 | 0.20 |
| 引用编号真实性 | 0.10 |
| 指令与格式完成度 | 0.20 |
| 安全与信息边界 | 0.10 |
| 检索质量 | 0.10 |
| 收敛质量 | 0.05 |
| 调用效率 | 0.05 |
| 反奖励投机 | 0.10 |

反奖励投机会识别内部评分字段、要求高分、重复行、近乎逐字复制参考答案和暴露内部检索
轨迹，并通过乘法刹车降低总奖励。Rollout 会保存原问题和本轮文档 ID，最终答案只能引用
真实存在的 `[1]`、`[2]` 片段编号。

## 量化指标

`GET /api/agent-rl/alignment/metrics` 返回：

- `pseudoLabelCoverage`：被自动赋予高置信正/负标签的比例；
- `autoApprovalRate`：自动正样本批准比例；
- `estimatedReviewRate`：仍需复评或抽检的 HOLDOUT 比例；
- `highDisagreementRate`：Judge 一致性低于 0.70 的比例；
- `averageVerifierReward`、`averageAiReward`、`averageReward`；
- `averageConfidence`、`averageJudgeAgreement`。

轨迹选择报告额外返回选择率、平均相似度、平均置信度、不可比较样本数、评测泄漏排除数
和任务组覆盖数。

## 实验与消融设计

量化报告必须使用同一份固定 benchmark、相同模型和相同最大调用预算，对比：

| 实验组 | RLVR | AI Judge | 轨迹筛选 |
|---|---:|---:|---:|
| A：旧基线 | 静态 Reward | 否 | 否 |
| B：RLVR | 是 | 否 | 否 |
| C：RLAIF | 是 | 是 | 否 |
| D：完整方案 | 是 | 是 | 是 |

四个实验组必须分别完成一次固定基准运行，然后调用：

```http
POST /api/agent-evaluation/alignment-ablation-reports
Content-Type: application/json

{
  "arms": [
    {
      "arm": "BASELINE_STATIC_REWARD",
      "evaluationRunId": "rag-ab-...",
      "modelVersion": "baseline-model"
    },
    {
      "arm": "RLVR_ONLY",
      "evaluationRunId": "rag-ab-...",
      "modelVersion": "rlvr-model"
    },
    {
      "arm": "RLVR_RLAIF",
      "evaluationRunId": "rag-ab-...",
      "modelVersion": "rlaif-model"
    },
    {
      "arm": "FULL_TRAJECTORY_GUIDED",
      "evaluationRunId": "rag-ab-...",
      "modelVersion": "full-model"
    }
  ]
}
```

报告器会拒绝 benchmark 版本、SHA-256 指纹或样本数不一致的运行，并输出质量增量、通过率
增量、成本比例、执行失败数以及发布门禁，不允许手工拼接不可比较的数据。

第一版发布门禁建议：

- 固定评测集泄漏数必须为 0；
- 候选总体质量不得低于当前基线，最大回归沿用现有 A/B 门禁；
- 路由准确率、成本比例沿用现有评测门禁；
- 重复评审决策一致率目标不低于 90%；
- `highDisagreementRate` 目标低于 15%；
- 轨迹选择样本的平均置信度不低于 0.80；
- 奖励投机/对抗样本通过率目标低于 5%。

这些是发布目标，不是当前实验结论。只有产生真实 A/B 报告后，才能对外宣称具体提升。
