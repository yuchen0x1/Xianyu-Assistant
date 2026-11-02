<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/components/layout/AppLayout.vue'
import LoginLayout from '@/components/layout/LoginLayout.vue'
import router from '@/router'

const route = useRoute()

// 等路由首次就绪后再渲染，避免初始帧布局闪烁，也避免登录页短暂挂载应用外壳
const isReady = ref(false)
router.isReady().then(() => {
  isReady.value = true
})

const isLoginPage = computed(() => route.path === '/login')
</script>

<template>
  <template v-if="isReady">
    <Transition name="fade" mode="out-in">
      <LoginLayout v-if="isLoginPage" />
      <AppLayout v-else />
    </Transition>
  </template>
</template>

<style>
html,
body {
  overflow: hidden;
}
</style>
