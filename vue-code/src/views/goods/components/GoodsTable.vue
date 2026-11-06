<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getGoodsStatusText, formatPrice } from '@/utils'
import type { GoodsItemWithConfig } from '@/api/goods'

import IconEmpty from '@/components/icons/IconEmpty.vue'
import IconTrash from '@/components/icons/IconTrash.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconSparkle from '@/components/icons/IconSparkle.vue'
import IconEdit from '@/components/icons/IconEdit.vue'

interface Props {
  goodsList: GoodsItemWithConfig[]
  loading?: boolean
}

interface Emits {
  (e: 'view', xyGoodId: string): void
  (e: 'edit', item: GoodsItemWithConfig): void
  (e: 'sync', xyGoodId: string): void
  (e: 'polish', xyGoodId: string): void
  (e: 'toggleAutoDelivery', item: GoodsItemWithConfig, value: boolean): void
  (e: 'toggleAutoReply', item: GoodsItemWithConfig, value: boolean): void
  (e: 'configAutoDelivery', item: GoodsItemWithConfig): void
  (e: 'delete', xyGoodId: string, title: string): void
}

defineProps<Props>()
const emit = defineEmits<Emits>()

const isMobile = ref(false)
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})

const getStatusColor = (status: number) => {
  const info = getGoodsStatusText(status)
  switch (info.type) {
    case 'success': return 'var(--success-500)'
    case 'warning': return 'var(--warning-500)'
    case 'info': return 'var(--text-2)'
    default: return 'var(--brand-600)'
  }
}

const getStatusBg = (status: number) => {
  const info = getGoodsStatusText(status)
  switch (info.type) {
    case 'success': return 'rgba(48,209,88,.2)'
    case 'warning': return 'rgba(255,159,10,.18)'
    case 'info': return 'rgba(120,120,128,.12)'
    default: return 'rgba(10,132,255,.15)'
  }
}

// 图片加载失败处理
const handleImgError = (e: Event) => {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}
</script>

