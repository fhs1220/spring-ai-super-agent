# System One 反事实质量标注

## 目的与边界

Jev/Laya 的分歧不是正确答案。流水线对同一问题强制执行 single-Agent 和 multi-Agent，
用隐藏架构名称、确定性交换 A/B 顺序的 AI Judge 评分，再结合费用和延迟产生候选标签。
只有人工批准的 development 样本能导出训练；流水线本身不训练模型，也不改变线上路由。

隔离评测不写 Chat Memory 或 Agent RL 训练轨迹。Judge 当前复用主 ChatModel，不是独立
人工真值；还需要通过人工一致率和重复评审检查校准。

## 质量与效用 v2

Judge 量表为正确与安全 35%、需求覆盖 25%、可执行性 20%、逻辑与依据 10%、直接简洁 10%。
分数、置信度必须存在、有限且在 `[0,1]`，理由不能为空。默认 60 秒超时；超时后如底层请求
尚未退出，该 Judge 实例不接受第二次请求。中断请求不代表远端账单已取消。

`system-one-utility-v2-linear` 使用不截断的惩罚：

```text
utility = quality
          - costWeight × estimatedCostCny / costScaleCny
          - latencyWeight × latencyMs / latencyScaleMs
```

默认 `costWeight=0.05`、`costScaleCny=1.0`、`latencyWeight=0.05`、
`latencyScaleMs=60000`，独立配置在 `agent.evaluation.system-one.utility.*`。
这避免旧版 `min(1, cost/0.02)` 在高成本样本上饱和、无法区分费用的问题。v1/v2 的 oracle
和标签不能直接混合比较；默认权重只是起点，正式实验需预注册，不可用 holdout 选权重。

默认 `multiUtility - singleUtility > 0.01` 倾向 multi-Agent。安全动作分歧、Judge 置信度
低于 0.70、距离决策边界小于 0.03 或证据不完整时进入 `REVIEW_REQUIRED`，不产生自动真值。
即使状态为 `COMPLETED`，也不等于获准训练。空答案或检测到强制 multi 降级为 single 时
跳过 Judge，避免把无效对照标成质量收益。

## 可恢复证据与费用

每个样本文件保存问题、原始 Shadow 观察、两路答案、AgentTrace（含可用引用片段）、
模型配置/源码版本/语料指纹、Judge 结果及 usage、utility 版本，以及调用 attempt 账本。
这不是完整语料快照；正式实验仍须保留相应语料与模型资产。

每次生成或 Judge 调用前写入 `PENDING`，结束后记录结果和可知费用，使用原子文件替换。
完成的答案复用；已完成 attempt 不允许改写，审批记录只追加。恢复时检查实验配置一致性，
并沿用首次冻结的 Shadow 观察，不受样本池同题覆盖影响。

- 已知费用的失败可在下次运行重试失败阶段，不重复成功生成。
- 进程退出后遗留 `PENDING`、网络异常或无法取得完整 usage 时，标为 `UNKNOWN_OUTCOME`，
  禁止自动重试和训练导出。需人工核对供应商账单；当前没有自动补账接口。
- Judge 的 Token 与按配置单价估算的费用纳入总额；缺 usage/价格时明确未知，不按免费处理。
- 运行的费用是已知 attempt 估算值之和；恢复样本包含其历史 attempt，不能解释为本次新增账单。
  `costAccountingComplete=false` 时只代表已知下界，不是最终总价。
- 标注要求 `SPRING_AI_RETRY_MAX_ATTEMPTS=1`，拒绝无法逐次记账的 SDK 隐式重试。
  应用内部可观测的 specialist 调用仍有各自轨迹；供应商最终计费以账单为准。

文件位于 `tmp/system-one-labels/{development,holdout}`，运行快照位于同目录 `runs/`。
重启后的未完成任务标为 `INTERRUPTED`，不会自动触发付费调用。存储要求单应用实例；
原子写入和进程内锁不能当作分布式任务锁。存储损坏需人工修复，不应删除文件后盲目重跑。

## 数据隔离与人工审批

问题 SHA-256 确定性分为 70% development 和 30% holdout。holdout 默认封存：

```bash
export AGENT_EVALUATION_SYSTEM_ONE_LABELING_EXPOSE_HOLDOUT_LABELS=false
```

运行报告的正负类、复核、失败统计和 sample IDs 仅来自 development，不泄漏 holdout 标签；
总费用与处理数量属于操作元数据。只有候选策略和验收指标锁定后才可临时解封 holdout。
即使解封并获人工批准，holdout 也永远不会进入 development 导出。

