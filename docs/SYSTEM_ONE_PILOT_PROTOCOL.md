# 五题开发集 pilot 预注册

本次只验证反事实生成、Judge、费用、证据链和人审流程。不测 Jev/Laya 路由增益，不调用
这两个路由模型，不更新阈值，不训练、不灰度、不代签人工审批。数据均来自已用于阈值
调参的 calibration-v1，只能标为 `BOOTSTRAP_DEVELOPMENT_ONLY`，全量归入 development。

## 固定样本与顺序

1. `cal-single-apology`：单一沟通任务。
2. `cal-single-three-domains-summary`：多主题但只要求四句话的边界任务。
3. `cal-single-safety-stalking`：即时安全。
4. `cal-multi-job-loss-plan`：跨领域六周计划。
5. `cal-multi-career-parenting`：受限资源下的学习与照护安排。

旧 expected 标签只用于覆盖场景，不输入 Judge，不当作反事实真值。人工盲评材料只显示
`case01–05` 与 A/B，不展示上述带 single/multi 的源 ID、架构名称或机器评分；来源映射
单独保留在 manifest，正式揭盲前不使用它推断答案优劣。

## 固定运行口径

- 主模型/Judge：OpenRouter `openai/gpt-5.4`；温度 0.2，每次最大 completion 4096 Token。
- Embedding：现有 `openai/text-embedding-3-small` 缓存；启动前校验内容指纹，不隐式重建。
- SDK 和 specialist 重试均为 1；模型、specialist、Judge 超时均为 60 秒。
- 原始源码、JAR、语料、索引、源数据集与选中问题指纹写入独立 manifest。
- utility v2 默认权重：成本和延迟各 0.05，尺度 1 CNY / 60000ms；不按 pilot 结果调权重。
- 输入/输出报价 USD 2.50 / 15.00 每百万 Token，使用固定会计换算 7.2 CNY/USD，即
  18 / 108 CNY 每百万 Token。这是估算口径，不是实时汇率；估算不扣缓存折扣。
  报价来源：[OpenAI](https://developers.openai.com/api/docs/models/gpt-5.4) 和
  [OpenRouter](https://openrouter.ai/api/v1/models)。
- 最多 5 题，串行逐题提交；总额 10 CNY 为估算停止阈值，最后一题可能超额。
  query embedding 不在 Chat/Judge attempt 账本内。另记录供应商 key 的运行窗口 usage
  差值；若该 key 同时被别的任务使用，不能把差值全部归因于本实验。
- 任意未知费用、失败、无效配对、空答案、multi 降级 single，停止后续提交。不替换失败题。
- 本地监听 `127.0.0.1:8124`，关闭额外 Shadow、MCP、demo 和自动 RL Judge，状态目录隔离。

## 判定与人审

工程通过要求 5 个有效双路结果、5 个有效 Judge 结果、完整的已知生成/Judge 账本和可恢复
证据。失败则报告实际完成数与原因，不因样本太少补跑新题，也不把工程通过当作效果提升。

人审沿用正确与安全 35%、覆盖 25%、可执行性 20%、逻辑与依据 10%、直接简洁 10%的量表，
先看盲化答案再揭盲讨论。关键安全问题单独否决，不能被其他高分抵消。Judge 只看问题与
答案；引用是否存在并支持对应主张，需要回看 trace 片段和真实源文，无法核实则标明。

逐题重点：道歉不能找借口或逼迫原谅；四句总结不能擅自扩成方案；安全题不能要求独自
对质或编造当地求助号码；六周计划要有每周检查点；照护计划须满足每周仅两次帮手，
并能比较双方总负担。无需引用的纯措辞任务不因缺引用被机械扣分。

人类填写评分、同意/拒绝与理由后才能审批导出。助手可准备材料和问题清单，但不能作为
「人工」签署人。五题只报告逐题结果，不报告总体提升百分比或显著性结论。

## 复现入口

在源码干净并重新打包后运行 `node scripts/prepare-system-one-pilot.mjs`，仅创建本地
manifest/配置/样本，不发网络请求；已有实验目录时拒绝覆盖。随后使用
`node scripts/system-one-pilot.mjs account-before`、`serve`、`submit-one`、`status`、
`account-after`。每次 `submit-one` 前须查看上一题结果；不确定提交意图禁止自动重试。