<template>
  <!-- Mobile: Card View -->
  <div v-if="isMobile" class="card-list" :class="{ 'card-list--loading': loading }">
    <div
      v-for="item in goodsList"
      :key="item.item.xyGoodId"
      class="goods-card"
      @click="emit('view', item.item.xyGoodId)"
    >
      <!-- 图片区域（含悬浮标题） -->
      <div class="goods-card__image-wrap">
        <img
          v-if="item.item.coverPic"
          :src="item.item.coverPic"
          class="goods-card__image"
          @error="handleImgError"
        />
        <div v-else class="goods-card__image-placeholder">
          <IconImage />
        </div>

        <!-- 状态标签 -->
        <span
          class="goods-card__status"
          :style="{
            color: getStatusColor(item.item.status),
            background: getStatusBg(item.item.status)
          }"
        >
          {{ getGoodsStatusText(item.item.status).text }}
        </span>

        <!-- 玻璃渐变悬浮层：标题 + 价格 -->
        <div class="goods-card__overlay">
          <div class="goods-card__title-wrap">
            <div class="goods-card__title">{{ item.item.title }}</div>
          </div>
          <div class="goods-card__meta">
            <span class="goods-card__price">{{ formatPrice(item.item.soldPrice) }}</span>
            <div class="goods-card__tags">
              <span v-if="item.xianyuAutoDeliveryOn === 1" class="goods-card__mode-tag goods-card__mode-tag--delivery">{{ item.autoDeliveryType === 2 ? '卡密发货' : '文本发货' }}</span>
              <span v-if="item.xianyuAutoReplyOn === 1" class="goods-card__mode-tag goods-card__mode-tag--ai">AI</span>
              <span v-if="item.xianyuKeywordReplyOn === 1" class="goods-card__mode-tag goods-card__mode-tag--keyword">关键词</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部操作栏 -->
      <div class="goods-card__actions">
        <button
          class="goods-card__action goods-card__action--edit"
          @click.stop="emit('edit', item)"
        >
          <IconEdit />
          <span>编辑</span>
        </button>
        <button
          class="goods-card__action goods-card__action--config"
          @click.stop="emit('configAutoDelivery', item)"
        >
          <IconSparkle />
          <span>配置</span>
        </button>
        <button
          class="goods-card__action goods-card__action--delete"
          @click.stop="emit('delete', item.item.xyGoodId, item.item.title)"
        >
          <IconTrash />
          <span>删除</span>
        </button>
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="!loading && goodsList.length === 0" class="empty-state">
      <div class="empty-state__icon"><IconEmpty /></div>
      <p class="empty-state__text">暂无商品数据</p>
    </div>
  </div>

  <!-- Desktop/Tablet: Table View -->
  <div v-else class="table-container" :class="{ 'table-container--loading': loading }">
    <table class="table" v-if="goodsList.length > 0">
      <thead class="table__head">
        <tr>
          <th class="table__th table__th--image">图片</th>
          <th class="table__th">商品标题</th>
          <th class="table__th table__th--price">价格</th>
          <th class="table__th table__th--sku">规格</th>
          <th class="table__th table__th--status">状态</th>
          <th class="table__th table__th--switch">发货模式</th>
          <th class="table__th table__th--switch">回复模式</th>
          <th class="table__th table__th--actions">操作</th>
        </tr>
      </thead>
      <tbody class="table__body">
        <tr v-for="item in goodsList" :key="item.item.xyGoodId" class="table__tr">
          <td class="table__td table__td--image">
            <div class="goods-thumb">
              <img
                v-if="item.item.coverPic"
                :src="item.item.coverPic"
                class="goods-thumb__img"
                @error="handleImgError"
                @click="emit('view', item.item.xyGoodId)"
              />
              <div v-else class="goods-thumb__placeholder">
                <IconImage />
              </div>
            </div>
          </td>
          <td class="table__td table__td--title" @click="emit('view', item.item.xyGoodId)">
            <div class="goods-title-cell">
              <span class="goods-title-cell__name">{{ item.item.title }}</span>
              <span class="goods-title-cell__id">ID: {{ item.item.xyGoodId }}</span>
            </div>
          </td>
          <td class="table__td table__td--price">
            <span class="price-text">{{ formatPrice(item.item.soldPrice) }}</span>
          </td>
          <td class="table__td table__td--sku">
            <span v-if="item.item.skuCount > 1" class="sku-count-tag">{{ item.item.skuCount }}规格</span>
            <span v-else class="sku-count-tag sku-count-tag--single">1</span>
          </td>
          <td class="table__td table__td--status">
            <span
              class="status-tag"
              :style="{
                color: getStatusColor(item.item.status),
                background: getStatusBg(item.item.status)
              }"
            >
              {{ getGoodsStatusText(item.item.status).text }}
            </span>
          </td>
          <td class="table__td table__td--switch">
            <span v-if="item.xianyuAutoDeliveryOn === 1" class="reply-mode-tag reply-mode-tag--delivery">{{ item.autoDeliveryType === 2 ? '卡密' : '文本' }}</span>
            <span v-else class="reply-mode-tag reply-mode-tag--off">-</span>
          </td>
          <td class="table__td table__td--switch">
            <div class="reply-mode-tags">
              <span v-if="item.xianyuAutoReplyOn === 1" class="reply-mode-tag reply-mode-tag--ai">AI</span>
              <span v-if="item.xianyuKeywordReplyOn === 1" class="reply-mode-tag reply-mode-tag--keyword">关键词</span>
              <span v-if="item.xianyuAutoReplyOn !== 1 && item.xianyuKeywordReplyOn !== 1" class="reply-mode-tag reply-mode-tag--off">-</span>
            </div>
          </td>
          <td class="table__td table__td--actions">
            <button class="table__action table__action--detail" @click="emit('view', item.item.xyGoodId)">
              <IconCheck />
              <span>详情</span>
            </button>
            <button class="table__action table__action--sync" @click="emit('edit', item)">
              <IconEdit aria-hidden="true" />
              <span>编辑</span>
            </button>
            <button class="table__action table__action--sync" @click="emit('sync', item.item.xyGoodId)">
              <IconSparkle />
              <span>同步</span>
            </button>
            <button
              v-if="item.item.status === 0"
              class="table__action table__action--detail"
              title="擦亮商品提升曝光"
              @click="emit('polish', item.item.xyGoodId)"
            >
              <IconSparkle />
              <span>擦亮</span>
            </button>
            <button class="table__action table__action--delete" @click="emit('delete', item.item.xyGoodId, item.item.title)">
              <IconTrash />
              <span>删除</span>
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- Empty State -->
    <EmptyState v-if="!loading && goodsList.length === 0" title="暂无商品数据" />
  </div>
