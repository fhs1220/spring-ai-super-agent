# Cortex AI Agent 智能体系统

一个基于 **Spring Boot + Spring AI + RAG + MCP + React** 构建的全栈 AI Agent 系统，实现多轮对话、知识库问答、工具调用与实时流式响应。

该项目展示了如何构建一个现代化 **LLM 驱动应用（AI Agent）**，支持知识检索增强（RAG）、工具调用（Tool Calling）以及实时 AI 对话。

---

# 项目架构

```
React Frontend
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
- 语义检索（Retriever）
- 检索结果增强生成（Answer Generation）

通过 RAG 能够在 AI 回复中引入外部知识，提高回答质量。

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
