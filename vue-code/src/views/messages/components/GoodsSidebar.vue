<script setup lang="ts">
import type { GoodsItemWithConfig } from '@/api/goods'
import IconImage from '@/components/icons/IconImage.vue'
import IconEmpty from '@/components/icons/IconEmpty.vue'

interface Props {
  goodsList: GoodsItemWithConfig[]
  goodsTotal: number
  goodsLoading: boolean
  goodsIdFilter: string
}

interface Emits {
  (e: 'select', goodsId: string, goods?: GoodsItemWithConfig): void
  (e: 'clearFilter'): void
}

defineProps<Props>()
const emit = defineEmits<Emits>()

const handleImgError = (e: Event) => {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}
</script>

<template>
  <div class="sidebar">
    <div class="sidebar__header">
      <span class="sidebar__title">商品列表</span>
      <button
        v-if="goodsIdFilter"
        class="sidebar__clear"
        @click="emit('clearFilter')"
      >
        取消筛选
      </button>
    </div>
    <div class="sidebar__list">
      <div
        v-for="goods in goodsList"
        :key="goods.item.id"
        class="sidebar__item"
        :class="{ 'sidebar__item--active': goodsIdFilter === goods.item.xyGoodId }"
        @click="emit('select', goods.item.xyGoodId, goods)"
      >
        <div class="sidebar__thumb">
          <img
            v-if="goods.item.coverPic"
            :src="goods.item.coverPic"
            :alt="goods.item.title"
            class="sidebar__img"
            @error="handleImgError"
          />
          <div v-else class="sidebar__placeholder">
            <IconImage />
          </div>
        </div>
        <div class="sidebar__info">
          <div class="sidebar__name">{{ goods.item.title }}</div>
          <div class="sidebar__id">{{ goods.item.xyGoodId }}</div>
        </div>
      </div>

      <div v-if="goodsLoading" class="sidebar__loading">
        <div class="sidebar__spinner"></div>
        <span>加载中...</span>
      </div>

      <div
        v-if="!goodsLoading && goodsList.length > 0 && goodsList.length >= goodsTotal"
        class="sidebar__end"
      >
        已加载全部
      </div>

      <div v-if="!goodsLoading && goodsList.length === 0" class="sidebar__empty">
        <div class="sidebar__empty-icon"><IconEmpty /></div>
        <p class="sidebar__empty-text">暂无商品</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar {
  --c-border: var(--border-1);
  --c-border-strong: var(--border-2);
  --c-text-1: var(--text-1);
  --c-text-2: var(--text-2);
  --c-text-3: var(--text-3);
  --c-accent: var(--brand-600);
  --c-ease: 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.sidebar__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--glass-bg-soft);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.sidebar__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text-1);
}

.sidebar__clear {
  font-size: 12px;
  font-weight: 500;
  color: var(--c-accent);
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: background var(--c-ease);
  -webkit-tap-highlight-color: transparent;
}

@media (hover: hover) {
  .sidebar__clear:hover {
    background: var(--brand-50);
  }
}

.sidebar__list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  scrollbar-width: thin;
  scrollbar-color: rgba(0, 0, 0, 0.08) transparent;
}

.sidebar__list::-webkit-scrollbar {
  width: 4px;
}

.sidebar__list::-webkit-scrollbar-thumb {
  background: var(--bg-hover);
  border-radius: 2px;
}

.sidebar__item {
  display: flex;
  gap: 10px;
  padding: 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: all var(--c-ease);
  -webkit-tap-highlight-color: transparent;
  border: 1px solid transparent;
}

@media (hover: hover) {
  .sidebar__item:hover {
    background: var(--bg-muted);
  }
}

.sidebar__item--active {
  background: var(--brand-50);
  border-color: color-mix(in srgb, var(--brand-600) 24%, transparent);
}

.sidebar__thumb {
  width: 40px;
  height: 40px;
  border-radius: 6px;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--glass-bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
}

.sidebar__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sidebar__placeholder {
  color: var(--c-text-3);
  opacity: 0.3;
}

.sidebar__placeholder svg {
  width: 16px;
  height: 16px;
}

.sidebar__info {
  flex: 1;
  min-width: 0;
}

.sidebar__name {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 2px;
}

.sidebar__id {
  font-size: 11px;
  color: var(--c-text-3);
  font-family: 'SF Mono', 'Menlo', monospace;
}

.sidebar__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  font-size: 12px;
  color: var(--c-text-3);
}

.sidebar__spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--border-1);
  border-top-color: var(--c-accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.sidebar__end {
  text-align: center;
  padding: 12px;
  font-size: 12px;
  color: var(--c-text-3);
  opacity: 0.6;
}

.sidebar__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 16px;
  gap: 8px;
}

.sidebar__empty-icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-3);
  opacity: 0.3;
}

.sidebar__empty-icon svg {
  width: 28px;
  height: 28px;
}

.sidebar__empty-text {
  font-size: 13px;
  color: var(--c-text-3);
}
</style>
