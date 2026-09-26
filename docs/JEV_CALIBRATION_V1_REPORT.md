# Jev Calibration v1 运行报告

## 结论

在与冻结 36 题分离的 24 条 bootstrap 开发集上，Jev 的 multi-Agent 概率排序正确，但原阈值
`0.65` 严重偏高：6 个正例全部漏召回。阈值扫描选择 `0.20` 后，开发集上的 multi-Agent 与
安全分类均为 24/24 正确。该结果用于锁定候选配置，不能作为最终简历效果数字。

## 运行身份

- 日期：2026-09-26 UTC
- 模型：`OPENROUTER_JEV:typesafe/jev-1.13-20260917`
- 数据集：`classpath:evaluation/system-one-calibration-v1.jsonl`
- 数据集 SHA-256：`9fa28d51a0f42560b869516d0e1f1d004604ab798091e67225c597ea0f534291`
- 样本：24（6 multi-Agent positive、18 single/hard negative、4 safety positive）

## 指标

| 指标 | 阈值 0.65 | 阈值 0.20 |
|---|---:|---:|
| Accuracy | 75.00% | 100.00% |
| Multi-Agent precision | 0.00% | 100.00% |
| Multi-Agent recall | 0.00% | 100.00% |
| Specificity | 100.00% | 100.00% |
| Balanced accuracy | 50.00% | 100.00% |
| False positive / false negative | 0 / 6 | 0 / 0 |

安全护栏在阈值 0.50 下 precision、recall、specificity 和 balanced accuracy 均为 100%，
false positive/negative 均为 0。安全判断未参与 multi-Agent 动作计算。

single/hard negative 的 multi-Agent 概率为 `0.04–0.16`，positive 为 `0.23–0.36`；开发集在
0.20 处存在明确间隔。阈值扫描步长为 0.05。

## 成本与延迟

- 输入 Token：20,811
- 输出 Token：2,592
- 估算费用：USD 0.00087406
- 平均决策延迟：171.04ms

## 证据边界

开发集标签经过人工审阅，但尚不是全部由强制单/多 Agent 的实测效用生成。100% 结果只说明
这批开发样本存在清晰分界，不说明泛化性能。阈值已经预先锁定为 0.20；冻结 36 题只用于下一次
正式回归，若失败也不能继续用该冻结集调参。
