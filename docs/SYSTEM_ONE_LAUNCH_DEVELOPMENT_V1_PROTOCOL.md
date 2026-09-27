# Jev/Laya 上线准备：20 题开发流程 pilot

本轮在 [v3 五题预检](SYSTEM_ONE_PILOT_V3_REPORT.md) 后执行，使用新编写的 20 条**合成开发问题**。题目覆盖单领域、多个主题但仅需短输出、否定与隐含表达、真正的跨领域协调，以及即时安全。它们不来自随机真实流量，未有人工作为路由真值，不能用于宣称上线收益、泛化准确率或统计显著性。

## 冻结输入与运行身份

- 固定数据文件：`src/main/resources/evaluation/system-one-launch-development-v1.jsonl`，20 个唯一 ID；不填写臆测的 `expectedMultiAgent` 标签。
- 固定目录：`tmp/system-one-launch-development-v1`。准备脚本要求 `src/scripts/docs` 相对 HEAD 干净，并记录源码、JAR、语料、索引及题集指纹；旧 v1/v2/v3 完全隔离。
- 使用答案契约 v7；生成与 Judge 请求 OpenRouter `openai/gpt-5.4`，温度 0.2、4096 completion Token，沿用缓存 embedding 和 v2 线性 utility。模型别名不等于供应商权重固定。
- 每次提交一题，最多 20 题，估算费用停止阈值 25 CNY；这是运行前预算保护，不是供应商硬账单封顶。价格核对口径为输入 USD 2.50、输出 USD 15.00 每百万 Token，固定 7.2 CNY/USD 用于账本估算。query embedding 与 Laya 本地算力不在此生成/Judge 账本内。
- 任一失败、未知用量、空答案、Multi 降级 Single、提交结果不明或超过估算阈值，立即停止后续题，不替换、不自动重试。隔离服务绑定 `127.0.0.1:8125`，与用户正在使用的 8123 应用隔离。

## 本轮判定

工程通过：20/20 有效 Single/Multi 配对、20/20 Judge、全部生成/Judge attempt 已计量，最终答案契约与待审理由均可追溯。逐题人工盲评需核对答案质量、来源是否支持主张、安全与实际约束；机器 Judge 不能代签。新问题也可能被本次观察反复用于修复，因此只归开发集。

本轮的样本是候选策略后续开发和故障发现材料。Jev/Laya 的独立决策调用、阈值校准和正式发布门禁需在另一个冻结运行中完成；特别是双路 Shadow 尚未在此实验中启用，不能把原始模型输出空缺当作路由预测。

## 运行入口

```bash
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/prepare-system-one-pilot.mjs
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/system-one-pilot.mjs account-before
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/system-one-pilot.mjs serve
# 另一个终端；每题先核对上一题的终态与账本
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/system-one-pilot.mjs submit-one
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/system-one-pilot.mjs status
# 结束后
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/system-one-pilot.mjs account-after
SYSTEM_ONE_PILOT_ROUND=launch-dev-v1 node scripts/render-system-one-pilot-review.mjs
```
