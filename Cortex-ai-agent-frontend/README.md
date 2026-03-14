# Cortex AI Agent 前端

Vue 3 + Vite 前端，包含主页与两个聊天应用（AI 恋爱大师、AI 超级智能体），通过 SSE 与后端实时对话。

## 环境

- Node 18+
- 后端接口前缀：`http://localhost:8123/api`

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
| `/love` | AI 恋爱大师（SSE：`/api/ai/love_app/chat/sse`，自动生成 chatId） |
| `/manus` | AI 超级智能体（SSE：`/api/ai/manus/chat`） |

## 技术栈

- Vue 3、Vue Router 4、Axios
- Vite 8、TypeScript
- 全局样式变量见 `src/style.css`，可按需修改主题