注意：来源是「分歧富集 + 约 10% agreement control」样本池。按问题哈希切分只做同题隔离，
不能消除选择偏差、语义近重复或时间泄漏。这里的 holdout 只能评价该采样框架；要声称整体
真实流量收益，必须另外建立独立随机抽样、按语义/场景分组且按时间隔离的测试集。

```http
GET /api/agent-evaluation/system-one-labeling/labels?split=DEVELOPMENT
GET /api/agent-evaluation/system-one-labeling/labels/{sampleId}

POST /api/agent-evaluation/system-one-labeling/labels/{sampleId}/reviews
Content-Type: application/json

{"expectedRevision":0,"reviewer":"reviewer-name","reason":"已逐项核对答案与引用，质量收益超过额外成本","accepted":true,"expectedMultiAgent":true}

GET /api/agent-evaluation/system-one-labeling/exports/development
```

审批需要完整有效的双路结果、Judge 和完整费用账本。`expectedRevision` 防止覆盖他人审批；
拒绝可提交 `accepted=false`。导出包含采样框架、版本、来源配置、审批版本和内容 SHA-256，
不是自动导入训练。`reviewer` 是本地人工声明，不是经过认证的用户身份。

## 运行 20 条 pilot

仅在受信任的本地环境开放评测 API：它可调用付费模型、读取原文和修改审批，不提供独立
身份认证。不要直接暴露公网。先收集经过隐私检查的 Shadow 原文样本，并设置实际模型价格：

```bash
export AGENT_EVALUATION_API_ENABLED=true
export AGENT_SYSTEM_ONE_COMPARISON_STORE_QUESTION=true
export SPRING_AI_RETRY_MAX_ATTEMPTS=1
export AGENT_EVALUATION_SYSTEM_ONE_LABELING_SOURCE_REVISION="<git commit SHA>"
export AGENT_EVALUATION_SYSTEM_ONE_LABELING_CORPUS_FINGERPRINT="<corpus SHA-256>"
# AGENT_RAG_INPUT_PRICE_PER_MILLION_TOKENS_CNY 和
# AGENT_RAG_OUTPUT_PRICE_PER_MILLION_TOKENS_CNY 必须与实际主模型匹配。
```

源码/语料指纹默认允许 `UNSPECIFIED` 以便本地 smoke，但这种报告不能作为正式实验的来源证明。
Judge 超时可用 `AGENT_EVALUATION_SYSTEM_ONE_LABELING_JUDGE_TIMEOUT_MS` 覆盖，默认 60000。

复用已有开发题的小样本 pilot 必须设置
`AGENT_EVALUATION_SYSTEM_ONE_LABELING_SAMPLING_FRAME=BOOTSTRAP_DEVELOPMENT_ONLY`，并以
`AGENT_EVALUATION_SYSTEM_ONE_LABELING_SOURCE_DATASET_FINGERPRINT` 提供源数据集的 64 位 SHA-256。
此模式只接受 `sampledReason=BOOTSTRAP_DEVELOPMENT_ONLY` 的观察，全部标为 `DEVELOPMENT`，
不产生 holdout 或真实流量收益证据；默认模式也拒绝这类样本。每个实验使用独立存储目录。
审批后的导出从实际样本来源推导 sampling frame，混合来源标为 `MIXED_NOT_POPULATION`。

```http
POST /api/agent-evaluation/system-one-labeling/runs
Content-Type: application/json

{"maximumCases":20,"maximumCostCny":10}

GET /api/agent-evaluation/system-one-labeling/runs/{runId}
DELETE /api/agent-evaluation/system-one-labeling/runs/{runId}
```

默认服务端最多 20 个样本、10 元估算停止阈值；0 元不调用模型。费用在阶段结束后才可知，
预算在样本之间检查，因此最后一个样本可能超额；它不是供应商侧硬消费上限。未知费用会
阻止继续处理其他样本。取消先返回 `CANCELLING`，实际 worker 退出后才释放单任务占用，
不会因点击取消而立即启动另一任务。本轮工程回归没有执行这个付费 pilot。

## 正式效果门禁

20 条 pilot 用于检查费用、失败率、Judge 稳定性和人工复核负担，不能形成简历提升结论。
扩大样本前应锁定任务分布、模型/语料/源码版本、质量量表、费用单价和 utility 权重，报告：

- 独立测试集的采样框架、数量、正负类和场景分布；
- 相对当前路由、always-single、always-multi 的配对质量差与 95% CI；
- balanced accuracy、multi-Agent precision/recall、utility/regret；
- 完整生成/Judge 费用与未知费用比例、端到端延迟；
- 安全漏报数、Judge 与人工一致率，以及重复采样稳定性。

只有预注册门禁通过，才写量化提升；否则如实展示工程系统、失败分析和实验设计成果。
