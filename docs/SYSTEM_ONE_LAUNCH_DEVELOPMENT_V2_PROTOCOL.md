# 20 题开发流程 pilot v2：运行前冻结协议

使用 [v1 固定 20 题](SYSTEM_ONE_LAUNCH_DEVELOPMENT_V1_PROTOCOL.md) 的相同合成问题、顺序、主模型、Judge、价格估算、预算和停止规则，验证答案契约 v8 修复。v1 只提交首题并因字数契约冲突停止；其结果和目录保持原样。v2 使用新的源码提交与 `tmp/system-one-launch-development-v2`，不能续写 v1 状态。

本轮仍是开发流程实验：题目由助手编写，已知首题被用来修复代码；不形成独立路由准确率或质量收益声明。20 个真实 Single/Multi 配对、Judge、调用账本和待审证据是工程目标。若任何题失败、降级、费用未知或再次发现影响标签意义的系统性契约冲突，立即停止后续提交，保留部分运行结果。

服务固定 `127.0.0.1:8125`，估算停止阈值 25 CNY（不是供应商硬封顶）。运行入口仅将 [v1 协议](SYSTEM_ONE_LAUNCH_DEVELOPMENT_V1_PROTOCOL.md) 中的 `SYSTEM_ONE_PILOT_ROUND=launch-dev-v1` 换为 `SYSTEM_ONE_PILOT_ROUND=launch-dev-v2`，并在提交前确认旧服务已退出。运行前仍应核对 OpenRouter 报价与模型可用性。
