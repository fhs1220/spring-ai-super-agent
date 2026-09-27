# System One 在线接管预备实现

截至 2026-09-27，本实现已加入代码，但**没有启用 Jev 或 Laya 在线路由**。默认 `AGENT_SYSTEM_ONE_DEPLOYMENT_MODE=OFF`。旧的 `AGENT_SYSTEM_ONE_MODE=SHADOW` 仍只控制单路顾问调用；轨迹策略的 CANARY/ACTIVE 是另一套开关。

## 接管契约

- `OFF` / `SHADOW`：保持原路由，System One 建议只作观测。
- `CANARY`：按 `release_version + 原始问题` 的 SHA-256 稳定分桶，`canary-percent` 只能为 0–99，保留同时段对照组。未命中的请求不做同步候选调用。
- `ACTIVE`：所有自适应请求进入候选判断。`FORCE_SINGLE` / `FORCE_MULTI` 始终绕过候选。
- 若识别到安全风险、候选调用失败、响应模型版本不符，或 Multi 建议不足两个概率 ≥0.5 的非安全专家领域，保留原路由。候选仅决定 Single/Multi 和可执行专家组合；答案安全审查仍是独立环节。
- 每次决策在路由 trace 记录 `systemOneDeploymentMode`、`systemOneReleaseVersion`、`systemOneCanarySelected`、`systemOneApplied`、`systemOneApplicationStatus`。这些字段不能和轨迹策略的 `policyApplied` 混用。
- CANARY/ACTIVE 还会把分母、入组数、失败数、连续失败数及安全回退数持久化到 `storage-directory`，并逐次追加不含问题原文的 JSONL 账本。不同进程通过文件锁协调；写入处于未完成状态、状态文件损坏或存储不可用时会停止候选接管。

## 启用前的门禁文件

`CANARY` / `ACTIVE` 需要显式设置 `AGENT_SYSTEM_ONE_ENABLED=true`、`AGENT_SYSTEM_ONE_MODE=SHADOW`、候选 provider/model/阈值，以及 `AGENT_SYSTEM_ONE_DEPLOYMENT_RELEASE_VERSION`、`AGENT_SYSTEM_ONE_DEPLOYMENT_EXPECTED_MODEL`、`AGENT_SYSTEM_ONE_DEPLOYMENT_MANIFEST_PATH`。双路异步 Shadow 不能同时启用，Multi-Agent 总开关也必须打开。配置冲突或门禁文件缺失时应用拒绝启动。

门禁清单与报告须位于同一目录，示意结构（**不是可用的上线批准**）：

```json
{
  "release_version": "candidate-2026-xx",
  "model": "OPENROUTER_JEV:<frozen-checkpoint>",
  "multi_agent_threshold": 0.20,
  "dataset_sha256": "<64位十六进制>",
  "report_path": "independent-report.json",
  "report_sha256": "<64位十六进制>",
  "independent_holdout_approved": true,
  "approved_by": "<人审负责人>"
}
```

启动时会核对版本、模型、阈值、报告哈希、`releaseGatePassed=true`、空 `gateFailures` 和至少 30 个样本。清单中的“独立测试集已批准”是人工签署声明，不是程序能自行证明的事实；运营者仍需核查原始抽样、盲评和成本证据。`jev-latest` 一类别名也不能证明供应商 checkpoint 不变，真正上线前应取得可固定的模型版本。

灰度账本配置默认指向 `tmp/system-one-rollout`，但 CANARY/ACTIVE **必须**用 `AGENT_SYSTEM_ONE_DEPLOYMENT_STORAGE_DIRECTORY` 指定绝对路径，并保证该目录由所有实例共享且持久化；否则启动会被拒绝。默认在同一版本连续 3 次候选失败，或至少 20 次入组后失败率超过 10% 时自动暂停；环境变量 `AGENT_SYSTEM_ONE_DEPLOYMENT_MAX_CONSECUTIVE_FAILURES`、`AGENT_SYSTEM_ONE_DEPLOYMENT_MINIMUM_SELECTED_FOR_RATE`、`AGENT_SYSTEM_ONE_DEPLOYMENT_MAX_FAILURE_RATE` 可在批准前预注册阈值。失败包括决策调用/模型不匹配和不可执行的专家组合；安全回退另行计数，不作为 API 故障。暂停状态会跨重启保持，后续请求使用原路由。不要通过删除账本来重启同一候选；应先调查事件、产生新的审批和版本。该机制仅是**决策可用性守卫**，不是答案质量或安全性监测。

## 尚未满足的上线条件

当前没有经过独立真实场景测试和人审批准的报告，因此不能生成有效门禁清单。新代码覆盖接管机制、持久化决策账本和因候选不可用而自动暂停，**不包括**答案质量/安全在线监测、灰度演练或 ACTIVE 晋升审批。手动回滚是将 `AGENT_SYSTEM_ONE_DEPLOYMENT_MODE` 改回 `OFF` 并重启服务。正式灰度前还需定义答案质量与安全风险的独立停止规则、监控告警和人工值守，不能将可用性自动暂停等同于完整发布保障。
