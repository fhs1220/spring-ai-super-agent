<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import AiAvatar from '../components/AiAvatar.vue'
import AgentRlPanel from '../components/AgentRlPanel.vue'
import {
  cancelAgenticRag,
  fetchAgentRlMetrics,
  fetchDatasetReadiness,
  fetchRoutingPolicyStatus,
  generateChatId,
  streamAgenticRag,
  submitAgentRlFeedback,
  type AgentProgressEvent,
  type AgentTrace,
  type AgentRlMetrics,
  type DatasetReadiness,
  type RewardBreakdown,
  type RoutingPolicyStatus,
} from '../api'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  trajectoryId?: string
  reward?: RewardBreakdown
  trace?: AgentTrace
  rating?: number
  feedbackComment?: string
  feedbackState?: 'idle' | 'submitting' | 'submitted' | 'error'
  liveEvents?: AgentProgressEvent[]
  cancelled?: boolean
  error?: boolean
}

interface ActiveRun {
  runId: string
  controller: AbortController
  message: Message
}

const router = useRouter()
const chatId = ref('')
const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const chatList = ref<HTMLElement | null>(null)
const metrics = ref<AgentRlMetrics | null>(null)
const readiness = ref<DatasetReadiness | null>(null)
const routingPolicy = ref<RoutingPolicyStatus | null>(null)
const dashboardLoading = ref(false)
const dashboardError = ref('')
const activeRun = shallowRef<ActiveRun | null>(null)

onMounted(() => {
  chatId.value = generateChatId()
  loadDashboard()
})

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return

  input.value = ''
  messages.value.push({ id: crypto.randomUUID(), role: 'user', content: text })
  const assistant = reactive<Message>({
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    feedbackState: 'idle',
  })
  messages.value.push(assistant)
  loading.value = true
  const runId = crypto.randomUUID()
  const controller = new AbortController()
  assistant.liveEvents = []
  activeRun.value = { runId, controller, message: assistant }
  await scrollToBottom()

  try {
    const result = await streamAgenticRag(
      text,
      chatId.value,
      runId,
      (event) => {
        assistant.liveEvents?.push(event)
        void scrollToBottom()
      },
      controller.signal,
    )
    assistant.content = result.answer
    assistant.trajectoryId = result.trajectoryId
    assistant.reward = result.reward
    assistant.trace = result.trace
    await loadDashboard()
  } catch (error) {
    if (assistant.cancelled || isAbortError(error)) {
      assistant.content = '本次 Agent 运行已取消，后续模型调用已停止。'
      assistant.cancelled = true
    } else {
      assistant.content = `请求失败：${errorMessage(error)}`
      assistant.error = true
    }
  } finally {
    if (activeRun.value?.runId === runId) {
      activeRun.value = null
    }
    loading.value = false
    await scrollToBottom()
  }
}

async function cancelRun() {
  const current = activeRun.value
  if (!current || current.message.cancelled) return
  current.message.cancelled = true
  current.controller.abort()
  try {
    await cancelAgenticRag(current.runId)
  } catch {
    // 浏览器流已中止；后端还会通过 SSE 断连回调停止任务。
  }
}

async function submitFeedback(message: Message) {
  if (!message.trajectoryId || !message.rating || message.feedbackState === 'submitting') return
  message.feedbackState = 'submitting'
  try {
    const trajectory = await submitAgentRlFeedback(
      message.trajectoryId,
      message.rating,
      message.feedbackComment?.trim() ?? '',
    )
    message.reward = trajectory.reward
    message.feedbackState = 'submitted'
    await loadDashboard()
  } catch {
    message.feedbackState = 'error'
  }
}

