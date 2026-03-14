import { createRouter, createWebHistory } from 'vue-router'
import { setPageMeta } from '../utils/head'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../views/HomeView.vue'),
      meta: {
        title: 'Cortex AI',
        description: 'Cortex AI 应用中心：AI 恋爱大师与 AI 超级智能体，流式对话与智能问答。',
      },
    },
    {
      path: '/love',
      name: 'love',
      component: () => import('../views/LoveAppView.vue'),
      meta: {
        title: 'AI 恋爱大师',
        description: 'AI 恋爱大师 - 情感陪伴、恋爱建议与多轮对话，实时流式回复。',
      },
    },
    {
      path: '/manus',
      name: 'manus',
      component: () => import('../views/ManusView.vue'),
      meta: {
        title: 'AI 超级智能体',
        description: 'AI 超级智能体 - 通用智能问答、工具调用与复杂任务编排，流式实时输出。',
      },
    },
  ],
})

router.afterEach((to) => {
  const title = (to.meta.title as string) ?? 'Cortex AI'
  const description = (to.meta.description as string) ?? ''
  setPageMeta({ title, description })
})

export default router
