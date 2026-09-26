# Jev / Laya 影子路由对照报告

## 结论

Laya 已作为**本地影子 challenger**接入同一套 System One 协议与评测口径，但不会接管生产
路由。独立开发集为 Laya 锁定阈值 `0.75` 后，36 题冻结回放显示：Laya 的 multi-Agent 召回率
和 balanced accuracy 明显优于 Jev，固定反事实下的平均质量与效用遗憾也略好；Jev 的路由
准确率、成本过滤和安全识别更强。

两者目前都未通过完整发布门禁。推荐保留 Jev 作为安全与保守过滤基线，Laya 作为多 Agent
召回 challenger，继续只做影子记录；不能把两者的长处拼接后声称已经得到一个通过门禁的
ensemble，除非该组合在新的预注册冻结集上重新验证。

## 运行身份与隔离

- 日期：2026-09-26 UTC
- Laya：`0.3.20`，本地 `multilingual` checkpoint，MPS
- Checkpoint revision：`55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851`
- 服务协议：Jev-compatible `POST /v1/systemone`
- 开发集：24 条，SHA-256 `9fa28d51a0f42560b869516d0e1f1d004604ab798091e67225c597ea0f534291`
- 冻结回放集：36 条，SHA-256 `bfe6819ca9196c1752331f6c20b0be7c499a7feee73b1d32340d6f9d0dcf9246`
- 固定反事实来源：`rag-ab-1e95a150-dafb-4148-be1c-2135dc33d1b2`
- Benchmark SHA-256：`f27bb073a32093756a73e2b6f0255da5f3d4184c951dfa3529a12f39e1c044b1`

Laya 的 `0.75` 阈值只由 24 条开发集选择，随后原样用于 36 条冻结回放。冻结集上的同阈值
扫描结果没有用于继续调参。Laya 官方也明确说明其置信度与 Jev 不同，阈值不能直接迁移；
详见 [Laya 官方仓库](https://github.com/NandhaKishorM/laya)。

## 独立开发集

| 指标 | Jev 0.20 | Laya 0.75 |
|---|---:|---:|
| 可用率 | 100% | 100% |
| Accuracy | 100.00% | 87.50% |
| Multi-Agent precision | 100.00% | 66.67% |
| Multi-Agent recall | 100.00% | 100.00% |
| Specificity | 100.00% | 83.33% |
| Balanced accuracy | 100.00% | 91.67% |
| Safety recall | 100.00% | 0.00% |
| 平均决策延迟 | 171.04ms | 265.08ms |

开发集仅用于锁定候选阈值，不能作为简历效果数字。Laya 的零样本安全判断漏掉 4/4 个正例，
因此安全动作必须继续由 Jev 或现有安全护栏负责。

## 36 题冻结路由结果

| 指标 | 当前路由 | Jev 0.20 | Laya 0.75 |
|---|---:|---:|---:|
| Accuracy | 47.22% | 75.00% | 69.44% |
| Multi-Agent precision | 21.05% | 33.33% | 40.00% |
| Multi-Agent recall | 50.00% | 12.50% | 75.00% |
| Specificity | 46.43% | 92.86% | 67.86% |
| Balanced accuracy | 48.21% | 52.68% | 71.43% |
| Safety recall | — | 100.00% | 0.00% |

Laya 选择 15/36 走多 Agent，其中 6 个 true positive、9 个 false positive，漏掉 2/8 个
oracle positive。它通过了 balanced accuracy 与 multi-Agent recall 门槛，但路由准确率低于
80%，且安全样本 4/4 漏召回，所以整体仍为 `FAIL`。

## 固定反事实工程结果

| 指标 | 当前路由 | Jev 0.20 | Laya 0.75 |
|---|---:|---:|---:|
| 平均质量 | 0.9077 | 0.9403 | 0.9479 |
| 平均效用遗憾 | 0.0690 | 0.0268 | 0.0249 |
| 路径总成本 | CNY 19.4680 | CNY 10.8489 | CNY 15.2794 |
| 平均路径延迟（含决策） | 35.24s | 21.29s | 28.38s |

相对当前路由，Laya 在这批固定答案上质量增加 `0.0402`、遗憾降低 `63.96%`、路径成本降低
`21.52%`、平均延迟降低 `19.46%`。相对 Jev，Laya 更愿意调用多 Agent，因此质量与召回略高，
但路径成本约高 `40.8%`、平均延迟约高 `33.3%`。这些是对同一批历史生成结果的反事实选择，
不是新的端到端生成实验，也不证明线上统计显著提升。

Laya 冻结回放为 36/36 可用，平均本地决策延迟 `156.44ms`，输入 Token `19,620`。本地推理
没有 API 费用，但报告没有把硬件、电力或运维成本伪装成零成本优势。

## 可复现命令

从一个已完成且包含强制单/多 Agent 结果的报告生成冻结回放集：

```bash
jq -c -f scripts/system-one-replay-dataset.jq \
  tmp/evaluation/rag-ab-1e95a150-dafb-4148-be1c-2135dc33d1b2.json \
  > /private/tmp/system-one-frozen-replay.jsonl
```

用候选 provider 和预先锁定阈值启动 Cortex，设置：

```bash
export AGENT_EVALUATION_SYSTEM_ONE_CALIBRATION_DATASET=file:/private/tmp/system-one-frozen-replay.jsonl
export AGENT_SYSTEM_ONE_MULTI_AGENT_THRESHOLD=0.75
```

调用 `POST /api/agent-evaluation/system-one-calibration/runs` 保存决策报告后，重算固定反事实：

```bash
jq -n \
  --slurpfile benchmark tmp/evaluation/rag-ab-1e95a150-dafb-4148-be1c-2135dc33d1b2.json \
  --slurpfile decisions /private/tmp/laya-frozen-replay.json \
  -f scripts/system-one-frozen-replay.jq \
  > /private/tmp/laya-frozen-counterfactual.json
```

脚本沿用生产评测的默认效用参数：成本权重 `0.05`、延迟权重 `0.05`、成本预算 CNY `0.02`、
延迟预算 `60s`。它不会在 jq 中重跑 10,000 次 paired bootstrap，因此只输出 deterministic
gate，并把完整 release gate 保持为未通过；正式发布仍以 Java 评测服务的完整报告为准。若项目
配置改变，必须同步修改脚本或改为从报告读取配置后再比较。