</template>

<style scoped>
/* ============================================================
   Shared Tokens
   ============================================================ */
.card-list,
.table-container {
  --c-bg: transparent;
  --c-surface: var(--bg-elevated);
  --c-surface-hover: var(--bg-elevated);
  --c-border: var(--border-1);
  --c-border-strong: var(--border-2);
  --c-text-1: var(--text-1);
  --c-text-2: var(--text-2);
  --c-text-3: var(--text-2);
  --c-accent: var(--brand-600);
  --c-danger: var(--danger-500);
  --c-success: var(--success-500);
  --c-price: var(--danger-500);
  --c-r-sm: 10px;
  --c-r-md: 14px;
  --c-ease: 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
}

/* ============================================================
   Mobile Card View
   ============================================================ */
.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  padding-bottom: 24px;
  min-height: 100%;
}

.goods-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--c-r-md);
  overflow: hidden;
  transition: all var(--c-ease);
  box-shadow: 0 8px 32px rgba(0,0,0,0.10), 0 1.5px 4px rgba(0,0,0,0.06);
  cursor: pointer;
  }

@media (hover: hover) {
  .goods-card:hover {
    box-shadow: 0 12px 40px rgba(0,0,0,0.14), 0 2px 6px rgba(0,0,0,0.08);
    border-color: var(--border-1);
  }
}

.goods-card:active {
  transform: scale(0.98);
}

.goods-card__image-wrap {
  position: relative;
  width: 100%;
  height: 220px;
  background: var(--bg-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.goods-card__image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.goods-card__image-placeholder {
  color: var(--c-text-3);
  opacity: 0.3;
}

.goods-card__image-placeholder svg {
  width: 32px;
  height: 32px;
}

.goods-card__status {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  line-height: 1;
  z-index: 2;
}

/* 玻璃渐变悬浮层 */
.goods-card__overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 1;
  background: rgba(15, 23, 42, 0.45);
  -webkit-mask-image: linear-gradient(to bottom, transparent 0%, black 30%);
  mask-image: linear-gradient(to bottom, transparent 0%, black 30%);
}

/* 渐变过渡区：标题区域 */
.goods-card__title-wrap {
  padding: 24px 12px 6px;
}

.goods-card__title {
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
}

/* 底部价格 + 按钮行 */
.goods-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px 12px;
}

.goods-card__price {
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
}

.goods-card__id {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.6);
  font-family: 'SF Mono', 'Menlo', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-card__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.goods-card__switches {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}

.goods-card__switch {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 8px;
  font-size: 11px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.75);
  background: rgba(255, 255, 255, 0.15);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--c-ease);
  -webkit-tap-highlight-color: transparent;
  }

.goods-card__switch svg {
  width: 12px;
  height: 12px;
}

.goods-card__switch--on {
  color: #fff;
  background: rgba(52, 199, 89, 0.5);
  border-color: var(--border-1);
}

/* 底部操作栏 */
.goods-card__actions {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--c-border-strong);
  background: transparent;
}

.goods-card__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex: 1;
  height: 32px;
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--c-r-sm);
  border: 1px solid;
  cursor: pointer;
  transition: all var(--c-ease);
  -webkit-tap-highlight-color: transparent;
  background: transparent;
}

.goods-card__action svg {
  width: 13px;
  height: 13px;
}

.goods-card__action--config {
  color: var(--c-accent);
  border-color: var(--border-1);
}

.goods-card__action--edit {
  color: var(--c-text-1);
  border-color: var(--c-border-strong);
}

@media (hover: hover) {
  .goods-card__action--edit:hover {
    background: rgba(28, 28, 30, 0.06);
  }
}

@media (hover: hover) {
  .goods-card__action--config:hover {
    background: rgba(0, 122, 255, 0.06);
  }
}

