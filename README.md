# Cortex AI Agent 智能体系统

一个基于 **Spring Boot + Spring AI + RAG + MCP + Vue 3** 构建的全栈 AI Agent 系统，实现多轮对话、知识库问答、工具调用与实时流式响应。

该项目展示了如何构建一个现代化 **LLM 驱动应用（AI Agent）**，支持知识检索增强（RAG）、工具调用（Tool Calling）以及实时 AI 对话。

---

# 项目架构

```
Vue 3 Frontend
      │
      │ HTTP / SSE
      ▼
Spring Boot Backend
      │
      ├── Chat Memory（对话记忆）
      │
      ├── RAG 知识库检索
      │       └── PGVector 向量数据库
      │
      ├── Tool Calling
      │       ├── Web Search
      │       ├── Web Scraping
      │       ├── File Operations
      │       └── Terminal Commands
      │
      └── MCP 工具调用
              └── 外部 MCP Server（如图片搜索）
```

---

# 项目功能

### 多轮 AI 对话与会话记忆
系统支持多轮对话能力，通过 ChatMemory 模块维护会话上下文，实现连续对话中的语义承接。

- 会话级 Chat Session 管理
- 对话上下文自动传递
- 内存存储与文件存储两种 ChatMemory 实现
- 支持复杂对话场景中的上下文理解

---

### RAG 知识库问答

系统实现完整的 **Retrieval Augmented Generation (RAG)** 流程，用于增强 AI 回答的准确性。

RAG Pipeline 包括：

- 文档加载（Document Loader）
- 文本切分（Text Splitter）
- 文本向量化（Embedding）
- 向量存储（PGVector Vector Store）
- Agentic 检索规划（Plan）
- 多查询语义检索与去重（Retrieve）
- 上下文充分性验证（Verify）
- 缺失信息补充检索，最多两轮（Follow-up）
- 答案忠实性审查与修正（Revise）
- 可展开的 Agent Trace（规划、检索、验证、补充检索、生成与修正耗时）
- 请求级模型遥测（每阶段实际/估算 Token、模型调用耗时、超时和人民币成本估算）
- 可配置的 30 秒模型阶段硬超时，避免底层重试或网络握手让请求长期挂起
- `[来源 n]` 答案引用与知识片段溯源
- 带知识库 SHA-256 指纹的本地向量索引缓存，避免每次启动重复生成 Embedding
- 动态 Top-K、向量/关键词混合召回与 RRF 本地重排

通过 RAG 能够在 AI 回复中引入外部知识，提高回答质量。

### 自适应多 Agent

`agentic-rag-v5` 使用“确定性 Complexity Router + 轨迹学习策略”判断任务是否值得启动多 Agent：

- 单一能力域的问题走 `SINGLE_AGENT` 快速路径，避免额外延迟和成本；
- 同时涉及关系、育儿、家务、家庭财务或安全风险的复合问题走
  `ADAPTIVE_MULTI_AGENT`；
- 最多并行调用 3 个专业 Agent，把结构化判断、建议、来源、置信度和不确定性写入
  Shared Evidence Blackboard；
- Synthesis Agent 基于统一知识库证据合并贡献，Review Agent 再检查忠实度和任务完成度；
- 单个专业 Agent 失败不会中断任务，全部失败或综合失败时自动降级到原单 Agent 生成路径。
- 专业 Agent 有独立总时间预算，只重试失败的 Agent，不重复执行已成功的并行分支；
- 连续失败达到阈值后开启内存熔断，在冷却期跳过故障 Agent，并继续使用其他贡献或单 Agent 降级。

当前内置能力：

- 关系沟通 Agent
- 育儿协作 Agent
- 家庭运营 Agent
- 家庭财务 Agent
- 关系安全 Agent

`GET /api/ai/love_app/agents` 会返回不包含提示词和密钥的能力契约，可在未来映射为
A2A Agent Card；`GET /api/ai/love_app/agents/health` 返回各专业 Agent 的熔断状态、
连续失败次数和预计恢复时间。相关开关：

- `AGENT_RAG_MULTI_AGENT_ENABLED`
- `AGENT_RAG_MULTI_AGENT_MINIMUM_DOMAINS`
- `AGENT_RAG_MULTI_AGENT_MAX_AGENTS`
- `AGENT_RAG_SPECIALIST_MAX_ATTEMPTS`
- `AGENT_RAG_SPECIALIST_TIMEOUT_SECONDS`
- `AGENT_RAG_CIRCUIT_BREAKER_FAILURE_THRESHOLD`
- `AGENT_RAG_CIRCUIT_BREAKER_COOLDOWN_SECONDS`

