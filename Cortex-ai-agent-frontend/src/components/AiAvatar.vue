<script setup lang="ts">
defineProps<{
  type: 'love' | 'manus'
}>()
</script>

<template>
  <div class="ai-avatar" :class="type" aria-hidden="true">
    <!-- 恋爱大师：心形 -->
    <svg v-if="type === 'love'" viewBox="0 0 24 24" fill="currentColor">
      <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>
    </svg>
    <!-- 超级智能体：六边形/芯片 -->
    <svg v-else viewBox="0 0 24 24" fill="currentColor">
      <path d="M12 2L2 7v10l10 5 10-5V7L12 2zm0 2.18l6.9 3.45v6.74L12 17.82l-6.9-3.45V7.63L12 4.18zM12 7L5 10.5V13l7 3.5 7-3.5v-2.5L12 7z"/>
    </svg>
  </div>
</template>

<style scoped>
.ai-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-text);
}
.ai-avatar svg {
  width: 20px;
  height: 20px;
}
.ai-avatar.love {
  background: var(--surface-elevated);
  border: 1px solid rgba(232, 30, 99, 0.5);
  color: #f48fb1;
}
.ai-avatar.manus {
  background: var(--surface-elevated);
  border: 1px solid rgba(0, 212, 170, 0.5);
  color: var(--accent);
}

@media (max-width: 640px) {
  .ai-avatar {
    width: 32px;
    height: 32px;
  }
  .ai-avatar svg {
    width: 18px;
    height: 18px;
  }
}
</style>
