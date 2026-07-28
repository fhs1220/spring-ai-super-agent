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

## 自动评分运行面

自动评分不是无上限后台循环。定时任务和批量手动接口统一经过
`AlignmentAutomationService`：

- 默认每批 10 条、每日最多 50 条轨迹；
- 暂停状态、每日用量、连续失败和冷却时间原子保存到
  `tmp/agent-rl/alignment-automation.json`；
- 连续 3 个批次全部 Judge 面板不完整或批处理异常时，进入默认 30 分钟冷却；
- 服务在批次中重启会自动恢复为失败状态，防止 `RUNNING` 假死；
- 公开前端只读取 pending、已评审、当日额度和错误摘要，不返回问题、答案或 Judge 理由；
- 评分执行、暂停和失败电路重置只存在于默认关闭的 Agent RL 管理 API。

推荐的受保护操作接口：

```http
GET  /api/agent-rl/alignment/automation
POST /api/agent-rl/alignment/automation/run?limit=10
POST /api/agent-rl/alignment/automation/control
Content-Type: application/json

{"paused":false,"resetFailureCircuit":true,"reason":"operator approved"}
```

自动调度默认仍为 OFF；“具备自动评分能力”不等于应用启动后会自动产生费用。

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

量化报告必须使用同一份固定 benchmark、相同上游基础模型架构和相同最大调用预算，但四组
必须是经过不同训练方案得到的四个真实模型资产：

| 实验组 | RLVR | AI Judge | 轨迹筛选 |
|---|---:|---:|---:|
| A：旧基线 | 静态 Reward | 否 | 否 |
| B：RLVR | 是 | 否 | 否 |
| C：RLAIF | 是 | 是 | 否 |
| D：完整方案 | 是 | 是 | 是 |

训练数据和 Reward 必须真实区分：

- `RLVR_ONLY` 只应用确定性奖励和去重/质量下限，不要求 AI Judge；
- `RLVR_RLAIF` 在其上要求多 Judge 高置信一致批准；
- `FULL_TRAJECTORY_GUIDED` 继续要求同任务至少两轮奖励变化，并根据同任务组人工锚点的
  奖励趋势相似度执行选择；
- 静态基线与 `RLVR_ONLY` 可以使用相同原始样本，以单独测量 Reward 函数变化，但必须各自
  训练成不同模型资产。

四份百炼配置位于 `bailian-agent-rl/experiments/configs/`。云端 Reward Runtime 会读取
`AGENT_RL_ALIGNMENT_ARM`：静态基线只使用参考质量和任务完成度，其他三臂使用完整 RLVR
硬门禁及 anti-hacking brake。RLAIF 和 trajectory-guided 的增量来自数据筛选。

正式实验先创建不可变的四臂清单。模型资产和训练配置使用 64 位 SHA-256 指纹；四个模型
资产指纹必须互不相同，因此不能把同一个模型重复运行四次后换名字冒充消融：

```http
POST /api/agent-evaluation/alignment-experiments
Content-Type: application/json

{
  "arms": [
    {
      "arm": "BASELINE_STATIC_REWARD",
      "modelVersion": "baseline-model",
      "modelArtifactFingerprint": "<64-hex-sha256>",
      "trainingConfigFingerprint": "<64-hex-sha256>",
      "rewardSchemaVersion": "static-reward-v1",
      "sourceDeployment": "bailian-baseline"
    },
    {
      "arm": "RLVR_ONLY",
      "modelVersion": "rlvr-model",
      "modelArtifactFingerprint": "<different-64-hex-sha256>",
      "trainingConfigFingerprint": "<64-hex-sha256>",
      "rewardSchemaVersion": "human-light-rlvr-v2",
      "sourceDeployment": "bailian-rlvr"
    },
    {
      "arm": "RLVR_RLAIF",
      "modelVersion": "rlaif-model",
      "modelArtifactFingerprint": "<different-64-hex-sha256>",
      "trainingConfigFingerprint": "<64-hex-sha256>",
      "rewardSchemaVersion": "human-light-rlvr-v2",
      "sourceDeployment": "bailian-rlaif"
    },
    {
      "arm": "FULL_TRAJECTORY_GUIDED",
      "modelVersion": "full-model",
      "modelArtifactFingerprint": "<different-64-hex-sha256>",
      "trainingConfigFingerprint": "<64-hex-sha256>",
      "rewardSchemaVersion": "human-light-rlvr-v2",
      "sourceDeployment": "bailian-full"
    }
  ]
}
```

