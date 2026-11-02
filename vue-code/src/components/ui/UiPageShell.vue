<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  subtitle?: string
  compact?: boolean
}>(), {
  title: '',
  subtitle: '',
  compact: false
})
</script>

<template>
  <div class="ui-page" :class="{ 'ui-page--compact': compact }">
    <header v-if="title || $slots.actions || $slots.title" class="ui-page__header">
      <div class="ui-page__heading">
        <slot name="title">
          <h1 v-if="title" class="ui-page__title">{{ title }}</h1>
          <p v-if="subtitle" class="ui-page__subtitle">{{ subtitle }}</p>
        </slot>
      </div>
      <div v-if="$slots.actions" class="ui-page__actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="ui-page__body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="ui-page__footer">
      <slot name="footer" />
    </footer>
  </div>
</template>

<style scoped>
.ui-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 100%;
}

.ui-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ui-page__heading {
  min-width: 0;
}

.ui-page__title {
  margin: 0;
  color: var(--text-1);
  font-size: 22px;
  font-weight: 780;
  letter-spacing: 0;
  line-height: 1.2;
}

.ui-page__subtitle {
  margin: 4px 0 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.4;
}

.ui-page__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.ui-page__body {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 16px;
}

.ui-page__footer {
  display: none;
}

@media (max-width: 767.98px) {
  .ui-page__header {
    flex-direction: column;
  }

  .ui-page__actions {
    width: 100%;
  }

  .ui-page__title {
    font-size: 20px;
  }

  .ui-page--compact .ui-page__title {
    display: none;
  }

  .ui-page__footer {
    display: flex;
    gap: 8px;
  }
}
</style>
