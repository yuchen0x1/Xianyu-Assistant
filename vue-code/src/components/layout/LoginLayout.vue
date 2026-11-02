<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="login-layout">
    <RouterView />
  </div>
</template>

<style scoped>
.login-layout {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  overflow-x: hidden;
  overflow-y: auto;
  background: var(--gradient-login-deep);
}
</style>
