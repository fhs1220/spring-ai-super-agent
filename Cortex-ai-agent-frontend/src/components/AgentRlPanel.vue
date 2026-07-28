<script setup lang="ts">
import { computed } from 'vue'
import type {
  AgentRlMetrics,
  AlignmentAutomationStatus,
  DatasetReadiness,
  RoutingPolicyQualityGuard,
  RoutingPolicyRegistry,
  RoutingPolicyOffPolicyEvaluation,
  RoutingPolicyDriftReport,
  RoutingPolicyProgressiveDelivery,
  RoutingPolicyProgressiveDeliveryAutomation,
  RoutingPolicyStatus,
} from '../api'

const props = defineProps<{
  metrics: AgentRlMetrics | null
  readiness: DatasetReadiness | null
  alignmentAutomation: AlignmentAutomationStatus | null
  routingPolicy: RoutingPolicyStatus | null
  routingPolicyQualityGuard: RoutingPolicyQualityGuard | null
  routingPolicyRegistry: RoutingPolicyRegistry | null
  routingPolicyOffPolicyEvaluation: RoutingPolicyOffPolicyEvaluation | null
  routingPolicyDrift: RoutingPolicyDriftReport | null
  routingPolicyProgressiveDelivery: RoutingPolicyProgressiveDelivery | null
  routingPolicyProgressiveDeliveryAutomation:
    RoutingPolicyProgressiveDeliveryAutomation | null
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

const alignmentAutomationLabel = computed(() => {
  const labels = {
    RUNNING: '评分中',
    PAUSED: '已暂停',
    COOLDOWN: '故障冷却',
    DAILY_LIMIT: '今日额度用完',
    IDLE: '暂无待评分',
    MANUAL_ONLY: '仅手动',
    READY: '自动评分就绪',
  }
  return props.alignmentAutomation
    ? labels[props.alignmentAutomation.mode]
    : '加载中'
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

const rolloutLabel = computed(() => {
  const labels = {
    OFF: '规则路由',
    SHADOW: '影子评估',
    CANARY: '灰度发布',
    ACTIVE: '正式生效',
  }
  return props.routingPolicy ? labels[props.routingPolicy.deployment.mode] : '加载中'
})

const rolloutDescription = computed(() => {
  const mode = props.routingPolicy?.deployment.mode
  if (mode === 'SHADOW') return '学习策略只做对照，不影响回答'
  if (mode === 'CANARY') return '仅对稳定抽样流量应用学习策略'
  if (mode === 'ACTIVE') return '学习策略已参与全部路由决策'
  if (mode === 'OFF') return '学习策略已关闭，使用确定性规则'
  return '正在读取策略发布状态'
})

const guardLabel = computed(() => {
  const labels = {
    DISABLED: '守卫关闭',
    INACTIVE: '守卫待命',
    COLLECTING: '采样中',
    HEALTHY: '质量健康',
    ROLLED_BACK: '已自动回滚',
    ERROR: '守卫异常',
  }
  return props.routingPolicyQualityGuard
    ? labels[props.routingPolicyQualityGuard.state]
    : '加载中'
})

const offPolicyLabel = computed(() => {
  const labels = {
    NO_ARTIFACT: '等待策略资产',
    COLLECTING: '积累探索数据',
    READY: '评测通过',
    REGRESSION: '发现奖励回退',
    ERROR: '评测异常',
  }
  return props.routingPolicyOffPolicyEvaluation
    ? labels[props.routingPolicyOffPolicyEvaluation.state]
    : '加载中'
})

const driftLabel = computed(() => {
  const labels = {
    DISABLED: '监控关闭',
    INACTIVE: '仅 ACTIVE 启用',
    COLLECTING: '积累监控窗口',
    HEALTHY: '运行稳定',
    DRIFTED: '检测到漂移',
    ROLLED_BACK: '已自动回退',
    ERROR: '监控异常',
  }
  return props.routingPolicyDrift
    ? labels[props.routingPolicyDrift.state]
    : '加载中'
})

const progressiveLabel = computed(() => {
  const labels = {
    DISABLED: '发布顾问关闭',
    WAITING_FOR_ARTIFACT: '等待候选资产',
    COOLDOWN: '阶段冷却中',
    COLLECTING: '阶段采样中',
    READY: '可以晋级',
    HOLD: '暂停晋级',
    COMPLETE: '发布完成',
    ERROR: '建议异常',
  }
  return props.routingPolicyProgressiveDelivery
    ? labels[props.routingPolicyProgressiveDelivery.state]
    : '加载中'
})
const automationLabel = computed(() => {
  const automation = props.routingPolicyProgressiveDeliveryAutomation
  if (!automation) return '加载中'
  if (automation.emergencyStop) return '紧急停止'
  if (!automation.executorConfigured) return '安全关闭'
  if (!automation.control.automationEnabled) return '等待授权'
  if (automation.control.paused) return '已暂停'
  if (automation.recommendation.dryRun) return 'DRY RUN'
  return automation.eligibleToExecute ? '自动执行就绪' : '等待门禁'
})
const lastExecutionLabel = computed(() => {
  const outcome = props.routingPolicyProgressiveDeliveryAutomation
    ?.lastExecution?.outcome
  const labels = {
    APPLIED: '晋级成功',
    FAILED: '执行失败',
    EMERGENCY_ROLLBACK: '紧急回退',
    MANUAL_ROLLBACK: '人工回退',
  }
  return outcome ? labels[outcome] : '暂无真实变更'
})
const progressiveStages = computed(() => [
  { label: 'SHADOW', rate: 0 },
  ...(props.routingPolicyProgressiveDelivery?.canaryStages ?? [])
    .map((rate) => ({ label: percent(rate), rate })),
  { label: 'ACTIVE', rate: 1 },
])
const currentProgressiveStage = computed(() => {
  const delivery = props.routingPolicyProgressiveDelivery
  if (!delivery || delivery.currentMode === 'OFF' || delivery.currentMode === 'SHADOW') {
    return 0
  }
  if (delivery.currentMode === 'ACTIVE') {
    return progressiveStages.value.length - 1
  }
  return Math.max(1, delivery.currentStageIndex + 1)
})

const latestArtifact = computed(() => props.routingPolicyRegistry?.artifacts[0] ?? null)
const contextualRuleCount = computed(
  () => Object.keys(latestArtifact.value?.contextualRules ?? {}).length,
)
const contextualCoverage = computed(() => {
  const bucketCount = props.routingPolicy?.contextualBucketCount ?? 0
  if (bucketCount === 0) return '—'
  return percent(contextualRuleCount.value / bucketCount)
})
const holdoutRule = computed(() => {
  const rules = latestArtifact.value?.temporalHoldout?.rules ?? {}
  return rules.GLOBAL ?? Object.values(rules).find((rule) => rule.passed) ?? Object.values(rules)[0]
})
const holdoutLabel = computed(() => {
  const holdout = latestArtifact.value?.temporalHoldout
  if (!holdout?.enabled) return '等待新策略'
  return holdout.validationPassed ? '独立验证通过' : '独立验证未通过'
})

function shortVersion(value: string | undefined): string {
  if (!value) return '—'
  return value.length > 24 ? `${value.slice(0, 21)}…` : value
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

    <section class="alignment-card">
      <div class="alignment-heading">
        <span>AI 自动评分</span>
        <strong
          :class="{
            ready: alignmentAutomation?.mode === 'READY',
            warning:
              alignmentAutomation?.mode === 'COOLDOWN'
              || alignmentAutomation?.mode === 'DAILY_LIMIT',
          }"
        >
          {{ alignmentAutomationLabel }}
        </strong>
      </div>
      <div class="alignment-meta">
        <span>待评分 {{ alignmentAutomation?.pendingTrajectoryCount ?? 0 }}</span>
        <span>已评分 {{ alignmentAutomation?.assessmentCount ?? 0 }}</span>
        <span>
          今日 {{ alignmentAutomation?.control.assessedToday ?? 0 }}
          / {{ alignmentAutomation?.dailyTrajectoryLimit ?? '—' }}
        </span>
        <span>
          Judge 调用估算
          {{ alignmentAutomation?.estimatedJudgeCallsToday ?? 0 }}
        </span>
      </div>
      <p v-if="alignmentAutomation?.control.lastError" class="alignment-error">
        {{ alignmentAutomation.control.lastError }}
      </p>
      <p v-else>
        {{
          alignmentAutomation?.schedulerConfigured
            ? `每批最多 ${alignmentAutomation.batchSize} 条，连续失败自动冷却`
            : '自动调度默认关闭，可通过受保护接口手动评分'
        }}
      </p>
    </section>

    <section class="policy-card">
      <div class="policy-heading">
        <span>路由策略</span>
        <strong :class="`mode-${routingPolicy?.deployment.mode?.toLowerCase() ?? 'loading'}`">
          {{ rolloutLabel }}
        </strong>
      </div>
      <p>{{ rolloutDescription }}</p>
      <div class="policy-samples">
        <span>
          SINGLE
          <b>{{ routingPolicy?.singleAgent.sampleCount ?? 0 }}</b>
          / {{ routingPolicy?.minimumSamplesPerMode ?? '—' }}
        </span>
        <span>
          MULTI
          <b>{{ routingPolicy?.multiAgent.sampleCount ?? 0 }}</b>
          / {{ routingPolicy?.minimumSamplesPerMode ?? '—' }}
        </span>
      </div>
      <div class="policy-meta">
        <span>{{ routingPolicy?.ready ? '对照样本已平衡' : '正在收集平衡样本' }}</span>
        <span v-if="routingPolicy?.deployment.mode === 'CANARY'">
          灰度 {{ percent(routingPolicy.deployment.canaryRate) }} ·
          {{
            routingPolicyQualityGuard?.canary.sampleCount
              ?? routingPolicy.canarySelectedTrajectoryCount
          }}
          /
          {{ routingPolicy.minimumCanarySamples }}
        </span>
        <span v-else>观察 {{ routingPolicy?.observedTrajectoryCount ?? 0 }}</span>
      </div>
      <div class="registry-line">
        <span>渐进式发布</span>
        <strong
          :class="{
            'artifact-validated': routingPolicyProgressiveDelivery?.state === 'READY'
              || routingPolicyProgressiveDelivery?.state === 'COMPLETE',
            'artifact-rejected': routingPolicyProgressiveDelivery?.state === 'HOLD'
              || routingPolicyProgressiveDelivery?.state === 'ERROR',
          }"
        >
          {{ progressiveLabel }}
          {{ routingPolicyProgressiveDelivery?.dryRun ? '· DRY RUN' : '' }}
        </strong>
      </div>
      <div class="delivery-stages" aria-label="渐进式发布阶段">
        <span
          v-for="(stage, index) in progressiveStages"
          :key="`${stage.label}-${stage.rate}`"
          :class="{
            passed: index < currentProgressiveStage,
            current: index === currentProgressiveStage,
          }"
        >
          {{ stage.label }}
        </span>
      </div>
      <div class="artifact-meta">
        <span>
          当前 {{ percent(routingPolicyProgressiveDelivery?.currentTrafficRate) }}
        </span>
        <span>
          建议
          {{ routingPolicyProgressiveDelivery?.recommendedMode ?? '—' }}
          {{ percent(routingPolicyProgressiveDelivery?.recommendedTrafficRate) }}
        </span>
        <span v-if="routingPolicyProgressiveDelivery?.currentMode === 'CANARY'">
          灰度 {{ routingPolicyProgressiveDelivery.canarySamples }}
          / {{ routingPolicyProgressiveDelivery.minimumCanarySamples }}
          · 对照 {{ routingPolicyProgressiveDelivery.controlSamples }}
          / {{ routingPolicyProgressiveDelivery.minimumControlSamples }}
        </span>
      </div>
      <p
        v-if="
          routingPolicyProgressiveDelivery
          && routingPolicyProgressiveDelivery.state !== 'READY'
          && routingPolicyProgressiveDelivery.state !== 'COMPLETE'
        "
        class="delivery-reason"
      >
        {{
          routingPolicyProgressiveDelivery.blockers[0]
          ?? routingPolicyProgressiveDelivery.reason
        }}
      </p>
      <div class="registry-line">
        <span>自动发布</span>
        <strong
          :class="{
            'artifact-validated':
              routingPolicyProgressiveDeliveryAutomation?.eligibleToExecute,
            'artifact-rejected':
              routingPolicyProgressiveDeliveryAutomation?.emergencyStop
              || routingPolicyProgressiveDeliveryAutomation
                ?.lastExecution?.outcome === 'FAILED',
          }"
        >
          {{ automationLabel }}
        </strong>
      </div>
      <div class="artifact-meta">
        <span>
          主开关
          {{
            routingPolicyProgressiveDeliveryAutomation?.executorConfigured
              ? 'ON'
              : 'OFF'
          }}
        </span>
        <span>
          授权
          {{
            routingPolicyProgressiveDeliveryAutomation?.control.automationEnabled
              ? 'ON'
              : 'OFF'
          }}
        </span>
        <span>
          {{
            routingPolicyProgressiveDeliveryAutomation?.control.paused
              ? 'PAUSED'
              : 'RUNNING'
          }}
        </span>
        <span>最近 {{ lastExecutionLabel }}</span>
      </div>
      <p
        v-if="
          routingPolicyProgressiveDeliveryAutomation
          && !routingPolicyProgressiveDeliveryAutomation.eligibleToExecute
        "
        class="delivery-reason"
      >
        {{
          routingPolicyProgressiveDeliveryAutomation.blockers[0]
          ?? routingPolicyProgressiveDeliveryAutomation.reason
        }}
      </p>
      <div
        class="guard-line"
        :class="`guard-${routingPolicyQualityGuard?.state.toLowerCase() ?? 'loading'}`"
      >
        <span>{{ guardLabel }}</span>
        <span
          v-if="
            routingPolicyQualityGuard?.state === 'COLLECTING'
            || routingPolicyQualityGuard?.state === 'HEALTHY'
            || routingPolicyQualityGuard?.state === 'ROLLED_BACK'
          "
        >
          灰度 {{ routingPolicyQualityGuard.canary.sampleCount }}
          · 对照 {{ routingPolicyQualityGuard.control.sampleCount }}
        </span>
      </div>
      <p
        v-if="
          routingPolicyQualityGuard?.state === 'ROLLED_BACK'
          || routingPolicyQualityGuard?.state === 'ERROR'
        "
        class="guard-reason"
      >
        {{ routingPolicyQualityGuard.violations[0] ?? routingPolicyQualityGuard.reason }}
      </p>
      <div class="registry-line">
        <span>离线策略评测</span>
        <strong>{{ offPolicyLabel }}</strong>
      </div>
      <div class="artifact-meta">
        <span>
          SINGLE {{ routingPolicyOffPolicyEvaluation?.singleActionSamples ?? 0 }}
          / {{ routingPolicyOffPolicyEvaluation?.minimumSamplesPerAction ?? '—' }}
        </span>
        <span>
          MULTI {{ routingPolicyOffPolicyEvaluation?.multiActionSamples ?? 0 }}
          / {{ routingPolicyOffPolicyEvaluation?.minimumSamplesPerAction ?? '—' }}
        </span>
        <span>
          ESS {{ score(routingPolicyOffPolicyEvaluation?.effectiveSampleSize) }}
          / {{ score(routingPolicyOffPolicyEvaluation?.minimumEffectiveSampleSize) }}
        </span>
        <span v-if="routingPolicyOffPolicyEvaluation?.state === 'READY'">
          SNIPS Δ {{ routingPolicyOffPolicyEvaluation.estimatedRewardLift.toFixed(3) }}
          · 95% 下界
          {{ routingPolicyOffPolicyEvaluation.rewardLiftLowerConfidenceBound.toFixed(3) }}
        </span>
      </div>
      <p
        v-if="
          routingPolicyOffPolicyEvaluation?.state === 'REGRESSION'
          || routingPolicyOffPolicyEvaluation?.state === 'ERROR'
        "
        class="guard-reason"
      >
        {{
          routingPolicyOffPolicyEvaluation.blockers[0]
          ?? routingPolicyOffPolicyEvaluation.reason
        }}
      </p>
      <div class="registry-line">
        <span>ACTIVE 漂移监控</span>
        <strong>{{ driftLabel }}</strong>
      </div>
      <div class="artifact-meta">
        <span>
          基线 {{ routingPolicyDrift?.reference.sampleCount ?? 0 }}
          / {{ routingPolicyDrift?.minimumReferenceSamples ?? '—' }}
        </span>
        <span>
          当前 {{ routingPolicyDrift?.active.sampleCount ?? 0 }}
          / {{ routingPolicyDrift?.minimumActiveSamples ?? '—' }}
        </span>
        <span>
          JS {{ score(routingPolicyDrift?.featureJsDivergence) }}
          / {{ score(routingPolicyDrift?.maximumFeatureJsDivergence) }}
        </span>
        <span v-if="routingPolicyDrift?.state === 'DRIFTED'">
          连续 {{ routingPolicyDrift.consecutiveViolationCount }}
          / {{ routingPolicyDrift.consecutiveViolationsRequired }}
        </span>
      </div>
      <p
        v-if="
          routingPolicyDrift?.state === 'DRIFTED'
          || routingPolicyDrift?.state === 'ROLLED_BACK'
          || routingPolicyDrift?.state === 'ERROR'
        "
        class="guard-reason"
      >
        {{ routingPolicyDrift.violations[0] ?? routingPolicyDrift.reason }}
      </p>
      <div class="registry-line">
        <span>策略资产</span>
        <strong>{{ routingPolicyRegistry?.artifacts.length ?? 0 }}</strong>
      </div>
      <div class="artifact-line">
        <span :title="latestArtifact?.version">
          {{ shortVersion(latestArtifact?.version) }}
        </span>
        <b :class="`artifact-${latestArtifact?.status.toLowerCase() ?? 'loading'}`">
          {{ latestArtifact?.status ?? 'LOADING' }}
        </b>
      </div>
      <div v-if="latestArtifact && latestArtifact.status !== 'BASELINE'" class="artifact-meta">
        <span>样本 {{ latestArtifact.trainingSampleCount }}</span>
        <span>指纹 {{ latestArtifact.trainingDataFingerprint.slice(0, 10) }}</span>
        <span>效用 Δ {{ latestArtifact.offlineEvaluation.multiAgentUtilityLift.toFixed(3) }}</span>
        <span>
          场景规则 {{ contextualRuleCount }} /
          {{ routingPolicy?.contextualBucketCount ?? 0 }}
          · 覆盖 {{ contextualCoverage }}
        </span>
        <span>
          全局回退
          {{
            latestArtifact.globalRule?.deployable
              ? latestArtifact.globalRule.recommendedMode
              : '确定性'
          }}
        </span>
      </div>
      <div v-if="latestArtifact && latestArtifact.status !== 'BASELINE'" class="registry-line">
        <span>时间留出门禁</span>
        <strong
          :class="
            latestArtifact.temporalHoldout.validationPassed
              ? 'artifact-validated'
              : 'artifact-rejected'
          "
        >
          {{ holdoutLabel }}
        </strong>
      </div>
      <div v-if="latestArtifact && latestArtifact.status !== 'BASELINE'" class="artifact-meta">
        <span>训练 {{ latestArtifact.trainingSampleCount }}</span>
        <span>
          较新验证 {{ latestArtifact.temporalHoldout.validationSampleCount }}
          · {{ percent(latestArtifact.temporalHoldout.validationRatio) }}
        </span>
        <span>
          SINGLE {{ holdoutRule?.singleAgentSamples ?? 0 }}
          / {{ latestArtifact.temporalHoldout.minimumSamplesPerMode }}
        </span>
        <span>
          MULTI {{ holdoutRule?.multiAgentSamples ?? 0 }}
          / {{ latestArtifact.temporalHoldout.minimumSamplesPerMode }}
        </span>
        <span v-if="holdoutRule">
          95% 效用下界 {{ holdoutRule.lowerConfidenceBound.toFixed(3) }}
          / {{ latestArtifact.temporalHoldout.minimumUtilityLiftLowerBound.toFixed(3) }}
        </span>
      </div>
      <p
        v-if="
          latestArtifact
          && latestArtifact.status !== 'BASELINE'
          && !latestArtifact.temporalHoldout.validationPassed
        "
        class="guard-reason"
      >
        {{
          latestArtifact.temporalHoldout.validationFailures[0]
          ?? latestArtifact.validationReason
        }}
      </p>
    </section>

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
.alignment-card {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background:
    linear-gradient(135deg, rgba(96, 165, 250, 0.06), transparent 58%),
    var(--surface);
}
.alignment-heading,
.alignment-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.alignment-heading span {
  color: var(--text-muted);
  font-size: 0.6875rem;
}
.alignment-heading strong {
  color: var(--text-heading);
  font: 600 0.57rem/1 var(--mono);
}
.alignment-heading strong.ready {
  color: var(--accent);
}
.alignment-heading strong.warning {
  color: #f5c76b;
}
.alignment-meta {
  flex-wrap: wrap;
  margin-top: 9px;
  color: var(--text-muted);
  font: 0.52rem/1.25 var(--mono);
}
.alignment-card p {
  margin: 9px 0 0;
  color: var(--text-muted);
  font-size: 0.65rem;
  line-height: 1.45;
}
.alignment-card p.alignment-error {
  color: #fca5a5;
}
.policy-card {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background:
    linear-gradient(135deg, rgba(74, 222, 128, 0.055), transparent 58%),
    var(--surface);
}
.policy-heading,
.policy-meta,
.policy-samples {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.policy-heading span {
  color: var(--text-muted);
  font-size: 0.6875rem;
}
.policy-heading strong {
  padding: 3px 6px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-heading);
  font: 600 0.57rem/1 var(--mono);
  letter-spacing: 0.04em;
}
.policy-heading .mode-shadow {
  color: #f5c76b;
  border-color: rgba(245, 199, 107, 0.35);
}
.policy-heading .mode-canary,
.policy-heading .mode-active {
  color: var(--accent);
  border-color: var(--accent-dim);
}
.policy-card p {
  margin: 9px 0 11px;
  color: var(--text);
  font-size: 0.7rem;
  line-height: 1.45;
}
.policy-samples {
  margin-bottom: 8px;
}
.policy-samples span {
  flex: 1;
  padding: 7px;
  border: 1px solid var(--border);
  border-radius: 5px;
  color: var(--text-muted);
  font: 0.55rem/1 var(--mono);
}
.policy-samples b {
  margin-left: 3px;
  color: var(--text-heading);
}
.policy-meta {
  color: var(--text-muted);
  font-size: 0.62rem;
}
.guard-line {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px solid var(--border);
  color: var(--text-muted);
  font: 0.58rem/1.2 var(--mono);
}
.guard-line.guard-healthy {
  color: var(--accent);
}
.guard-line.guard-rolled_back,
.guard-line.guard-error {
  color: #fca5a5;
}
.guard-reason {
  margin: 7px 0 0;
  color: #fca5a5;
  font-size: 0.62rem;
}
.registry-line,
.artifact-line,
.artifact-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.registry-line {
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 0.62rem;
}
.registry-line strong {
  color: var(--text-heading);
  font-family: var(--mono);
}
.registry-line strong.artifact-validated {
  color: var(--accent);
}
.registry-line strong.artifact-rejected {
  color: #fca5a5;
}
.delivery-stages {
  display: flex;
  gap: 4px;
  margin-top: 8px;
}
.delivery-stages span {
  flex: 1;
  overflow: hidden;
  padding: 5px 2px;
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text-muted);
  font: 0.48rem/1 var(--mono);
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.delivery-stages span.passed {
  border-color: var(--accent-dim);
  color: var(--accent);
  background: rgba(45, 212, 191, 0.05);
}
.delivery-stages span.current {
  border-color: rgba(245, 199, 107, 0.55);
  color: #f5c76b;
  background: rgba(245, 199, 107, 0.08);
}
.delivery-reason {
  margin: 7px 0 0;
  color: var(--text-muted);
  font-size: 0.62rem;
}
.artifact-line {
  margin-top: 7px;
  color: var(--text);
  font: 0.57rem/1.2 var(--mono);
}
.artifact-line b {
  color: var(--text-muted);
  font-size: 0.52rem;
}
.artifact-line .artifact-validated {
  color: var(--accent);
}
.artifact-line .artifact-rejected {
  color: #fca5a5;
}
.artifact-meta {
  margin-top: 6px;
  color: var(--text-muted);
  font: 0.5rem/1.2 var(--mono);
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
