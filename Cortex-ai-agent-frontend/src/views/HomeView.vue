<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="home">
    <div class="home-bg" aria-hidden="true"></div>
    <header class="home-header">
      <p class="geek-prefix">$ cortex --mode=select</p>
      <h1 class="logo">CORTEX<span class="logo-accent">.AI</span></h1>
      <p class="tagline">/ 选择应用</p>
    </header>
    <nav class="app-grid">
      <RouterLink to="/love" class="app-card">
        <span class="app-icon love">♥</span>
        <span class="app-name">AI 恋爱大师</span>
        <p class="app-desc">对话与陪伴</p>
        <ul class="app-bullets">
          <li>Agentic RAG 检索闭环</li>
          <li>规划、验证与答案修正</li>
          <li>多轮对话与轨迹记录</li>
          <li>评分反馈驱动 Agent RL</li>
        </ul>
      </RouterLink>
      <RouterLink to="/manus" class="app-card">
        <span class="app-icon manus">◇</span>
        <span class="app-name">AI 超级智能体</span>
        <p class="app-desc">智能任务与问答</p>
        <ul class="app-bullets">
          <li>通用智能问答</li>
          <li>工具调用与执行</li>
          <li>复杂任务编排</li>
          <li>流式实时输出</li>
        </ul>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.home {
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
  padding: var(--page-py) var(--page-px);
  min-height: 100%;
  position: relative;
}
.home-bg {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, transparent 0%, var(--bg) 100%),
    repeating-linear-gradient(
      0deg,
      transparent,
      transparent 2px,
      rgba(0, 212, 170, 0.03) 2px,
      rgba(0, 212, 170, 0.03) 4px
    );
  pointer-events: none;
}
.home-header {
  text-align: center;
  margin-bottom: 48px;
  position: relative;
}
.geek-prefix {
  font-family: var(--mono);
  font-size: 0.75rem;
  color: var(--accent);
  margin: 0 0 12px;
  letter-spacing: 0.08em;
  text-shadow: 0 0 20px var(--accent-glow);
}
.logo {
  font-size: clamp(1.75rem, 5vw, 2.25rem);
  font-weight: 700;
  letter-spacing: 0.2em;
  color: var(--text-heading);
  margin: 0 0 6px;
  font-family: var(--mono);
}
.logo-accent {
  color: var(--accent);
  text-shadow: 0 0 24px var(--accent-glow);
}
.tagline {
  font-size: 0.8125rem;
  color: var(--text-muted);
  margin: 0;
  font-family: var(--mono);
  letter-spacing: 0.06em;
}
.app-grid {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  max-width: 700px;
  margin: 0 auto;
  position: relative;
}
.app-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 10px;
  min-height: 220px;
  padding: 28px 22px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  text-decoration: none;
  color: inherit;
  transition: border-color 0.2s, box-shadow 0.2s;
  position: relative;
}
.app-card::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: calc(var(--radius) + 1px);
  padding: 1px;
  background: linear-gradient(135deg, var(--accent) 0%, transparent 50%, var(--border) 100%);
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  opacity: 0.4;
  pointer-events: none;
}
.app-card:hover {
  border-color: var(--accent-dim);
  box-shadow: 0 0 32px var(--accent-glow);
}
.app-card:hover::before {
  opacity: 0.8;
}
.app-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.35rem;
  color: var(--text-heading);
  flex-shrink: 0;
  border: 1px solid var(--border);
}
.app-icon.love {
  background: var(--surface-elevated);
  border-color: rgba(232, 30, 99, 0.4);
  color: #f48fb1;
}
.app-icon.manus {
  background: var(--surface-elevated);
  border-color: rgba(0, 212, 170, 0.4);
  color: var(--accent);
}
.app-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-heading);
  font-family: var(--mono);
  letter-spacing: 0.04em;
}
.app-desc {
  font-size: 0.8125rem;
  color: var(--text-muted);
  margin: 0;
}
.app-bullets {
  list-style: none;
  padding: 0;
  margin: 4px 0 0;
  width: 100%;
  text-align: left;
  font-size: 0.8125rem;
  color: var(--text-muted);
  line-height: 1.6;
}
.app-bullets li {
  position: relative;
  padding-left: 14px;
  margin-bottom: 4px;
}
.app-bullets li::before {
  content: '>';
  position: absolute;
  left: 0;
  color: var(--accent);
  font-family: var(--mono);
  font-size: 0.75rem;
}

@media (max-width: 768px) {
  .app-grid {
    grid-template-columns: 1fr;
    max-width: 380px;
  }
  .app-card {
    min-height: auto;
    padding: 24px 20px;
  }
}
@media (max-width: 640px) {
  .home-header {
    margin-bottom: 32px;
  }
  .app-card {
    padding: 20px 18px;
  }
}
</style>