.goods-card__action--delete {
  color: var(--c-danger);
  border-color: var(--border-1);
}

@media (hover: hover) {
  .goods-card__action--delete:hover {
    background: rgba(255, 59, 48, 0.06);
  }
}

.goods-card__action:active {
  transform: scale(0.97);
}

/* ============================================================
   Desktop Table View
   ============================================================ */
.table-container {
  min-height: 100%;
}

.table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
}

/* Table Head */
.table__head {
  position: sticky;
  top: 0;
  z-index: 10;
}

.table__th {
  text-align: left;
  padding: 12px 16px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-1);
  letter-spacing: 0;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border-1);
  white-space: nowrap;
  user-select: none;
}

.table__th--image { width: 64px; }
.table__th--price { width: 100px; text-align: right; }
.table__th--sku { width: 70px; text-align: center; }
.table__th--status { width: 80px; }
.table__th--switch { width: 90px; text-align: center; }
.table__th--actions { width: 130px; text-align: center; }

/* Table Body */
.table__tr {
  transition: background var(--c-ease);
}

.table__tr:not(:last-child) .table__td {
  border-bottom: 1px solid var(--c-border-strong);
}

@media (hover: hover) {
  .table__tr:hover .table__td {
    background: var(--bg-muted);
  }
}

.table__td {
  padding: 10px 16px;
  color: var(--c-text-1);
  white-space: nowrap;
  background: transparent;
  transition: background var(--c-ease);
  line-height: 1.5;
}

/* Thumbnail */
.goods-thumb {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-xs);
  overflow: hidden;
  background: var(--bg-muted);
  display: flex;
  align-items: center;
  justify-content: center;
}

.goods-thumb__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  cursor: pointer;
  transition: transform var(--c-ease);
}

@media (hover: hover) {
  .goods-thumb__img:hover {
    transform: scale(1.08);
  }
}

.goods-thumb__placeholder {
  color: var(--c-text-3);
  opacity: 0.3;
}

.goods-thumb__placeholder svg {
  width: 18px;
  height: 18px;
}

/* Title Cell */
.table__td--title {
  cursor: pointer;
}

.goods-title-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.goods-title-cell__name {
  font-weight: 500;
  color: var(--c-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.goods-title-cell__id {
  font-size: 11px;
  color: var(--c-text-3);
  font-family: 'SF Mono', 'Menlo', monospace;
}

/* Price */
.table__td--price {
  text-align: right;
}

/* SKU Count */
.table__td--sku {
  text-align: center;
}

.sku-count-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  color: var(--warning-500);
  background: rgba(255, 149, 0, 0.1);
  white-space: nowrap;
}

.sku-count-tag--single {
  color: var(--c-text-3);
  background: transparent;
  font-weight: 400;
}

.price-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-price);
  font-variant-numeric: tabular-nums;
}

/* Status */
.table__td--status {}

.status-tag {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: var(--radius-sm);
  line-height: 1;
}

/* Toggle Switch */
.table__td--switch {
  text-align: center;
}

.toggle-btn {
  display: inline-flex;
  align-items: center;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  -webkit-tap-highlight-color: transparent;
}

.toggle-btn__track {
  width: 44px;
  height: 26px;
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
  position: relative;
  transition: background var(--c-ease);
  display: block;
}

.toggle-btn--on .toggle-btn__track {
  background: var(--c-success);
}

.toggle-btn__thumb {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.15);
  position: absolute;
  top: 2px;
  left: 2px;
  transition: transform var(--c-ease);
}

.toggle-btn--on .toggle-btn__thumb {
  transform: translateX(18px);
}

/* Action Buttons in Table */
.table__td--actions {
  text-align: center;
}

.table__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 30px;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  border: none;
  cursor: pointer;
  transition: all var(--c-ease);
  -webkit-tap-highlight-color: transparent;
  background: transparent;
}

.table__action svg {
  width: 13px;
  height: 13px;
}

.table__action--config {
  color: var(--c-accent);
}

@media (hover: hover) {
  .table__action--config:hover {
    background: rgba(0, 122, 255, 0.08);
  }
}