#### 轨迹学习路由

路由策略按任务能力域、结构化要求和问题长度生成 `featureBucket`，分别统计
`SINGLE_AGENT` 与 `ADAPTIVE_MULTI_AGENT` 的：

- 平均总奖励和成功样本数；
- 平均人民币估算成本；
- 平均端到端阶段耗时；
- 扣除成本和延迟惩罚后的净效用。

同一上下文和全局样本都不足时保持确定性路由。只有单/多 Agent 至少各有 8 条轨迹，且净效用
差达到 `0.03`，学习策略才会生成可发布候选。默认发布模式是 `SHADOW`，候选只写入轨迹做
对照分析，不会改变实际回答；明确晋升到 `CANARY` 或 `ACTIVE` 后才会逐步接管路由。
关系安全任务始终执行 `SAFETY_OVERRIDE`，不会因为成本数据被降级。执行策略、候选策略、
发布模式、是否命中灰度、置信度、证据样本数与特征桶都会写入 Agent Trace。

发布状态机：

- `OFF`：关闭学习策略，完全使用确定性路由；
- `SHADOW`：计算候选策略但不应用，适合安全积累线上对照数据；
- `CANARY`：按问题稳定哈希选择固定比例流量应用学习策略；
- `ACTIVE`：全部流量应用学习策略。

从 `SHADOW` 晋升 `CANARY` 要求单/多 Agent 样本都达到学习门槛；从 `CANARY` 晋升
`ACTIVE` 还要求至少 20 条实际灰度样本、在线质量守卫为 `HEALTHY`，并且离线策略评测通过。
每次发布都会原子保存到 `tmp/routing-policy/deployment.json`，并保留有界历史用于回滚。

灰度质量守卫每 30 秒按同一个发布版本比较灰度组与对照组的平均奖励、忠实度、完成率、
端到端延迟和估算成本。两组都达到门槛后，如果任一指标超过允许回退阈值，会自动创建一条
带指标原因的 `SHADOW` 发布记录；自动回滚因此可以跨重启审计，不会影响正在执行的回答请求。

策略注册中心每 60 秒检查一次学习状态。单/多 Agent 证据平衡后，它会生成不含用户问题和
答案的不可变策略资产，内容包括：

- 路由算法、上游模型和成本/延迟/效用超参数；
- 训练轨迹集合的 SHA-256 指纹和样本数；
- 全局规则与按 `featureBucket` 冻结的场景规则，以及各规则的置信度、证据数和效用提升；
- 单/多 Agent 离线奖励、成本、延迟、净效用及推荐模式；
- 离线门禁结论、验证失败原因、父策略版本和创建时间。

只有单/多 Agent 样本数和效用提升同时通过门禁的规则才会进入资产。线上推理按
“场景规则 → 全局规则 → 确定性路由”逐级回退。资产版本同时对算法、模型、参数、训练数据、
评测结论和全部冻结规则做内容寻址。同一资产不会重复注册；任何输入变化都会产生新版本。
发布记录通过 `policyVersion` 绑定资产。`CANARY/ACTIVE` 只执行注册表中已验证的冻结策略，
新轨迹不会让已发布策略在后台无版本漂移。旧版 v1 全局资产会自动迁移为兼容的全局规则。

灰度路由会在轨迹中记录实际动作的行为策略概率和探索资格。离线评测器只使用同一策略资产下
具有有效概率的探索轨迹，通过 SNIPS（自归一化逆倾向评分）估计冻结策略奖励，同时检查
SINGLE/MULTI 双动作支持度、重要性权重、有效样本量和奖励提升的 95% 置信下界。历史轨迹
没有行为概率时不会被误用于反事实评测；评测样本不足或置信下界显示奖励回退时，
`ACTIVE` 晋升会被拒绝。

查看不包含用户问题的策略状态：

```http
GET /api/ai/love_app/agents/routing-policy
GET /api/ai/love_app/agents/routing-policy/quality-guard
GET /api/ai/love_app/agents/routing-policy/registry
GET /api/ai/love_app/agents/routing-policy/off-policy-evaluation
```

