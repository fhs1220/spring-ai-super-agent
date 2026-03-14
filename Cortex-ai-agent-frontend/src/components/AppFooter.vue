<script setup lang="ts">
const currentYear = new Date().getFullYear()
const email = 'fanghaosen88@gmail.com'
</script>

<template>
  <footer class="app-footer">
    <div class="footer-inner">
      <p class="copyright">
        © {{ currentYear }} Cortex AI. All rights reserved.
      </p>
      <p class="contact">
        <a :href="`mailto:${email}`" class="email">{{ email }}</a>
      </p>
    </div>
  </footer>
</template>

<style scoped>
.app-footer {
  flex-shrink: 0;
  border-top: 1px solid var(--border);
  background: var(--surface);
  padding: var(--page-px);
}
.footer-inner {
  max-width: 720px;
  margin: 0 auto;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px 24px;
}
.copyright {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--text-muted);
  font-family: var(--mono);
}
.contact {
  margin: 0;
}
.email {
  font-size: 0.8125rem;
  color: var(--accent);
  text-decoration: none;
}
.email:hover {
  text-decoration: underline;
  color: var(--accent-dim);
}
@media (max-width: 640px) {
  .footer-inner {
    flex-direction: column;
    gap: 4px;
  }
}
</style>
