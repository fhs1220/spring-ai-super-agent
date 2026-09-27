# 五题开发回归 v3：v6 上线前预检协议

本轮沿用 [v1 预注册](SYSTEM_ONE_PILOT_PROTOCOL.md) 中固定的五题、顺序、模型、温度、Token 上限、估算价格、Judge 量表及人工边界。样本已被用于开发，只能验证 v6 契约和标注流程，不能作为 Jev/Laya 路由收益、独立泛化或发布证据。本轮不调用 Jev/Laya、不调整路由阈值、不训练，也不自动批准标签。

- 独立目录：`tmp/system-one-bootstrap-pilot-v3`；v1/v2 证据保持原样。
- 打包并冻结源码 SHA、JAR、语料、索引、题集和配置指纹。请求的主模型和 Judge 仍为 OpenRouter `openai/gpt-5.4`；供应商别名不证明底层权重已固定。
- 最多逐题提交五题。生成与 Judge 估算费用停止阈值为 10 CNY，非供应商硬账单上限；每题核对完整账本后再继续。未知费用、失败、空答案、Multi 降级 Single 或提交结果不明立即停止，不自动重试或替换。
- 主要观察 v6 的最终答案契约：通用套话、四句任务、六周逐周检查点、负担表单位/周期与待审原因。安全题单独核对即时建议与引用是否支持具体主张；机器 Judge 分数不代替人工审核。
- 工程通过要求五组有效 Single/Multi 配对、五份 Judge、完整调用账本，以及每路最终契约和待审状态一致。逐题报告质量、Token、费用和延迟；不汇总为质量提升百分比。
- OpenRouter 价格应在实际付费前核对；输入/输出估算暂沿用 USD 2.50/15.00 每百万 Token及固定 7.2 CNY/USD 会计换算。query embedding 不在生成/Judge attempt 账本中，供应商窗口用量差值不能单独当成本实验账单。

运行使用固定的 `v3` 选择器，脚本不接受任意输出路径或预算覆盖：

```bash
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/prepare-system-one-pilot.mjs
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/system-one-pilot.mjs account-before
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/system-one-pilot.mjs serve
# 另一个终端，每次提交后等待完成并核对账本
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/system-one-pilot.mjs submit-one
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/system-one-pilot.mjs status
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/system-one-pilot.mjs account-after
SYSTEM_ONE_PILOT_ROUND=v3 node scripts/render-system-one-pilot-review.mjs
```

只有预检验证了真实模型生成和证据链，才能准备新的 20 条开发样本。20 条样本仍属于开发 pilot；代表真实流量的独立测试集需另外采样和冻结。
