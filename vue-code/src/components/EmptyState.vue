<script setup lang="ts">
withDefaults(defineProps<{
  icon?: string
  title?: string
  desc?: string
  actionText?: string
  action?: (...args: unknown[]) => void
}>(), {
  icon: '',
  title: '暂无数据',
  desc: '',
  actionText: ''
})
</script>

<template>
  <div class="empty-state">
    <div class="empty-state__icon" aria-hidden="true">
      <svg v-if="!icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
        <path d="M13 2v6h6" />
        <path d="M8 13h6" />
        <path d="M8 17h4" />
      </svg>
      <span v-else>{{ icon }}</span>
    </div>
    <h4 class="empty-state__title">{{ title }}</h4>
    <p v-if="desc" class="empty-state__desc">{{ desc }}</p>
    <button
      v-if="actionText && action"
      class="empty-state__action"
      @click="action?.()"
    >
      {{ actionText }}
    </button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 200px;
  padding: var(--space-5) var(--space-4);
  text-align: center;
  color: var(--text-2);
}

.empty-state__icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: var(--space-1);
  color: var(--text-2);
  border-radius: var(--radius-md);
  background: linear-gradient(145deg, color-mix(in srgb, var(--glass-bg-strong) 82%, transparent), color-mix(in srgb, var(--glass-bg-soft) 72%, transparent));
  border: 1px solid var(--glass-border-strong);
  box-shadow: var(--glass-edge-glow), inset 0 1px 0 var(--glass-highlight-strong);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.empty-state__icon svg {
  width: 24px;
  height: 24px;
}

.empty-state__icon span {
  font-size: 20px;
  line-height: 1;
}

.empty-state__title {
  margin: 0;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 600;
}

.empty-state__desc {
  margin: 0;
  max-width: 300px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
}

.empty-state__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  margin-top: var(--space-2);
  padding: 0 var(--space-4);
  font-size: 13px;
  font-weight: 500;
  color: var(--brand-600);
  background: color-mix(in srgb, var(--glass-bg-strong) 82%, var(--bg-active));
  border: 1px solid var(--glass-border-strong);
  border-radius: var(--radius-pill);
  box-shadow: var(--glass-edge-glow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard), border-color var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard);
}

@media (hover: hover) {
  .empty-state__action:hover {
    background: var(--glass-bg-strong);
    border-color: var(--glass-border-strong);
    box-shadow: var(--glass-shadow-float);
  }
}

.empty-state__action:active {
  transform: scale(0.97);
}
</style>
