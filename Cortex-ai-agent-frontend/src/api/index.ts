import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api'

const http = axios.create({
  baseURL: API_BASE,
  headers: {
    'Content-Type': 'application/json',
  },
})

export interface RewardBreakdown {
  total: number
  retrievalQuality: number
  groundingQuality: number
  convergenceQuality: number
  taskCompletionQuality: number
  collaborationQuality: number
  efficiency: number
  userFeedback: number
}

export interface AgentTraceStep {
  phase:
    | 'ROUTE'
    | 'PLAN'
    | 'RETRIEVE'
    | 'VERIFY'
    | 'FOLLOW_UP'
    | 'SPECIALIST'
    | 'SYNTHESIZE'
    | 'GENERATE'
    | 'REVIEW'
    | 'REVISE'
  title: string
  summary: string
  durationMs: number
  success: boolean
  details: string[]
}

export interface RagCitation {
  index: number
  documentId: string
  source: string
  excerpt: string
}

export interface ModelCallMetric {
  stage: string
  durationMs: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  usageEstimated: boolean
  timedOut: boolean
  error: string
}

export interface AgentRunMetrics {
  model: string
  modelCallCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  usageEstimated: boolean
  estimatedCostCny: number
  timeoutCount: number
  calls: ModelCallMetric[]
}

export interface AgentTrace {
  totalDurationMs: number
  executionMode?: 'SINGLE_AGENT' | 'ADAPTIVE_MULTI_AGENT'
  steps: AgentTraceStep[]
  citations: RagCitation[]
  telemetry?: AgentRunMetrics
}

export interface AgenticRagResult {
  answer: string
  trajectoryId: string
  reward: RewardBreakdown
  trace: AgentTrace
}

export interface AgentTrajectory {
  trajectoryId: string
  reward: RewardBreakdown
  userRating: number | null
  feedbackComment: string | null
}

export interface AgentRlMetrics {
  trajectoryCount: number
  averageReward: number
  groundedRate: number
  averageLatencyMs: number
  averageUserRating: number
  multiAgentRate: number
  averageCollaborationQuality: number
  totalTokens: number
  estimatedCostCny: number
  timeoutCount: number
}

export interface DatasetReadiness {
  totalTrajectoryCount: number
  eligibleTrajectoryCount: number
  trainingCount: number
  validationCount: number
  expectedBatchSize: number
  minimumReward: number
  validationRatio: number
  requireHumanApproval: boolean
  readyForCloudSubmission: boolean
  warnings: string[]
}

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

export async function sendAgenticRag(message: string, chatId: string): Promise<AgenticRagResult> {
  const response = await http.post<AgenticRagResult>('/ai/love_app/chat/agentic-rag', {
    message,
    chatId,
  })
  return response.data
}

export async function submitAgentRlFeedback(
  trajectoryId: string,
  rating: number,
  comment: string,
): Promise<AgentTrajectory> {
  const response = await http.post<AgentTrajectory>('/ai/love_app/agent-rl/feedback', {
    trajectoryId,
    rating,
    comment,
  })
  return response.data
}

export async function fetchAgentRlMetrics(): Promise<AgentRlMetrics> {
  const response = await http.get<AgentRlMetrics>('/ai/love_app/agent-rl/metrics')
  return response.data
}

export async function fetchDatasetReadiness(): Promise<DatasetReadiness> {
  const response = await http.get<DatasetReadiness>('/ai/love_app/agent-rl/readiness')
  return response.data
}

export function generateChatId(): string {
  return crypto.randomUUID?.() ?? `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