主要配置：

- `AGENT_RAG_ROUTING_POLICY_ENABLED`
- `AGENT_RAG_ROUTING_POLICY_MODE`（默认 `SHADOW`）
- `AGENT_RAG_ROUTING_CANARY_RATE`（默认 `0.1`）
- `AGENT_RAG_ROUTING_MINIMUM_CANARY_SAMPLES`（默认 `20`）
- `AGENT_RAG_ROUTING_QUALITY_GUARD_ENABLED`（默认 `true`）
- `AGENT_RAG_ROUTING_GUARD_INTERVAL_MS`（默认 `30000`）
- `AGENT_RAG_ROUTING_GUARD_MINIMUM_CONTROL_SAMPLES`（默认 `20`）
- `AGENT_RAG_ROUTING_GUARD_MAXIMUM_REWARD_REGRESSION`（默认 `0.05`）
- `AGENT_RAG_ROUTING_GUARD_MAXIMUM_GROUNDING_REGRESSION`（默认 `0.05`）
- `AGENT_RAG_ROUTING_GUARD_MAXIMUM_COMPLETION_REGRESSION`（默认 `0.05`）
- `AGENT_RAG_ROUTING_GUARD_MAXIMUM_LATENCY_MULTIPLIER`（默认 `1.5`）
- `AGENT_RAG_ROUTING_GUARD_MAXIMUM_COST_MULTIPLIER`（默认 `1.5`）
- `AGENT_RAG_ROUTING_REGISTRY_STATE_FILE`
- `AGENT_RAG_ROUTING_REGISTRY_ALGORITHM`
- `AGENT_RAG_ROUTING_REGISTRY_MAXIMUM_ARTIFACTS`（默认 `50`）
- `AGENT_RAG_ROUTING_REGISTRY_RECONCILE_INTERVAL_MS`（默认 `60000`）
- `AGENT_RAG_ROUTING_OPE_MINIMUM_SAMPLES_PER_ACTION`（默认 `20`）
- `AGENT_RAG_ROUTING_OPE_MINIMUM_EFFECTIVE_SAMPLE_SIZE`（默认 `20`）
- `AGENT_RAG_ROUTING_OPE_MAXIMUM_IMPORTANCE_WEIGHT`（默认 `20`）
- `AGENT_RAG_ROUTING_OPE_MAXIMUM_REWARD_REGRESSION`（默认 `0.03`）
- `AGENT_RAG_ROUTING_MINIMUM_SAMPLES_PER_MODE`
- `AGENT_RAG_ROUTING_MINIMUM_UTILITY_LIFT`
- `AGENT_RAG_ROUTING_COST_WEIGHT`
- `AGENT_RAG_ROUTING_LATENCY_WEIGHT`
- `AGENT_RAG_ROUTING_REFRESH_SECONDS`

发布/回滚接口默认关闭。仅在受信任环境设置
`AGENT_RAG_ROUTING_MANAGEMENT_API_ENABLED=true` 后开放：

```http
GET  /api/agent-routing-policy/deployments
POST /api/agent-routing-policy/deployments
POST /api/agent-routing-policy/rollback
```

例如晋升到 10% 灰度：

```json
{
  "mode": "CANARY",
  "canaryRate": 0.1,
  "policyVersion": "routing-policy-替换为已验证版本",
  "reason": "balanced offline evidence passed"
}
```

`CANARY` 未提供 `policyVersion` 时会选择最新的已验证非基线资产；`ACTIVE` 必须沿用当前
灰度资产，禁止在正式晋升时偷换策略。紧急切换 `OFF/SHADOW` 和回滚不依赖注册表可用性。

### RAG A/B 自动化评测与回归门禁

项目内置版本化 JSONL 基准集
`src/main/resources/evaluation/love-rag-ab.jsonl`，当前包含 12 类简单、复合、格式约束、
拒绝无必要追问和关系安全样本。评测会比较：

- A：`TRADITIONAL_RAG`，原查询重写 + 单次知识库问答；
- B：`AGENTIC_RAG_V5`，当前学习型路由 Agentic RAG / 多 Agent 策略。

