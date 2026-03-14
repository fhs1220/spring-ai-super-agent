const API_BASE = 'http://localhost:8123/api'

export function loveAppSseUrl(message: string, chatId: string): string {
  const params = new URLSearchParams({ message, chatId })
  return `${API_BASE}/ai/love_app/chat/sse?${params.toString()}`
}

export function manusSseUrl(message: string): string {
  const params = new URLSearchParams({ message })
  return `${API_BASE}/ai/manus/chat?${params.toString()}`
}

export function createLoveAppEventSource(message: string, chatId: string): EventSource {
  return new EventSource(loveAppSseUrl(message, chatId))
}

export function createManusEventSource(message: string): EventSource {
  return new EventSource(manusSseUrl(message))
}

export function generateChatId(): string {
  return crypto.randomUUID?.() ?? `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
