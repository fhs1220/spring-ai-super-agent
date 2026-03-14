<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AiAvatar from '../components/AiAvatar.vue'
import { generateChatId, createLoveAppEventSource } from '../api'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
}

const router = useRouter()
const chatId = ref('')
const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)

onMounted(() => {
  chatId.value = generateChatId()
})

function send() {
  const text = input.value.trim()
  if (!text || loading.value) return
  input.value = ''
  messages.value.push({ id: crypto.randomUUID(), role: 'user', content: text })
  const assistantId = crypto.randomUUID()
  messages.value.push({ id: assistantId, role: 'assistant', content: '' })
  loading.value = true

  const es = createLoveAppEventSource(text, chatId.value)
  const assistant = messages.value.find((m) => m.id === assistantId)!

  es.onmessage = (e) => {
    assistant.content += e.data ?? ''
  }
  es.onerror = () => {
    es.close()
    loading.value = false
  }
}

function back() {
  router.push('/')
}
</script>

<template>
  <div class="chat-page">
    <header class="chat-header">
      <button type="button" class="back-btn" @click="back">← 返回</button>
      <h1 class="chat-title">AI 恋爱大师</h1>
      <span class="chat-id">会话 {{ chatId.slice(0, 8) }}</span>
    </header>
    <div class="chat-list">
      <template v-for="msg in messages" :key="msg.id">
        <div v-if="msg.role === 'assistant'" class="row assistant-row">
          <AiAvatar type="love" />
          <div class="bubble assistant-bubble">
            <div class="bubble-content">{{ msg.content || '…' }}</div>
          </div>
        </div>
        <div v-else class="row user-row">
          <div class="bubble user-bubble">
            <div class="bubble-content">{{ msg.content }}</div>
          </div>
        </div>
      </template>
    </div>
    <div class="chat-input-wrap">
      <input
        v-model="input"
        type="text"
        class="chat-input"
        placeholder="输入消息…"
        :disabled="loading"
        @keydown.enter.prevent="send()"
      />
      <button type="button" class="send-btn" :disabled="loading || !input.trim()" @click="send">
        发送
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 720px;
  margin: 0 auto;
  background: var(--surface);
  border-left: 1px solid var(--border);
  border-right: 1px solid var(--border);
  flex-shrink: 0;
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  height: var(--header-h);
  padding: 0 20px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-elevated);
  flex-shrink: 0;
}
.back-btn {
  padding: 8px 12px;
  font-size: 0.875rem;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  border-radius: var(--radius-sm);
  font-family: var(--mono);
}
.back-btn:hover {
  color: var(--accent);
  border-color: var(--border);
}
.chat-title {
  flex: 1;
  font-size: 0.9375rem;
  font-weight: 600;
  margin: 0;
  color: var(--text-heading);
  font-family: var(--mono);
  letter-spacing: 0.04em;
}
.chat-id {
  font-size: 0.75rem;
  color: var(--text-muted);
  font-family: var(--mono);
}
.chat-list {
  flex: 1;
  min-height: 420px;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: var(--gap);
}
.row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 100%;
}
.assistant-row {
  align-self: flex-start;
}
.user-row {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.bubble {
  max-width: 85%;
  min-width: 0;
}
.assistant-bubble .bubble-content {
  padding: 12px 14px;
  border-radius: var(--radius);
  font-size: 0.9375rem;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  text-align: left;
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--text);
}
.user-bubble .bubble-content {
  padding: 12px 14px;
  border-radius: var(--radius);
  font-size: 0.9375rem;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  text-align: left;
  background: var(--accent);
  color: var(--bg);
}
.chat-input-wrap {
  display: flex;
  gap: 8px;
  padding: 16px 20px;
  padding-bottom: max(16px, env(safe-area-inset-bottom));
  border-top: 1px solid var(--border);
  background: var(--surface-elevated);
  flex-shrink: 0;
}
.chat-input {
  flex: 1;
  height: var(--input-h);
  padding: 0 14px;
  font-size: 0.9375rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg);
  color: var(--text);
  outline: none;
  font-family: var(--sans);
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
  padding: 0 20px;
  font-size: 0.9375rem;
  font-weight: 500;
  color: var(--bg);
  background: var(--accent);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-family: var(--mono);
}
.send-btn:hover:not(:disabled) {
  box-shadow: 0 0 16px var(--accent-glow);
}
.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .chat-page {
    width: 100%;
    max-width: none;
    border-left: none;
    border-right: none;
  }
  .chat-id {
    display: none;
  }
}
</style>