每条样本使用隔离的 `chatId`，并按样本序号交替 A/B 执行顺序。确定性评分覆盖任务要点、
直接回答、`[来源 n]` 引用、禁用表达、长度约束和 B 版本的路由准确率。报告包含胜/平/负、
平均质量、通过率、延迟、Token、成本、失败数和关键回归，并写入 `tmp/evaluation/<runId>.json`。
传统 RAG 尚无完整 Token 遥测，因此报告会以 `usageMeasuredCases=0` 明确标记，而不会把它
误解释为零成本。

评测会调用两套流程并产生模型费用，API 默认关闭。仅在本地受信任环境开启：

```bash
export AGENT_EVALUATION_API_ENABLED=true
```

建议先运行 2 条烟雾测试：

```http
POST /api/agent-evaluation/ab-runs
Content-Type: application/json

{"maximumCases":2}
```

接口立即返回异步 `runId`，随后查询或取消：

```http
GET /api/agent-evaluation/ab-runs/{runId}
DELETE /api/agent-evaluation/ab-runs/{runId}
GET /api/agent-evaluation/benchmark
```

默认回归门禁要求 B 平均质量至少 `0.72`、相对 A 回退不超过 `0.02`、路由准确率至少
`0.80`、没有单条关键回归且 B 的执行失败数不高于 A。门槛可通过以下环境变量调整：

- `AGENT_EVALUATION_CANDIDATE_MINIMUM_SCORE`
- `AGENT_EVALUATION_MAXIMUM_QUALITY_REGRESSION`
- `AGENT_EVALUATION_MINIMUM_ROUTE_ACCURACY`
- `AGENT_EVALUATION_MAXIMUM_CASES`

### Agent RL（第一阶段）

Agentic RAG 会把每次执行保存为可训练轨迹，包含规划、检索、验证、补充检索、生成和修正步骤，
同时计算检索质量、答案忠实度、知识库证据充分度、任务完成度、多 Agent 协作质量、效率和
用户反馈奖励。专业 Agent 还会记录独立过程奖励、置信度、Token、耗时和失败原因。轨迹默认保存在
`tmp/agent-rl/trajectories`，该目录不会提交到 Git。

调用带轨迹返回值的 Agentic RAG：

```http
POST /api/ai/love_app/chat/agentic-rag
Content-Type: application/json

{"message":"婚后经常因为家务吵架怎么办？","chatId":"demo-1"}
```

响应包含 `answer`、`trajectoryId`、`reward` 和 `trace.telemetry`。遥测优先使用模型返回的
实际 Token 用量；供应商未返回用量时会标记 `usageEstimated=true` 并使用本地估算。
默认成本单价和模型阶段硬超时都可通过环境变量调整：

- `AGENT_RAG_INPUT_PRICE_PER_MILLION_TOKENS_CNY`
- `AGENT_RAG_OUTPUT_PRICE_PER_MILLION_TOKENS_CNY`
- `AGENT_RAG_MODEL_CALL_TIMEOUT_SECONDS`

前端默认使用结构化 SSE 接口实时展示路由、规划、检索、验证、每个专业 Agent、综合和审查：

```http
POST /api/ai/love_app/chat/agentic-rag/stream
Accept: text/event-stream
Content-Type: application/json

{"message":"请制定一周家庭改善计划","chatId":"demo-1","runId":"client-run-001"}
```

事件类型包括 `accepted`、`progress`、`complete`、`cancelled` 和 `error`。运行中可用同一
`runId` 主动取消：

```http
DELETE /api/ai/love_app/chat/agentic-rag/runs/client-run-001
```

取消会中断承载虚拟线程与当前模型调用，并把轨迹标记为 `CANCELLED`；取消轨迹不会进入指标
聚合或 RL 数据集。原同步接口继续保留，便于服务端集成和回归测试。

### Durable Agent Runtime

SSE 多 Agent 运行会以原子 JSON 快照持久化到 `tmp/agent-runs`，保存请求、尝试次数、
最近阶段事件、终态和最终结果：

- 相同 `runId`、相同请求已经成功时，流接口直接回放进度和结果，不会重复调用模型；
- 运行失败或取消后不会自动重试，避免无意产生额外费用；
- 服务启动时会把上次遗留的 `QUEUED/RUNNING` 标记为 `RECOVERY_REQUIRED`；
- 只有显式调用 `resume` 才会使用保存的请求开始新 attempt；
- 单个快照默认最多保留 200 条进度事件，运行文件不会提交到 Git。

查询运行状态或显式恢复：