async function loadDashboard() {
  dashboardLoading.value = true
  dashboardError.value = ''
  const [metricsResult, readinessResult, routingPolicyResult] = await Promise.allSettled([
    fetchAgentRlMetrics(),
    fetchDatasetReadiness(),
    fetchRoutingPolicyStatus(),
  ])

  if (metricsResult.status === 'fulfilled') {
    metrics.value = metricsResult.value
  }
  if (readinessResult.status === 'fulfilled') {
    readiness.value = readinessResult.value
  }
  if (routingPolicyResult.status === 'fulfilled') {
    routingPolicy.value = routingPolicyResult.value
  }
  if (
    metricsResult.status === 'rejected'
    || readinessResult.status === 'rejected'
    || routingPolicyResult.status === 'rejected'
  ) {
    dashboardError.value = '指标暂时不可用，请确认后端服务已启动'
  }
  dashboardLoading.value = false
}

function setRating(message: Message, rating: number) {
  if (message.feedbackState === 'submitted') return
  message.rating = rating
  if (message.feedbackState === 'error') {
    message.feedbackState = 'idle'
  }
}

function score(value: number | null | undefined): string {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

function formatDuration(durationMs: number): string {
  return durationMs < 1000 ? `${durationMs} ms` : `${(durationMs / 1000).toFixed(1)} s`
}

function formatInteger(value: number): string {
  return value.toLocaleString('zh-CN')
}

function formatCost(value: number): string {
  return `¥${value.toFixed(6)}`
}

function executionModeLabel(mode: AgentTrace['executionMode']): string {
  return mode === 'ADAPTIVE_MULTI_AGENT' ? 'MULTI AGENT' : 'SINGLE AGENT'
}

function latestProgress(message: Message): string {
  const latest = message.liveEvents?.at(-1)
  return latest ? `${latest.title}：${latest.summary}` : '正在启动 Agentic RAG'
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return 'Agentic RAG 服务暂时不可用'
}

async function scrollToBottom() {
  await nextTick()
  chatList.value?.scrollTo({ top: chatList.value.scrollHeight, behavior: 'smooth' })
}

function back() {
  router.push('/')
}
</script>

<template>
  <div class="agentic-page">
    <section class="chat-page">
      <header class="chat-header">
        <button type="button" class="back-btn" @click="back">← 返回</button>
        <div class="title-block">
          <h1 class="chat-title">AI 恋爱大师</h1>
          <span class="agent-badge">ADAPTIVE MULTI-AGENT</span>
        </div>
        <span class="chat-id">会话 {{ chatId.slice(0, 8) }}</span>
      </header>

      <div ref="chatList" class="chat-list" aria-live="polite">
        <div v-if="messages.length === 0" class="empty-state">
          <span class="empty-kicker">ROUTE · RETRIEVE · SPECIALISTS · SYNTHESIZE</span>
          <h2>简单问题快速回答，复杂问题多 Agent 协作</h2>
          <p>系统会动态选择单 Agent 或并行专业 Agent，并把每个贡献和奖励写入 Agent RL 轨迹。</p>
        </div>

        <template v-for="message in messages" :key="message.id">
          <div v-if="message.role === 'assistant'" class="row assistant-row">
            <AiAvatar type="love" />
            <div class="bubble assistant-bubble">
              <div
                class="bubble-content"
                :class="{ error: message.error, cancelled: message.cancelled }"
              >
                <template v-if="message.content">{{ message.content }}</template>
                <span v-else class="thinking">
                  {{ latestProgress(message) }}
                  <i></i><i></i><i></i>
                </span>
              </div>

              <section
                v-if="message.liveEvents?.length && !message.trace"
                class="live-trace"
                aria-label="实时 Agent 执行轨迹"
              >
                <header>
                  <span class="live-dot"></span>
                  <strong>LIVE AGENT TRACE</strong>
                  <span>{{ message.liveEvents.length }} EVENTS</span>
                </header>
                <ol>
                  <li
                    v-for="(event, index) in message.liveEvents"
                    :key="`${event.phase}-${event.title}-${index}`"
                    :class="event.status.toLowerCase()"
                  >
                    <span class="live-status"></span>
                    <div>
                      <strong>{{ event.phase }} · {{ event.title }}</strong>
                      <p>{{ event.summary }}</p>
                    </div>
                    <time>{{ formatDuration(event.elapsedMs) }}</time>
                  </li>
                </ol>
              </section>

              <div v-if="message.reward" class="reward-strip">
                <span class="reward-total">奖励 {{ score(message.reward.total) }}</span>
                <span title="检索质量">检索 {{ score(message.reward.retrievalQuality) }}</span>
                <span title="答案忠实度">忠实 {{ score(message.reward.groundingQuality) }}</span>
                <span title="知识库证据是否充分">证据 {{ score(message.reward.convergenceQuality) }}</span>
                <span title="最终回答是否完成用户任务">完成 {{ score(message.reward.taskCompletionQuality) }}</span>
                <span title="专业 Agent 的成功率和过程奖励">
                  协作 {{ score(message.reward.collaborationQuality) }}
                </span>
                <span title="执行效率">效率 {{ score(message.reward.efficiency) }}</span>
              </div>

              <details v-if="message.trace" class="trace-panel">
                <summary>
                  <span class="trace-summary-title">Agent Trace</span>
                  <span
                    class="trace-mode"
                    :class="{ multi: message.trace.executionMode === 'ADAPTIVE_MULTI_AGENT' }"
                  >
                    {{ executionModeLabel(message.trace.executionMode) }}
                  </span>
                  <span>{{ message.trace.steps.length }} 步</span>
                  <span>{{ message.trace.citations.length }} 条来源</span>
                  <span>{{ formatDuration(message.trace.totalDurationMs) }}</span>
                </summary>

                <div class="trace-body">
                  <section v-if="message.trace.telemetry" class="telemetry-section">
                    <div class="telemetry-heading">
                      <span>模型调用</span>
                      <span>{{ message.trace.telemetry.model }}</span>
                    </div>
                    <div class="telemetry-grid">
                      <div>
                        <span>CALLS</span>
                        <strong>{{ message.trace.telemetry.modelCallCount }}</strong>
                      </div>
                      <div>
                        <span>TOKENS</span>
                        <strong>{{ formatInteger(message.trace.telemetry.totalTokens) }}</strong>
                      </div>
                      <div>
                        <span>EST. COST</span>
                        <strong>{{ formatCost(message.trace.telemetry.estimatedCostCny) }}</strong>
                      </div>
                      <div :class="{ warning: message.trace.telemetry.timeoutCount > 0 }">
                        <span>TIMEOUTS</span>
                        <strong>{{ message.trace.telemetry.timeoutCount }}</strong>
                      </div>
                    </div>
                    <p class="telemetry-note">
                      输入 {{ formatInteger(message.trace.telemetry.promptTokens) }} /
                      输出 {{ formatInteger(message.trace.telemetry.completionTokens) }} Token ·
                      {{ message.trace.telemetry.usageEstimated ? '包含本地估算值' : '模型返回实际用量' }}
                    </p>
                    <div class="model-call-list">
                      <div
                        v-for="(call, index) in message.trace.telemetry.calls"
                        :key="`${call.stage}-${index}`"
                        class="model-call-row"
                        :class="{ failed: Boolean(call.error), timeout: call.timedOut }"
                      >
                        <span>{{ call.stage }}</span>
                        <span>{{ formatInteger(call.totalTokens) }} tok</span>
                        <span>{{ formatDuration(call.durationMs) }}</span>
                        <span>{{ call.usageEstimated ? '估算' : '实际' }}</span>
                      </div>
                    </div>
                  </section>

                  <ol class="trace-list">
                    <li
                      v-for="(step, index) in message.trace.steps"
                      :key="`${step.phase}-${index}`"
                      class="trace-step"
                      :class="{ failed: !step.success }"
                    >
                      <span class="trace-index">{{ String(index + 1).padStart(2, '0') }}</span>
                      <div class="trace-step-content">
                        <div class="trace-step-heading">
                          <span class="trace-phase">{{ step.phase }}</span>
                          <strong>{{ step.title }}</strong>
                          <span class="trace-duration">{{ formatDuration(step.durationMs) }}</span>
                        </div>
                        <p>{{ step.summary }}</p>
                        <ul v-if="step.details.length">
                          <li v-for="detail in step.details" :key="detail">{{ detail }}</li>
                        </ul>
                      </div>
                    </li>
                  </ol>

                  <section class="citation-section">
                    <div class="citation-heading">
                      <span>答案引用</span>
                      <span>{{ message.trace.citations.length }} CITATIONS</span>
                    </div>
                    <div v-if="message.trace.citations.length" class="citation-list">
                      <article
                        v-for="citation in message.trace.citations"
                        :key="citation.documentId"
                        class="citation-card"
                      >
                        <header>
                          <span>[来源 {{ citation.index }}]</span>
                          <strong>{{ citation.source }}</strong>
                        </header>
                        <p>{{ citation.excerpt }}</p>
                      </article>
                    </div>
                    <p v-else class="citation-empty">本次答案没有引用知识库片段。</p>
                  </section>
                </div>
              </details>

              <form
                v-if="message.trajectoryId && !message.error"
                class="feedback-card"
                @submit.prevent="submitFeedback(message)"
              >
                <div class="feedback-heading">
                  <span>这次回答有帮助吗？</span>
                  <span v-if="message.feedbackState === 'submitted'" class="feedback-success">
                    已加入训练反馈
                  </span>
                </div>
                <div class="feedback-row">
                  <div class="stars" aria-label="选择 1 到 5 星评分">
                    <button
                      v-for="rating in 5"
                      :key="rating"
                      type="button"
                      class="star-btn"
                      :class="{ active: (message.rating ?? 0) >= rating }"
                      :disabled="message.feedbackState === 'submitted'"
                      :aria-label="`${rating} 星`"
                      @click="setRating(message, rating)"
                    >
                      ★
                    </button>
                  </div>
                  <span class="trajectory-id">TRACE {{ message.trajectoryId.slice(0, 8) }}</span>
                </div>
                <div v-if="message.rating && message.feedbackState !== 'submitted'" class="comment-row">
                  <input
                    v-model="message.feedbackComment"
                    type="text"
                    maxlength="1000"
                    placeholder="可选：哪里好，哪里需要改进？"
                    aria-label="反馈说明"
                  />
                  <button type="submit" :disabled="message.feedbackState === 'submitting'">
                    {{ message.feedbackState === 'submitting' ? '提交中' : '提交' }}
                  </button>
                </div>
                <p v-if="message.feedbackState === 'error'" class="feedback-error">
                  提交失败，请稍后重试。
                </p>
              </form>
            </div>
          </div>

          <div v-else class="row user-row">
            <div class="bubble user-bubble">
              <div class="bubble-content">{{ message.content }}</div>
            </div>
          </div>
        </template>
      </div>

      <div class="chat-input-wrap">
        <input
          v-model="input"
          type="text"
          class="chat-input"
          placeholder="描述你的关系问题…"
          :disabled="loading"
          @keydown.enter.prevent="send()"
        />
        <button
          type="button"
          class="send-btn"
          :class="{ cancel: loading }"
          :disabled="!loading && !input.trim()"
          @click="loading ? cancelRun() : send()"
        >
          {{ loading ? '停止运行' : '发送' }}
        </button>
      </div>
    </section>

    <AgentRlPanel
      :metrics="metrics"
      :readiness="readiness"
      :routing-policy="routingPolicy"
      :loading="dashboardLoading"
      :error="dashboardError"
      @refresh="loadDashboard"
    />
  </div>
</template>

<style scoped>
.agentic-page {
  width: min(1120px, 100%);
  margin: 0 auto;
  padding: 20px;
  display: flex;
  align-items: flex-start;
  gap: 16px;
  flex: 1;
  min-height: 0;
}
.chat-page {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: calc(100vh - 104px);
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--header-h);
  padding: 8px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-elevated);
  flex-shrink: 0;
}
.back-btn {
  padding: 8px 10px;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  border-radius: var(--radius-sm);
  font: 0.8125rem var(--mono);
}
.back-btn:hover {
  color: var(--accent);
  border-color: var(--border);
}
.title-block {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
}
.chat-title {
  font: 600 0.9375rem/1 var(--mono);
  margin: 0;
  color: var(--text-heading);
  letter-spacing: 0.04em;
}
.agent-badge {
  padding: 4px 6px;
  border: 1px solid rgba(0, 212, 170, 0.28);
  border-radius: 999px;
  color: var(--accent);
  background: rgba(0, 212, 170, 0.06);
  font: 0.5625rem/1 var(--mono);
  letter-spacing: 0.08em;
}
.chat-id {
  font: 0.6875rem var(--mono);
  color: var(--text-muted);
}
.chat-list {
  flex: 1;
  min-height: 440px;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: var(--gap);
  scroll-behavior: smooth;
}
.empty-state {
  max-width: 480px;
  margin: auto;
  padding: 32px;
  text-align: center;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  background: rgba(10, 12, 16, 0.45);
}
.empty-kicker {
  color: var(--accent);
  font: 0.625rem var(--mono);
  letter-spacing: 0.08em;
}
.empty-state h2 {
  margin: 12px 0 8px;
  color: var(--text-heading);
  font-size: 1.125rem;
}
.empty-state p {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.8125rem;
}
.row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 100%;
}
.assistant-row {
  align-self: stretch;
}
.user-row {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.bubble {
  max-width: 86%;
  min-width: 0;
}
.assistant-bubble {
  flex: 1;
}
.bubble-content {
  padding: 12px 14px;
  border-radius: var(--radius);
  font-size: 0.9375rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  text-align: left;
}
.assistant-bubble .bubble-content {
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--text);
}
.assistant-bubble .bubble-content.error {
  border-color: rgba(248, 113, 113, 0.4);
  color: #fca5a5;
}
.assistant-bubble .bubble-content.cancelled {
  border-color: rgba(245, 185, 66, 0.38);
  color: #f5c96a;
}
.user-bubble .bubble-content {
  background: var(--accent);
  color: var(--bg);
}
.thinking {
  color: var(--text-muted);
}
.thinking i {
  display: inline-block;
  width: 4px;
  height: 4px;
  margin-left: 4px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.2s infinite ease-in-out;
}
.thinking i:nth-child(2) {
  animation-delay: 0.15s;
}
.thinking i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes pulse {
  0%, 80%, 100% { opacity: 0.25; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
.live-trace {
  margin-top: 8px;
  overflow: hidden;
  border: 1px solid rgba(0, 212, 170, 0.25);
  border-radius: var(--radius);
  background: rgba(0, 212, 170, 0.035);
}
.live-trace > header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 11px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
  font: 0.625rem var(--mono);
}
.live-trace > header strong {
  color: var(--accent);
  letter-spacing: 0.08em;
}
.live-trace > header span:last-child {
  margin-left: auto;
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent);
  animation: pulse 1.2s infinite ease-in-out;
}
.live-trace ol {
  max-height: 250px;
  margin: 0;
  padding: 6px 10px;
  overflow-y: auto;
  list-style: none;
}
.live-trace li {
  display: grid;
  grid-template-columns: 9px minmax(0, 1fr) auto;
  align-items: start;
  gap: 8px;
  padding: 7px 2px;
  color: var(--text-muted);
}
.live-trace li + li {
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.live-status {
  width: 7px;
  height: 7px;
  margin-top: 4px;
  border-radius: 50%;
  background: #64748b;
}
.live-trace li.started .live-status {
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent-glow);
}
.live-trace li.completed .live-status {
  background: #4ade80;
}
.live-trace li.failed .live-status,
.live-trace li.cancelled .live-status {
  background: #f5b942;
}
.live-trace li strong {
  display: block;
  color: var(--text-heading);
  font: 0.625rem var(--mono);
}
.live-trace li p {
  margin: 3px 0 0;
  font-size: 0.6875rem;
  line-height: 1.35;
}
.live-trace li time {
  font: 0.5625rem var(--mono);
  white-space: nowrap;
}
.reward-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 7px;
}
.reward-strip span {
  padding: 4px 7px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-muted);
  background: var(--surface-elevated);
  font: 0.625rem var(--mono);
}
.reward-strip .reward-total {
  color: var(--accent);
  border-color: rgba(0, 212, 170, 0.28);
}
.trace-panel {
  margin-top: 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: rgba(10, 12, 16, 0.68);
  overflow: hidden;
}
.trace-panel summary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  color: var(--text-muted);
  cursor: pointer;
  list-style: none;
  font: 0.625rem var(--mono);
  letter-spacing: 0.03em;
}
.trace-panel summary::-webkit-details-marker {
  display: none;
}
.trace-panel summary::after {
  content: '+';
  margin-left: auto;
  color: var(--accent);
  font-size: 0.875rem;
}
.trace-panel[open] summary::after {
  content: '−';
}
.trace-summary-title {
  color: var(--accent);
  font-weight: 600;
  letter-spacing: 0.08em;
}
.trace-mode {
  padding: 2px 5px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-muted);
}
.trace-mode.multi {
  color: #c4b5fd;
  border-color: rgba(167, 139, 250, 0.38);
  background: rgba(124, 58, 237, 0.08);
}
.trace-body {
  padding: 4px 12px 12px;
  border-top: 1px solid var(--border);
}
.telemetry-section {
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.telemetry-heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-heading);
  font-size: 0.75rem;
}
.telemetry-heading span:last-child {
  overflow: hidden;
  color: var(--text-muted);
  font: 0.5625rem var(--mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.telemetry-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin-top: 9px;
}
.telemetry-grid > div {
  display: grid;
  gap: 3px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
}
.telemetry-grid span {
  color: var(--text-muted);
  font: 0.5rem var(--mono);
}
.telemetry-grid strong {
  overflow: hidden;
  color: var(--accent);
  font: 0.6875rem var(--mono);
  text-overflow: ellipsis;
}
.telemetry-grid .warning strong {
  color: #fca5a5;
}
.telemetry-note {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 0.625rem;
}
.model-call-list {
  display: grid;
  gap: 4px;
  margin-top: 8px;
}
.model-call-row {
  display: grid;
  grid-template-columns: minmax(62px, 1fr) repeat(3, auto);
  gap: 8px;
  padding: 5px 7px;
  border-left: 2px solid rgba(0, 212, 170, 0.35);
  color: var(--text-muted);
  background: rgba(26, 30, 40, 0.45);
  font: 0.5625rem var(--mono);
}
.model-call-row span:first-child {
  color: var(--text-heading);
}
.model-call-row.failed,
.model-call-row.timeout {
  border-left-color: rgba(248, 113, 113, 0.7);
}
.trace-list {
  padding: 12px 0 4px;
  margin: 0;
  list-style: none;
}
.trace-step {
  position: relative;
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 10px;
  padding-bottom: 15px;
}
.trace-step:not(:last-child)::before {
  content: '';
  position: absolute;
  top: 23px;
  bottom: 0;
  left: 12px;
  width: 1px;
  background: var(--border);
}
.trace-index {
  position: relative;
  z-index: 1;
  width: 25px;
  height: 25px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(0, 212, 170, 0.35);
  border-radius: 50%;
  color: var(--accent);
  background: var(--bg);
  font: 0.5625rem var(--mono);
}
.trace-step.failed .trace-index {
  color: #fca5a5;
  border-color: rgba(248, 113, 113, 0.4);
}
.trace-step-content {
  min-width: 0;
}
.trace-step-heading {
  min-height: 25px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.trace-step-heading strong {
  color: var(--text-heading);
  font-size: 0.75rem;
}
.trace-phase,
.trace-duration {
  color: var(--text-muted);
  font: 0.5625rem var(--mono);
}
.trace-phase {
  color: var(--accent);
}
.trace-duration {
  margin-left: auto;
}
.trace-step-content > p {
  margin: 2px 0 0;
  color: var(--text);
  font-size: 0.75rem;
}
.trace-step-content ul {
  margin: 6px 0 0;
  padding-left: 16px;
  color: var(--text-muted);
  font-size: 0.6875rem;
  line-height: 1.5;
}
.citation-section {
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
.citation-heading {
  display: flex;
  justify-content: space-between;
  color: var(--text-heading);
  font-size: 0.75rem;
}
.citation-heading span:last-child {
  color: var(--text-muted);
  font: 0.5625rem var(--mono);
}
.citation-list {
  display: grid;
  gap: 7px;
  margin-top: 9px;
}
.citation-card {
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
}
.citation-card header {
  display: flex;
  gap: 7px;
  align-items: center;
  color: var(--accent);
  font: 0.625rem var(--mono);
}
.citation-card header strong {
  overflow: hidden;
  color: var(--text-heading);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.citation-card p,
.citation-empty {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 0.6875rem;
  line-height: 1.55;
}
.feedback-card {
  margin-top: 8px;
  padding: 11px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: rgba(26, 30, 40, 0.6);
}
.feedback-heading,
.feedback-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.feedback-heading {
  color: var(--text);
  font-size: 0.75rem;
}
.feedback-success {
  color: var(--accent);
  font: 0.625rem var(--mono);
}
.feedback-row {
  margin-top: 8px;
}
.stars {
  display: flex;
  gap: 2px;
}
.star-btn {
  padding: 2px;
  border: 0;
  color: #3d4552;
  background: transparent;
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
}
.star-btn.active {
  color: #f5b942;
}
.star-btn:disabled {
  cursor: default;
}
.trajectory-id {
  color: var(--text-muted);
  font: 0.5625rem var(--mono);
  letter-spacing: 0.06em;
}
.comment-row {
  display: flex;
  gap: 6px;
  margin-top: 9px;
}
.comment-row input {
  min-width: 0;
  flex: 1;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  outline: none;
  color: var(--text);
  background: var(--bg);
  font: 0.75rem var(--sans);
}
.comment-row input:focus {
  border-color: var(--accent-dim);
}
.comment-row button {
  padding: 0 12px;
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  color: var(--bg);
  background: var(--accent);
  cursor: pointer;
  font: 0.6875rem var(--mono);
}
.comment-row button:disabled {
  opacity: 0.55;
}
.feedback-error {
  margin: 7px 0 0;
  color: #fca5a5;
  font-size: 0.6875rem;
}
.chat-input-wrap {
  display: flex;
  gap: 8px;
  padding: 14px 16px;
  padding-bottom: max(14px, env(safe-area-inset-bottom));
  border-top: 1px solid var(--border);
  background: var(--surface-elevated);
  flex-shrink: 0;
}
.chat-input {
  flex: 1;
  min-width: 0;
  height: var(--input-h);
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  outline: none;
  color: var(--text);
  background: var(--bg);
  font: 0.9375rem var(--sans);
}
.chat-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-glow);
}
.chat-input::placeholder {
  color: var(--text-muted);
}
.send-btn {
  height: var(--input-h);
  min-width: 78px;
  padding: 0 18px;
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  color: var(--bg);
  background: var(--accent);
  cursor: pointer;
  font: 500 0.875rem var(--mono);
}
.send-btn:hover:not(:disabled) {
  box-shadow: 0 0 16px var(--accent-glow);
}
.send-btn.cancel {
  border-color: rgba(248, 113, 113, 0.55);
  color: #fee2e2;
  background: rgba(185, 28, 28, 0.78);
}
.send-btn.cancel:hover {
  box-shadow: 0 0 16px rgba(248, 113, 113, 0.22);
}
.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 980px) {
  .agentic-page {
    flex-direction: column;
  }
  .chat-page {
    width: 100%;
    min-height: 680px;
  }
  .chat-list {
    max-height: none;
  }
}

@media (max-width: 640px) {
  .agentic-page {
    padding: 0;
    gap: 12px;
  }
  .chat-page {
    min-height: calc(100vh - 64px);
    border-left: 0;
    border-right: 0;
    border-radius: 0;
  }
  .chat-id {
    display: none;
  }
  .agent-badge {
    display: none;
  }
  .chat-list {
    padding: 14px;
  }
  .bubble {
    max-width: 92%;
  }
  .feedback-heading,
  .feedback-row {
    align-items: flex-start;
  }
  .comment-row {
    flex-direction: column;
  }
  .comment-row button {
    height: 34px;
  }
  .telemetry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