.table__action--detail {
  color: var(--c-success);
}

@media (hover: hover) {
  .table__action--detail:hover {
    background: rgba(52, 199, 89, 0.08);
  }
}

.table__action--sync {
  color: var(--c-accent);
}

@media (hover: hover) {
  .table__action--sync:hover {
    background: rgba(0, 122, 255, 0.08);
  }
}

.table__action--delete {
  color: var(--c-danger);
}

@media (hover: hover) {
  .table__action--delete:hover {
    background: rgba(255, 59, 48, 0.08);
  }
}

.table__action:active {
  transform: scale(0.95);
}

/* ============================================================
   Dark Theme Overrides
   ============================================================ */
[data-theme="dark"] .card-list,
[data-theme="dark"] .table-container {
  --c-surface: rgba(30, 41, 59, 0.65);
  --c-surface-hover: rgba(51, 65, 85, 0.75);
  --c-border: var(--border-1);
  --c-border-strong: var(--border-2);
  --c-text-1: var(--text-1);
  --c-text-2: var(--text-2);
  --c-text-3: var(--text-3);
}

[data-theme="dark"] .table__th {
  color: var(--neon-cyan);
  background: rgba(13, 22, 38, 0.92);
  border-bottom: 1px solid rgba(0, 242, 254, 0.22);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

[data-theme="dark"] .table__tr:hover .table__td {
  background: rgba(0, 242, 254, 0.04);
}

[data-theme="dark"] .goods-thumb {
  border: 1px solid rgba(56, 189, 248, 0.2);
  box-shadow: 0 0 10px rgba(0, 242, 254, 0.08);
  background: rgba(255, 255, 255, 0.04);
}

[data-theme="dark"] .toggle-btn__track {
  background: rgba(255, 255, 255, 0.18);
}

[data-theme="dark"] .goods-card__image-wrap,
[data-theme="dark"] .goods-thumb {
  background: rgba(255, 255, 255, 0.04);
}

/* ============================================================
   Loading State
   ============================================================ */
.card-list--loading,
.table-container--loading {
  opacity: 0.5;
  pointer-events: none;
}

/* ============================================================
   Responsive
   ============================================================ */
@media screen and (max-width: 480px) {
  .card-list {
    padding: 12px;
    gap: 8px;
  }

  .goods-card__image-wrap {
    height: 190px;
  }

  .goods-card__title {
    font-size: 12px;
  }

  .goods-card__price {
    font-size: 15px;
  }

  .goods-card__switch {
    height: 24px;
    padding: 0 7px;
    font-size: 11px;
  }

  .goods-card__action {
    height: 30px;
    font-size: 11px;
  }
}

/* ============================================================
   Delivery Type Tag
   ============================================================ */
.delivery-type-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  white-space: nowrap;
}

.delivery-type-tag--text {
  color: var(--c-accent);
  background: rgba(0, 122, 255, 0.08);
}

.delivery-type-tag--kami {
  color: var(--purple-500);
  background: rgba(155, 89, 182, 0.08);
}

.delivery-type-tag--off {
  color: var(--c-text-3);
}

.reply-mode-tags {
  display: flex;
  gap: 4px;
  justify-content: center;
  flex-wrap: wrap;
}

.reply-mode-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  white-space: nowrap;
}

.reply-mode-tag--ai {
  color: var(--brand-600);
  background: rgba(0, 122, 255, 0.1);
}

.reply-mode-tag--keyword {
  color: var(--success-500);
  background: rgba(52, 199, 89, 0.1);
}

.reply-mode-tag--delivery {
  color: var(--purple-500);
  background: rgba(175, 82, 222, 0.1);
}

.reply-mode-tag--off {
  color: var(--c-text-3);
}

.goods-card__mode-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 5px;
  font-size: 10px;
  font-weight: 500;
  border-radius: 3px;
  white-space: nowrap;
}

.goods-card__mode-tag--ai {
  color: var(--brand-600);
  background: rgba(0, 122, 255, 0.1);
}

.goods-card__mode-tag--keyword {
  color: var(--success-500);
  background: rgba(52, 199, 89, 0.1);
}