```http
GET /api/ai/love_app/chat/agentic-rag/runs/{runId}
POST /api/ai/love_app/chat/agentic-rag/runs/{runId}/resume
Accept: text/event-stream
```

`resume` 只接受 `FAILED`、`CANCELLED` 或 `RECOVERY_REQUIRED` 状态。运行存储位置和事件上限
可通过 `AGENT_RAG_RUNTIME_STORAGE_DIRECTORY`、`AGENT_RAG_RUNTIME_MAXIMUM_EVENTS` 调整。
快照包含用户问题和回答，生产环境应把这些接口放在认证和访问控制之后。

轨迹管理接口涉及用户问题和回答，默认关闭；
仅在受信任环境设置 `AGENT_RL_API_ENABLED=true` 后启用：

- `GET /api/agent-rl/trajectories/{trajectoryId}`：查看轨迹
- `POST /api/agent-rl/feedback`：提交 1～5 分用户反馈并重算奖励
- `GET /api/agent-rl/metrics`：查看平均奖励、忠实率、延迟和用户评分
- `GET /api/agent-rl/export?minimumReward=0.7`：导出 JSONL 训练数据

前端的评分闭环使用下列聚合/反馈接口，无需开放完整轨迹管理 API：

- `POST /api/ai/love_app/agent-rl/feedback`：提交某条轨迹的 1～5 星反馈
- `GET /api/ai/love_app/agent-rl/metrics`：读取不含用户内容的聚合指标
- `GET /api/ai/love_app/agent-rl/readiness`：读取百炼数据集就绪状态

### 阿里云百炼 Agentic RL（第二阶段）

项目已提供一套不依赖本机 NVIDIA GPU 的百炼云端训练链路：

1. Java 服务记录 Agentic RAG 轨迹与多维奖励；
2. 只选择高奖励且获得 4～5 星人工反馈的轨迹，生成百炼
   `messages + rollout_extra` 格式的训练集和验证集；
3. 百炼 Rollout 中执行“规划 → 远程检索 → 验证 → 补充检索 → 回答”；
4. Reward 函数综合参考答案质量、上下文忠实度、检索质量、证据充分度、任务完成度和效率；
5. 使用 Qwen 9B 在百炼云端执行 GSPO，Mac 只负责数据准备与任务提交。

云端训练代码位于 `bailian-agent-rl/`。提交脚本默认是 dry-run，只有同时传入
`--execute` 并设置 `BAILIAN_RL_ALLOW_BILLING=true` 才会创建付费任务。

#### 1. 收集人工反馈

先调用 Agentic RAG 获得 `trajectoryId`，再提交用户评分：

```http
POST /api/agent-rl/feedback
Content-Type: application/json

{
  "trajectoryId": "替换为实际 ID",
  "rating": 5,
  "comment": "回答准确且可执行"
}
```

#### 2. 导出百炼数据集

管理 API 默认关闭。仅在本地或受信任网络中设置
`AGENT_RL_API_ENABLED=true`，然后调用：

```http
POST /api/agent-rl/bailian/datasets
Content-Type: application/json

{}
```

返回值包含 `rl-train.jsonl`、`rl-validation.jsonl` 和 `manifest.json` 的路径。
`readyForCloudSubmission=false` 时不要提交训练；默认要求训练集数量严格大于
`batch_size=64`，并且验证集非空。若只是检查格式，可显式设置
`requireHumanApproval=false`，但这种自生成答案不应直接用于正式 RL。

#### 3. 部署只读检索环境

百炼 Rollout 在云端运行，无法访问 Mac 的 `localhost`。需要把当前 Spring Boot
服务部署到一个百炼可访问的 HTTPS 地址，并设置：

```bash
export AGENT_RL_ENVIRONMENT_API_ENABLED=true
export AGENT_RL_ENVIRONMENT_TOKEN='替换为独立的高强度随机令牌'
```

云端只调用：

```http
POST /api/agent-rl/environment/retrieve
X-Agent-RL-Token: <token>
```

该接口只返回文档 ID、来源和截断后的正文，不返回完整 metadata。生产环境还应配置
TLS、访问日志、限流和网络白名单。

#### 4. 本地预检

百炼 RL SDK 需要 Python 3.10 及以上。进入训练目录，创建独立环境：

```bash
cd bailian-agent-rl
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
pip download --no-deps dashscope==1.25.16 -d .
```

