<script setup lang="ts">
import { computed } from 'vue'
import type { AgentRlMetrics, DatasetReadiness } from '../api'

const props = defineProps<{
  metrics: AgentRlMetrics | null
  readiness: DatasetReadiness | null
  loading: boolean
  error: string
}>()

defineEmits<{
  refresh: []
}>()

const eligibleTarget = computed(() => {
  if (!props.readiness) return 82
  const trainingTarget = props.readiness.expectedBatchSize + 1
  return Math.ceil(trainingTarget / (1 - props.readiness.validationRatio))
})

const progress = computed(() => {
  if (!props.readiness) return 0
  return Math.min(100, Math.round((props.readiness.eligibleTrajectoryCount / eligibleTarget.value) * 100))
})

function score(value: number | undefined): string {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

function percent(value: number | undefined): string {
  return typeof value === 'number' ? `${Math.round(value * 100)}%` : '—'
}

function latency(value: number | undefined): string {
  if (typeof value !== 'number') return '—'
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${Math.round(value)}ms`
}
</script>

<template>
  <aside class="rl-panel" aria-label="Agent RL 训练面板">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">LEARNING LOOP</p>
        <h2>Agent RL</h2>
      </div>
      <button
        type="button"
        class="refresh-btn"
        :disabled="loading"
        aria-label="刷新 Agent RL 指标"
        @click="$emit('refresh')"
      >
        ↻
      </button>
    </div>

    <div class="status-line">
      <span class="status-dot" :class="{ ready: readiness?.readyForCloudSubmission }"></span>
      <span>{{ readiness?.readyForCloudSubmission ? '数据可提交' : '正在积累训练数据' }}</span>
    </div>

    <div v-if="error" class="panel-error">{{ error }}</div>

    <div class="metric-grid">
      <div class="metric-card">
        <span class="metric-label">轨迹</span>
        <strong>{{ metrics?.trajectoryCount ?? 0 }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">平均奖励</span>
        <strong>{{ score(metrics?.averageReward) }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">忠实率</span>
        <strong>{{ percent(metrics?.groundedRate) }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">平均耗时</span>
        <strong>{{ latency(metrics?.averageLatencyMs) }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">多 Agent 占比</span>
        <strong>{{ percent(metrics?.multiAgentRate) }}</strong>
      </div>
      <div class="metric-card">
        <span class="metric-label">平均协作奖励</span>
        <strong>{{ score(metrics?.averageCollaborationQuality) }}</strong>
      </div>
    </div>

    <div class="usage-line">
      <span>{{ (metrics?.totalTokens ?? 0).toLocaleString('zh-CN') }} TOKENS</span>
      <span>¥{{ (metrics?.estimatedCostCny ?? 0).toFixed(6) }}</span>
      <span>{{ metrics?.timeoutCount ?? 0 }} TIMEOUTS</span>
    </div>

    <section class="readiness-card">
      <div class="readiness-title">
        <span>人工审核样本</span>
        <span>{{ readiness?.eligibleTrajectoryCount ?? 0 }} / {{ eligibleTarget }}</span>
      </div>
      <div
        class="progress-track"
        role="progressbar"
        :aria-valuenow="progress"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <span class="progress-value" :style="{ width: `${progress}%` }"></span>
      </div>
      <div class="split-row">
        <span>训练 {{ readiness?.trainingCount ?? 0 }}</span>
        <span>验证 {{ readiness?.validationCount ?? 0 }}</span>
        <span>评分 {{ score(metrics?.averageUserRating) }}</span>
      </div>
    </section>

    <div class="pipeline">
      <span>ROUTE</span>
      <i>→</i>
      <span>RETRIEVE</span>
      <i>→</i>
      <span>AGENTS</span>
      <i>→</i>
      <span>JUDGE</span>
    </div>

    <p class="panel-note">
      每次评分都会更新轨迹奖励。达到质量门槛后，再导出到百炼执行云端 GSPO。
    </p>
  </aside>
</template>

<style scoped>
.rl-panel {
  width: 300px;
  flex-shrink: 0;
  padding: 20px;
  background: #0f1218;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  align-self: flex-start;
  position: sticky;
  top: 20px;
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
}
.eyebrow {
  margin: 0 0 3px;
  color: var(--accent);
  font: 0.625rem/1.2 var(--mono);
  letter-spacing: 0.12em;
}
h2 {
  margin: 0;
  color: var(--text-heading);
  font: 600 1rem/1.2 var(--mono);
}
.refresh-btn {
  width: 32px;
  height: 32px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: var(--surface);
  cursor: pointer;
  font-size: 1rem;
}
.refresh-btn:hover:not(:disabled) {
  color: var(--accent);
  border-color: var(--accent-dim);
}
.refresh-btn:disabled {
  opacity: 0.5;
}
.status-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0;
  color: var(--text);
  font-size: 0.8125rem;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f5a524;
  box-shadow: 0 0 10px rgba(245, 165, 36, 0.35);
}
.status-dot.ready {
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent-glow);
}
.panel-error {
  margin-bottom: 12px;
  padding: 9px 10px;
  border: 1px solid rgba(248, 113, 113, 0.35);
  border-radius: var(--radius-sm);
  color: #fca5a5;
  background: rgba(127, 29, 29, 0.16);
  font-size: 0.75rem;
}
.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.metric-card {
  min-height: 68px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
}
.metric-card strong {
  display: block;
  margin-top: 6px;
  color: var(--text-heading);
  font: 600 1.05rem/1 var(--mono);
}
.metric-label {
  color: var(--text-muted);
  font-size: 0.6875rem;
}
.readiness-card {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
}
.usage-line {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  margin-top: 9px;
  color: var(--text-muted);
  font: 0.5rem/1.2 var(--mono);
}
.readiness-title,
.split-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--text-muted);
  font-size: 0.6875rem;
}
.readiness-title span:last-child {
  color: var(--text-heading);
  font-family: var(--mono);
}
.progress-track {
  height: 6px;
  margin: 10px 0;
  overflow: hidden;
  border-radius: 999px;
  background: var(--bg);
}
.progress-value {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent-glow);
  transition: width 0.3s ease;
}
.pipeline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  color: var(--accent);
  font: 0.55rem/1 var(--mono);
}
.pipeline i {
  color: var(--text-muted);
  font-style: normal;
}
.panel-note {
  margin: 14px 0 0;
  color: var(--text-muted);
  font-size: 0.7rem;
  line-height: 1.55;
}

@media (max-width: 980px) {
  .rl-panel {
    width: 100%;
    position: static;
  }
}
</style>
