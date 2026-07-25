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

export type AgentProgressStatus =
  | 'STARTED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'RETRYING'
  | 'RECOVERY_REQUIRED'
  | 'SKIPPED'
  | 'TIMED_OUT'

export interface AgentProgressEvent {
  phase: string
  status: AgentProgressStatus
  title: string
  summary: string
  details: string[]
  elapsedMs: number
  timestamp: string
}

export interface CancelRunResponse {
  runId: string
  cancelled: boolean
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

export type RoutingPolicyMode = 'OFF' | 'SHADOW' | 'CANARY' | 'ACTIVE'

export interface RoutingPolicyDeployment {
  version: string
  policyVersion: string
  mode: RoutingPolicyMode
  canaryRate: number
  createdAt: string
  reason: string
}

export type RoutingPolicyArtifactStatus =
  | 'BASELINE'
  | 'VALIDATED'
  | 'REJECTED'
  | 'ARCHIVED'

export interface RoutingPolicyArtifactModeEvaluation {
  sampleCount: number
  successfulCount: number
  usageMeasuredSamples: number
  averageReward: number
  averageCostCny: number
  averageLatencyMs: number
  utility: number
}

export interface RoutingPolicyOfflineEvaluation {
  balancedEvidence: boolean
  observedTrajectoryCount: number
  minimumSamplesPerMode: number
  singleAgent: RoutingPolicyArtifactModeEvaluation
  multiAgent: RoutingPolicyArtifactModeEvaluation
  multiAgentUtilityLift: number
  recommendedMode: string
  validationPassed: boolean
  validationFailures: string[]
}

export interface RoutingPolicyDecisionRule {
  deployable: boolean
  recommendedMode: string
  confidence: number
  evidenceSamples: number
  utilityLift: number
  singleAgent: RoutingPolicyArtifactModeEvaluation
  multiAgent: RoutingPolicyArtifactModeEvaluation
  reason: string
}

export interface RoutingPolicyArtifact {
  schemaVersion?: number
  version: string
  status: RoutingPolicyArtifactStatus
  algorithm: string
  upstreamModel: string
  parameters: Record<string, string>
  trainingDataFingerprint: string
  trainingSampleCount: number
  offlineEvaluation: RoutingPolicyOfflineEvaluation
  globalRule?: RoutingPolicyDecisionRule
  contextualRules?: Record<string, RoutingPolicyDecisionRule>
  parentVersion: string
  createdAt: string
  validationReason: string
}

export interface RoutingPolicyRegistry {
  artifacts: RoutingPolicyArtifact[]
  updatedAt: string
}

export type OffPolicyEvaluationState =
  | 'NO_ARTIFACT'
  | 'COLLECTING'
  | 'READY'
  | 'REGRESSION'
  | 'ERROR'

export interface RoutingPolicyOffPolicyEvaluation {
  state: OffPolicyEvaluationState
  policyArtifactVersion: string
  observedTrajectoryCount: number
  probabilityLoggedTrajectoryCount: number
  exploratoryTrajectoryCount: number
  eligibleTrajectoryCount: number
  singleActionSamples: number
  multiActionSamples: number
  targetMatchedSamples: number
  minimumSamplesPerAction: number
  effectiveSampleSize: number
  minimumEffectiveSampleSize: number
  behaviorAverageReward: number
  estimatedPolicyReward: number
  estimatedRewardLift: number
  rewardLiftStandardError: number
  rewardLiftLowerConfidenceBound: number
  maximumRewardRegression: number
  healthyForPromotion: boolean
  blockers: string[]
  evaluatedAt: string
  reason: string
}

export type RoutingPolicyDriftState =
  | 'DISABLED'
  | 'INACTIVE'
  | 'COLLECTING'
  | 'HEALTHY'
  | 'DRIFTED'
  | 'ROLLED_BACK'
  | 'ERROR'

export interface RoutingPolicyDriftWindow {
  sampleCount: number
  completedCount: number
  usageMeasuredSamples: number
  averageReward: number
  groundingRate: number
  completionRate: number
  averageLatencyMs: number
  averageCostCny: number
  featureDistribution: Record<string, number>
}

export interface RoutingPolicyDriftReport {
  enabled: boolean
  state: RoutingPolicyDriftState
  deploymentVersion: string
  policyArtifactVersion: string
  rolloutMode: RoutingPolicyMode
  minimumReferenceSamples: number
  minimumActiveSamples: number
  reference: RoutingPolicyDriftWindow
  active: RoutingPolicyDriftWindow
  featureJsDivergence: number
  maximumFeatureJsDivergence: number
  consecutiveViolationCount: number
  consecutiveViolationsRequired: number
  maximumRewardRegression: number
  maximumGroundingRegression: number
  maximumCompletionRegression: number
  maximumLatencyMultiplier: number
  maximumCostMultiplier: number
  violations: string[]
  evaluatedAt: string
  reason: string
}

export interface RoutingModeStats {
  sampleCount: number
  successfulCount: number
  usageMeasuredSamples: number
  averageReward: number
  averageCostCny: number
  averageLatencyMs: number
  utility: number
}

export interface RoutingPolicyStatus {
  enabled: boolean
  ready: boolean
  deployment: RoutingPolicyDeployment
  minimumSamplesPerMode: number
  observedTrajectoryCount: number
  singleAgent: RoutingModeStats
  multiAgent: RoutingModeStats
  contextualBucketCount: number
  canarySelectedTrajectoryCount: number
  minimumCanarySamples: number
  refreshedAt: string
  reason: string
}

export type RoutingPolicyGuardState =
  | 'DISABLED'
  | 'INACTIVE'
  | 'COLLECTING'
  | 'HEALTHY'
  | 'ROLLED_BACK'
  | 'ERROR'

export interface RoutingPolicyCohortStats {
  sampleCount: number
  completedCount: number
  usageMeasuredSamples: number
  averageReward: number
  groundingRate: number
  completionRate: number
  averageLatencyMs: number
  averageCostCny: number
}

export interface RoutingPolicyQualityGuard {
  enabled: boolean
  state: RoutingPolicyGuardState
  deploymentVersion: string
  rolloutMode: RoutingPolicyMode
  minimumCanarySamples: number
  minimumControlSamples: number
  canary: RoutingPolicyCohortStats
  control: RoutingPolicyCohortStats
  maximumRewardRegression: number
  maximumGroundingRegression: number
  maximumCompletionRegression: number
  maximumLatencyMultiplier: number
  maximumCostMultiplier: number
  violations: string[]
  evaluatedAt: string
  reason: string
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

export async function streamAgenticRag(
  message: string,
  chatId: string,
  runId: string,
  onProgress: (event: AgentProgressEvent) => void,
  signal?: AbortSignal,
): Promise<AgenticRagResult> {
  const response = await fetch(`${API_BASE}/ai/love_app/chat/agentic-rag/stream`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ message, chatId, runId }),
    signal,
  })
  if (!response.ok) {
    throw new Error(`Agentic RAG 流式请求失败（HTTP ${response.status}）`)
  }
  if (!response.body) {
    throw new Error('浏览器未提供可读取的流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completedResult: AgenticRagResult | null = null

  const consumeFrame = (frame: string) => {
    const lines = frame.split('\n')
    let eventName = 'message'
    const dataLines: string[] = []
    for (const rawLine of lines) {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }
    if (!dataLines.length) return
    const data = JSON.parse(dataLines.join('\n')) as unknown
    if (eventName === 'progress') {
      onProgress(data as AgentProgressEvent)
    } else if (eventName === 'complete') {
      completedResult = data as AgenticRagResult
    } else if (eventName === 'cancelled') {
      throw new DOMException('运行已取消', 'AbortError')
    } else if (eventName === 'error') {
      const error = data as { message?: string }
      throw new Error(error.message || 'Agentic RAG 执行失败')
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replaceAll('\r\n', '\n')
    let frameEnd = buffer.indexOf('\n\n')
    while (frameEnd >= 0) {
      consumeFrame(buffer.slice(0, frameEnd))
      buffer = buffer.slice(frameEnd + 2)
      frameEnd = buffer.indexOf('\n\n')
    }
    if (done) break
  }
  if (buffer.trim()) {
    consumeFrame(buffer)
  }
  if (!completedResult) {
    throw new Error('Agentic RAG 流在返回最终结果前结束')
  }
  return completedResult
}

export async function cancelAgenticRag(runId: string): Promise<CancelRunResponse> {
  const response = await http.delete<CancelRunResponse>(
    `/ai/love_app/chat/agentic-rag/runs/${encodeURIComponent(runId)}`,
  )
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

export async function fetchRoutingPolicyStatus(): Promise<RoutingPolicyStatus> {
  const response = await http.get<RoutingPolicyStatus>('/ai/love_app/agents/routing-policy')
  return response.data
}

export async function fetchRoutingPolicyQualityGuard(): Promise<RoutingPolicyQualityGuard> {
  const response = await http.get<RoutingPolicyQualityGuard>(
    '/ai/love_app/agents/routing-policy/quality-guard',
  )
  return response.data
}

export async function fetchRoutingPolicyRegistry(): Promise<RoutingPolicyRegistry> {
  const response = await http.get<RoutingPolicyRegistry>(
    '/ai/love_app/agents/routing-policy/registry',
  )
  return response.data
}

export async function fetchRoutingPolicyOffPolicyEvaluation():
  Promise<RoutingPolicyOffPolicyEvaluation> {
  const response = await http.get<RoutingPolicyOffPolicyEvaluation>(
    '/ai/love_app/agents/routing-policy/off-policy-evaluation',
  )
  return response.data
}

export async function fetchRoutingPolicyDrift():
  Promise<RoutingPolicyDriftReport> {
  const response = await http.get<RoutingPolicyDriftReport>(
    '/ai/love_app/agents/routing-policy/drift',
  )
  return response.data
}

export function generateChatId(): string {
  return crypto.randomUUID?.() ?? `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
