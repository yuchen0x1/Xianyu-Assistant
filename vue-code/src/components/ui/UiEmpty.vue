<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  description?: string
}>(), {
  title: '暂无数据',
  description: ''
})
</script>

<template>
  <div class="ui-empty">
    <div class="ui-empty__icon" aria-hidden="true">
      <slot name="icon">∅</slot>
    </div>
    <h3 class="ui-empty__title">{{ title }}</h3>
    <p v-if="description" class="ui-empty__desc">{{ description }}</p>
    <div v-if="$slots.action" class="ui-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.ui-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 220px;
  padding: 28px 16px;
  text-align: center;
  color: var(--text-2);
}

.ui-empty__icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin-bottom: 4px;
  color: var(--text-2);
  font-size: 22px;
  border-radius: 14px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--glass-bg-strong) 82%, transparent), color-mix(in srgb, var(--glass-bg-soft) 72%, transparent));
  border: 1px solid var(--glass-border-strong);
  box-shadow: var(--glass-edge-glow), inset 0 1px 0 var(--glass-highlight-strong);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.ui-empty__title {
  margin: 0;
  color: var(--text-1);
  font-size: 15px;
  font-weight: 700;
}

.ui-empty__desc {
  margin: 0;
  max-width: 320px;
  color: var(--text-3);
  font-size: 13px;
  line-height: 1.5;
}

.ui-empty__action {
  margin-top: 8px;
}
</style>
