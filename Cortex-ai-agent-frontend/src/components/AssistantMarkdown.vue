<script setup lang="ts">
import { computed } from 'vue'
import { renderAssistantMarkdown } from '../utils/markdown.js'

const props = defineProps<{ content: string }>()
const rendered = computed(() => renderAssistantMarkdown(props.content))
</script>

<template>
  <!-- Only the HTML-disabled, link-restricted parser output may enter this sink. -->
  <div class="assistant-markdown" v-html="rendered" />
</template>

<style scoped>
.assistant-markdown { white-space: normal; overflow-wrap: anywhere; }
.assistant-markdown :deep(> :first-child) { margin-top: 0; }
.assistant-markdown :deep(> :last-child) { margin-bottom: 0; }
.assistant-markdown :deep(p) { margin: 0.65em 0; }
.assistant-markdown :deep(h1), .assistant-markdown :deep(h2),
.assistant-markdown :deep(h3), .assistant-markdown :deep(h4) {
  font-size: 1.05em; line-height: 1.5; margin: 1em 0 0.4em;
}
.assistant-markdown :deep(ul), .assistant-markdown :deep(ol) { padding-left: 1.5em; margin: 0.6em 0; }
.assistant-markdown :deep(li + li) { margin-top: 0.35em; }
.assistant-markdown :deep(blockquote) { margin: 0.7em 0; padding-left: 1em; border-left: 3px solid var(--border); }
.assistant-markdown :deep(pre) { overflow-x: auto; white-space: pre; padding: 0.8em; background: var(--bg-secondary, #121823); border-radius: 6px; }
.assistant-markdown :deep(code) { font-family: monospace; }
.assistant-markdown :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; margin: 0.7em 0; }
.assistant-markdown :deep(th), .assistant-markdown :deep(td) { border: 1px solid var(--border); padding: 0.4em 0.65em; }
.assistant-markdown :deep(a) { color: var(--accent); text-decoration: underline; }
</style>
