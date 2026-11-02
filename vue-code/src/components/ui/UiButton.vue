<script setup lang="ts">
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  type?: 'button' | 'submit' | 'reset'
  loading?: boolean
  disabled?: boolean
  block?: boolean
}>(), {
  variant: 'primary',
  type: 'button',
  loading: false,
  disabled: false,
  block: false,
})
</script>

<template>
  <button
    class="ui-btn"
    :class="[
      `ui-btn--${variant}`,
      { 'ui-btn--block': block, 'ui-btn--loading': loading }
    ]"
    :type="type"
    :disabled="disabled || loading"
  >
    <span v-if="loading" class="ui-btn__spinner" aria-hidden="true"></span>
    <slot />
  </button>
</template>

<style scoped>
.ui-btn--block {
  width: 100%;
}

.ui-btn--loading {
  position: relative;
}

.ui-btn__spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: currentColor;
  border-radius: 50%;
  animation: ui-btn-spin 0.7s linear infinite;
}

@keyframes ui-btn-spin {
  to { transform: rotate(360deg); }
}
</style>