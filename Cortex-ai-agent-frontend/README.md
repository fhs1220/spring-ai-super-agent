# Cortex AI Agent 前端

Vue 3 + Vite 前端，包含主页与两个聊天应用。AI 恋爱大师使用 Agentic RAG，
支持自适应单/多 Agent 路由、并行专业 Agent Trace、协作奖励、用户评分、
每阶段 Token/成本/超时遥测和 Agent RL 数据就绪面板；
AI 超级智能体使用 SSE 实时对话。

## 环境

- Node 18+
- 开发服务器会把 `/api` 代理到 `http://localhost:8123`
- 如需连接其他后端，可设置 `VITE_API_BASE_URL`

## 安装与运行

```bash
npm install
npm run dev
```

浏览器访问：http://localhost:5173

## 构建

```bash
npm run build
```

产物在 `dist/`，可用 `npm run preview` 本地预览。

## 路由

| 路径 | 说明 |
|------|------|
| `/` | 主页，切换应用 |
| `/love` | AI 恋爱大师（Agentic RAG + Agent RL 反馈，自动生成 chatId） |
| `/manus` | AI 超级智能体（SSE：`/api/ai/manus/chat`） |

## 技术栈

- Vue 3、Vue Router 4、Axios
- Vite 8、TypeScript
- 全局样式变量见 `src/style.css`，可按需修改主题
