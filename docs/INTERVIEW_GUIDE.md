# 面试讲解指南

## 30 秒项目介绍

这是一个可运行的 Agentic RAG 与自适应多 Agent 系统。它不是把多个提示词串起来：
系统会先规划、混合检索、验证和补充检索，再根据任务复杂度选择单 Agent 或并行专业
Agent；所有阶段记录可训练轨迹，学习策略经过独立时间留出验证、反事实评测、灰度质量
守卫和渐进式发布后才能接管流量。

## 5 分钟演示顺序

1. 打开 `http://127.0.0.1:5173/love`，发送一个单领域沟通问题，展示
   `SINGLE_AGENT`、引用和 Agent Trace。
2. 发送同时包含育儿、家务和家庭财务的一周计划，展示 Complexity Router、并行专业
   Agent、Shared Evidence Blackboard、综合与审查。
3. 展开右侧 Agent RL 面板，说明奖励、Token、成本、策略资产、SHADOW/CANARY/ACTIVE
   和自动回退。
4. 调用 `GET /api/agent-evaluation/benchmark/metadata`，展示固定基准版本、SHA-256
   指纹和单双 Agent 标签分布。
5. 展示一次四路报告：传统 RAG、强制单 Agent、强制多 Agent、自适应 Agentic RAG。

## 关键设计取舍

- 为什么不让所有问题都走多 Agent？多 Agent 通常增加延迟与成本，简单问题的边际收益低。
- 为什么需要强制单/多 Agent？只有固定两条处理臂，才能测量 Router 是否真的选对。
- 为什么评测轨迹不进入 RL？离线基准不是自然用户分布，混入训练会形成数据泄漏和策略偏差。
- 为什么不直接用训练集分数发布？候选策略必须通过较新时间窗口的独立留出验证。
- 为什么还要 OPE？CANARY 的探索日志包含行为概率，可用 SNIPS 评估冻结策略而不直接全量上线。
- 为什么需要三道自动发布开关？把“生成建议”和“改变真实流量”的权限彻底分离。
- Agent 失败怎么办？独立超时、有限重试、熔断、可用贡献综合，全部失败再降级单 Agent。
- 如何防止 RAG 提示注入？知识片段被明确视为不可信数据，生成和审查阶段都禁止执行片段指令。

## 可以诚实说明的边界

- 当前文件仓库适合本地演示，生产环境应迁移到 PostgreSQL/Redis。
- 传统 RAG 没有完整 Token 遥测，报告明确标记 usage unavailable，不把未知成本当作零。
- 固定基准提供可复现工程门禁；真实模型效果必须运行全量评测后再引用，仓库不伪造结果。
- 自动发布默认 DRY RUN，面试演示不会意外改变真实路由流量。

## 常用命令

```bash
# 无模型费用的完整工程验收
bash verify-interview-v1.sh

# 启动两条样本的真实四路烟雾评测
curl -X POST http://127.0.0.1:8123/api/agent-evaluation/ab-runs \
  -H 'Content-Type: application/json' \
  -d '{"maximumCases":2}'
```