把数据集导出接口返回的实际路径替换到以下命令中：

```bash
python submit_job.py \
  --train ../tmp/agent-rl/bailian/<数据包>/rl-train.jsonl \
  --validation ../tmp/agent-rl/bailian/<数据包>/rl-validation.jsonl
```

预检只验证模型、配置、JSONL、样本数量和训练/验证集隔离，不会连接云端，也不会计费。

#### 5. 创建云端训练任务

先在百炼控制台完成 RL 服务授权，并准备 API Key。Rollout 需要访问已部署的检索服务：

```bash
export DASHSCOPE_API_KEY='替换为百炼 API Key'
export FC_PYPI_LIB='dashscope-1.25.16-py3-none-any.whl'
export AGENT_RL_RETRIEVAL_URL='https://你的服务域名'
export AGENT_RL_RETRIEVAL_TOKEN='与服务端相同的令牌'
export BAILIAN_RL_ALLOW_BILLING=true

python submit_job.py \
  --train ../tmp/agent-rl/bailian/<数据包>/rl-train.jsonl \
  --validation ../tmp/agent-rl/bailian/<数据包>/rl-validation.jsonl \
  --execute
```

训练任务会产生 MTU 和函数计算费用。默认配置使用 `qwen3.5-9b`、1 个 MTU4、
1 个 epoch，配置文件为 `bailian-agent-rl/config.example.json`。

查询任务状态或日志：

```bash
python job_status.py ft-xxxx
python job_status.py ft-xxxx --logs 100
```

---

### AI Agent 推理与工具调用

系统实现 Agent 推理能力，支持 LLM 根据任务自动选择并调用工具。

当前实现的 Agent 包括：

- BaseAgent
- ReActAgent
- ToolCallAgent

Agent 可以在推理过程中：

- 规划任务步骤
- 调用外部工具
- 结合工具结果继续推理

---

### Tool Calling 工具系统

系统内置多种可供 AI 调用的工具：

- WebSearchTool：联网搜索
- WebScrapingTool：网页内容抓取
- FileOperationTool：文件操作
- ResourceDownloadTool：资源下载
- PDFGenerationTool：PDF 文档生成
- TerminalOperationTool：终端命令执行

LLM 可通过 Tool Calling 自动触发工具执行。

---

### MCP 工具协议集成

系统支持 **Model Context Protocol (MCP)**，实现 AI Agent 与外部工具服务的解耦调用。

MCP 架构包括：

- MCP Client（客户端）
- MCP Server（工具服务）

当前项目实现：

- 图片搜索 MCP 服务

通过 MCP 可以扩展更多远程 AI 工具。

---

### SSE 实时流式 AI 回复

系统提供基于 **Server-Sent Events (SSE)** 的流式 AI 回复接口。

特点：

- 实时返回 AI 生成内容
- 降低响应延迟
- 提升聊天体验

---

# 技术栈

## 后端
- Java 21
- Spring Boot 3
- Spring AI
- PGVector
- Maven
- Docker

## AI 能力
- RAG (Retrieval Augmented Generation)
- Tool Calling
- MCP (Model Context Protocol)
- 多模型支持

## 前端
- React
- TypeScript
- Vite

---

# 项目结构

```
spring-ai-super-agent
│
├── src/                          # Spring Boot 后端
│
├── Cortex-ai-agent-frontend/     # React 前端
│
├── fhs-image-search-mcp-server/  # MCP 工具服务
│
├── Dockerfile                    # Docker 部署
│
└── pom.xml
```

---

# 运行后端

```bash
mvn clean package
java -jar target/fhs-ai-agent-0.0.1-SNAPSHOT.jar
```

服务启动：

```
http://localhost:8123
```

---

# 运行前端

```bash
cd Cortex-ai-agent-frontend
npm install
npm run dev
```

访问：

```
http://localhost:5173
```

---

# Docker 部署

构建镜像：

```bash
docker build -t cortex-ai-agent .
```

运行容器：

```bash
docker run -p 8123:8123 cortex-ai-agent
```

---

# 示例 API

流式 AI 对话接口：

```
GET /ai/love_app/chat/sse?message=hello&chatId=1
```

返回 **SSE 流式 AI 回复**。

---

# 作者

Haosen Fang  
Master of Computer Science  
University of Illinois Urbana-Champaign
