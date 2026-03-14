<script setup lang="ts">
import AppFooter from './components/AppFooter.vue'
</script>

<template>
  <div class="app">
    <main class="app-main">
      <div class="view-wrap">
        <router-view />
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  background: var(--bg);
  display: flex;
  flex-direction: column;
}
.app-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.view-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