.goods-card__mode-tag--delivery {
  color: var(--purple-500);
  background: rgba(175, 82, 222, 0.1);
}

.goods-card__tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.goods-card__type-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  white-space: nowrap;
}

.goods-card__type-tag--text {
  color: var(--c-accent);
  background: rgba(0, 122, 255, 0.1);
}

.goods-card__type-tag--kami {
  color: var(--purple-500);
  background: rgba(155, 89, 182, 0.1);
}
/* ============================================================
   Liquid Glass Table Surfaces
   ============================================================ */
.card-list,
.table-container {
  --c-surface: var(--glass-bg-soft);
  --c-surface-hover: color-mix(in srgb, var(--glass-highlight) 70%, var(--bg-hover));
  --c-border: var(--glass-border);
  --c-border-strong: var(--glass-border-strong);
}

.goods-card {
  background: var(--glass-bg-soft);
  border-color: var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

@media (hover: hover) {
  .goods-card:hover {
    border-color: var(--glass-border-strong);
    box-shadow: var(--glass-shadow-float);
  }
}

.goods-card__image-wrap,
.goods-thumb {
  background: color-mix(in srgb, var(--glass-bg-soft) 72%, transparent);
}

.goods-card__status,
.status-tag {
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

.table-container {
  background: transparent;
}

.table__head {
  z-index: 11;
}

.table__th {
  color: var(--text-2);
  background: color-mix(in srgb, var(--glass-bg-strong) 88%, var(--bg-muted));
  border-bottom-color: var(--glass-border);
  box-shadow: inset 0 1px 0 var(--glass-highlight-strong);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.table__tr:not(:last-child) .table__td {
  border-bottom-color: var(--glass-border);
}

.table__td {
  background: transparent;
}

@media (hover: hover) {
  .table__tr:hover .table__td {
    background: linear-gradient(90deg, color-mix(in srgb, var(--brand-500) 12%, transparent), color-mix(in srgb, var(--glass-highlight) 70%, var(--bg-hover)) 26%, color-mix(in srgb, var(--glass-highlight) 70%, var(--bg-hover)));
    box-shadow: inset 2px 0 0 color-mix(in srgb, var(--brand-500) 72%, transparent);
  }
}

.table__action,
.goods-card__action {
  background: color-mix(in srgb, var(--glass-bg-soft) 78%, transparent);
  border-color: var(--glass-border);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

@media (hover: hover) {
  .table__action--config:hover,
  .table__action--sync:hover,
  .goods-card__action--config:hover,
  .goods-card__action--edit:hover {
    background: color-mix(in srgb, var(--brand-500) 12%, var(--glass-bg-strong));
    border-color: color-mix(in srgb, var(--brand-500) 34%, transparent);
  }

  .table__action--detail:hover {
    background: color-mix(in srgb, var(--success-500) 12%, var(--glass-bg-strong));
    border-color: color-mix(in srgb, var(--success-500) 34%, transparent);
  }

  .table__action--delete:hover,
  .goods-card__action--delete:hover {
    background: color-mix(in srgb, var(--danger-500) 12%, var(--glass-bg-strong));
    border-color: color-mix(in srgb, var(--danger-500) 34%, transparent);
  }
}

.toggle-btn__track {
  background: color-mix(in srgb, var(--glass-bg-strong) 82%, var(--bg-hover));
  border: 1px solid var(--glass-border);
}

.delivery-type-tag--text,
.reply-mode-tag--ai,
.goods-card__mode-tag--ai,
.goods-card__type-tag--text {
  background: color-mix(in srgb, var(--brand-500) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--brand-500) 22%, transparent);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

.delivery-type-tag--kami,
.reply-mode-tag--delivery,
.goods-card__mode-tag--delivery,
.goods-card__type-tag--kami {
  background: color-mix(in srgb, var(--purple-500) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--purple-500) 22%, transparent);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

.reply-mode-tag--keyword,
.goods-card__mode-tag--keyword {
  background: color-mix(in srgb, var(--success-500) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--success-500) 22%, transparent);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

@media (prefers-reduced-transparency: reduce) {
  .goods-card,
  .table__th,
  .table__action,
  .goods-card__action,
  .toggle-btn__track {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

</style>