每个部署运行评测前，配置 `AGENT_EVALUATION_MODEL_VERSION`、
`AGENT_EVALUATION_MODEL_ARTIFACT_FINGERPRINT`、`AGENT_EVALUATION_TRAINING_CONFIG_FINGERPRINT`、
`AGENT_EVALUATION_REWARD_SCHEMA_VERSION` 和 `AGENT_EVALUATION_SOURCE_DEPLOYMENT`。完成后逐臂
挂接证据并最终汇总：

```http
POST /api/agent-evaluation/alignment-experiments/{experimentId}/arms/{arm}/evidence
Content-Type: application/json

{"evaluationRunId":"rag-ab-..."}

POST /api/agent-evaluation/alignment-experiments/{experimentId}/finalize
```

编排器会拒绝身份不匹配、复用同一 `runId`、模型资产重复、benchmark 版本/指纹或样本数
不一致的证据。创建、挂接和最终报告均支持幂等重试；清单和结果持久化到
`tmp/evaluation/alignment-experiments` 与 `tmp/evaluation/alignment-ablation`。

百炼产物完成后使用 `experiment_manifest.py` 计算训练配置、数据集、Reward/Rollout 代码
及不可变云产物描述符的指纹。`run_experiment.py` 默认只打印操作计划，只有 `--execute`
才会写入本地实验状态；它不会启动任何付费训练或评测。

报告还会按照 `caseId` 对四组运行做逐样本配对统计。默认使用由 benchmark 指纹和实验臂
派生的固定随机种子执行 10,000 次 percentile bootstrap，输出：

- 平均/中位质量差值和 95% 置信区间；
- 配对标准化效应量；
- 胜/平/负数量，默认 `|Δ| <= 0.03` 视为平局；
- 去除平局后的精确双侧符号检验 p 值；
- Bootstrap 均值为正的比例 `P(Δ>0)`；
- 相对静态 Reward 基线的非劣性结论。

发布门禁不强制“显著优于”基线，因为安全升级首先需要证明不劣；但只有置信区间完全高于
0 时才会标记 `statisticallySignificant=true`，才能在面试或简历中声称统计显著提升。
两条烟雾评测可以验证链路，却会因为少于默认 30 个配对样本而被发布门禁拒绝。

统计参数可以通过以下环境变量调整：

- `AGENT_RL_ALIGNMENT_ABLATION_BOOTSTRAP_ITERATIONS`
- `AGENT_RL_ALIGNMENT_ABLATION_CONFIDENCE_LEVEL`
- `AGENT_RL_ALIGNMENT_ABLATION_MINIMUM_PAIRED_CASES`
- `AGENT_RL_ALIGNMENT_EXPERIMENT_DIRECTORY`
- `AGENT_RL_ALIGNMENT_ABLATION_PAIRED_WIN_DELTA`

第一版发布门禁建议：

- 固定评测集泄漏数必须为 0；
- 候选总体质量不得低于当前基线，最大回归沿用现有 A/B 门禁；
- 完整方案至少有 30 个逐样本配对，且质量差值置信区间下界通过非劣界；
- 路由准确率、成本比例沿用现有评测门禁；
- 重复评审决策一致率目标不低于 90%；
- `highDisagreementRate` 目标低于 15%；
- 轨迹选择样本的平均置信度不低于 0.80；
- 奖励投机/对抗样本通过率目标低于 5%。

这些是发布目标，不是当前实验结论。只有产生真实 A/B 报告后，才能对外宣称具体提升。
