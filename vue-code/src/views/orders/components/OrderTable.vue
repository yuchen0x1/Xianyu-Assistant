<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { formatTime } from '@/utils'
import { showError } from '@/utils'
import { getOrderDetail } from '@/api/order'
import type { DeliveryRecordItem } from '../useOrderManager'

import IconEmpty from '@/components/icons/IconEmpty.vue'
import IconCopy from '@/components/icons/IconCopy.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconUser from '@/components/icons/IconUser.vue'
import IconClock from '@/components/icons/IconClock.vue'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconEye from '@/components/icons/IconImage.vue'

interface Props {
  orderList: DeliveryRecordItem[]
  loading?: boolean
  selectedIds?: number[]
  selectable?: boolean
  allSelected?: boolean
  indeterminate?: boolean
}

interface Emits {
  (e: 'copySid', sid: string): void
  (e: 'confirmShipment', item: DeliveryRecordItem): void
  (e: 'viewDetail', item: DeliveryRecordItem): void
  (e: 'toggleSelect', id: number): void
  (e: 'toggleSelectAll'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const isSelected = (id: number) => props.selectedIds?.includes(id) ?? false

const detailVisible = ref(false)
const detailLoading = ref(false)
interface OrderDetailData {
  // 本地记录字段（state: 1成功 / 0待核对 / -1失败）
  state?: number
  failReason?: string
  confirmState?: number
  createTime?: string
  orderId?: string
  goodsTitle?: string
  buyerUserName?: string
  content?: string
  consignTime?: string
  orderCreateTime?: string
  paySuccessTime?: string
  totalPrice?: string
  buyNum?: number
  // 服务端订单详情解析字段（detailFromServer=true 时存在）
  module?: OrderDetailModule
}
interface OrderDetailModule {
  merchantCommonData?: { orderId?: string; orderStatus?: string; createTime?: string; paySuccessTime?: string }
  merchantItemVO?: { title?: string }
  merchantPriceVO?: { totalPrice?: string; buyNum?: number }
  merchantBuyerVO?: { userNick?: string }
}
const detailData = ref<OrderDetailData | null>(null)
const detailSkuText = ref('')
const detailFromServer = ref(false)

const handleViewDetail = async (order: DeliveryRecordItem, fromServer: boolean = false) => {
  if (!order.orderId || !order.xianyuAccountId) return
  detailLoading.value = true
  detailVisible.value = true
  detailData.value = null
  detailSkuText.value = ''
  detailFromServer.value = fromServer
  try {
    const res = await getOrderDetail({ xianyuAccountId: order.xianyuAccountId, orderId: order.orderId, fromServer })
    if (res.code === 200 || res.code === 0) {
      const parsed = JSON.parse(res.data || '{}')
      detailData.value = parsed

      if (fromServer) {
        const module = parsed.module || {}
        const orderInfoVO = module.orderInfoVO || {}
        const itemInfo = orderInfoVO.itemInfo || {}
        const merchantItemVO = module.merchantItemVO || {}
        const itemInfoLines = (merchantItemVO.itemInfoLines || []) as Array<{ key?: string; value?: string }>
        const specLine = itemInfoLines.find((l) => l.key === '规格')
        const skuInfo = itemInfo.skuInfo || ''
        if (skuInfo || specLine) {
          detailSkuText.value = specLine?.value || skuInfo.split(':').pop() || ''
        } else {
          detailSkuText.value = ''
        }
      } else {
        detailSkuText.value = parsed.skuName || ''
      }
    } else {
      showError(res.msg || '获取订单详情失败')
      detailVisible.value = false
    }
  } catch {
    showError('获取订单详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

let clickTimer: ReturnType<typeof setTimeout> | null = null
let lastClickTime = 0
const handleClickDetail = (order: DeliveryRecordItem) => {
  const now = Date.now()
  if (clickTimer) {
    clearTimeout(clickTimer)
    clickTimer = null
  }
  if (now - lastClickTime < 300) {
    handleViewDetail(order, true)
  } else {
    clickTimer = setTimeout(() => {
      handleViewDetail(order, false)
      clickTimer = null
    }, 300)
  }
  lastClickTime = now
}

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

const getDeliveryText = (state: number) => {
  if (state === 1) return '已发货'
  if (state === 0) return '待核对'
  return '失败'
}

const getDeliveryColor = (state: number) => {
  if (state === 1) return 'var(--success-500)'
  if (state === 0) return 'var(--warning-500)'
  return 'var(--danger-500)'
}

const getDeliveryBg = (state: number) => {
  if (state === 1) return 'var(--success-50)'
  if (state === 0) return 'rgba(255,159,10,.18)'
  return 'var(--danger-50)'
}

const getConfirmText = (state: number) => {
  return state === 1 ? '已确认' : '未确认'
}

const getConfirmColor = (state: number) => {
  return state === 1 ? 'var(--success-500)' : 'var(--text-2)'
}

const getConfirmBg = (state: number) => {
  return state === 1 ? 'var(--success-50)' : 'var(--bg-hover)'
}
</script>

<template>
  <div v-if="isMobile" class="card-list" :class="{ 'card-list--loading': loading }">
    <div
      v-for="order in orderList"
      :key="order.id"
      class="order-card"
    >
      <div class="order-card__header">
        <label v-if="selectable" class="checkbox" @click.stop>
          <input
            type="checkbox"
            :checked="isSelected(order.id)"
            @change="emit('toggleSelect', order.id)"
          />
          <span class="checkbox__box"></span>
        </label>
        <span class="order-card__id">{{ order.orderId || '-' }}</span>
        <div class="order-card__status-group">
          <span
            class="order-card__status"
            :style="{
              color: getDeliveryColor(order.state),
              background: getDeliveryBg(order.state)
            }"
          >
            {{ getDeliveryText(order.state) }}
          </span>
          <span v-if="order.state !== 1 && order.failReason" class="order-card__fail-reason">{{ order.failReason }}</span>
          <span
            class="order-card__status"
            :style="{
              color: getConfirmColor(order.confirmState || 0),
              background: getConfirmBg(order.confirmState || 0)
            }"
          >
            {{ getConfirmText(order.confirmState || 0) }}
          </span>
        </div>
      </div>

      <div class="order-card__body">
        <div class="order-card__row">
          <IconShoppingBag />
          <span class="order-card__label">商品</span>
          <span class="order-card__value text-ellipsis-10" :title="order.goodsTitle || '-'">{{ order.goodsTitle || '-' }}</span>
        </div>
        <div v-if="order.skuName" class="order-card__row">
          <span class="order-card__label"></span>
          <span class="order-card__label">规格</span>
          <span class="order-card__value order-card__sku">{{ order.skuName }}</span>
        </div>
        <div class="order-card__row">
          <IconUser />
          <span class="order-card__label">买家</span>
          <span class="order-card__value">{{ order.buyerUserName || '-' }}</span>
        </div>
        <div class="order-card__row">
          <IconClock />
          <span class="order-card__label">下单时间</span>
          <span class="order-card__value">{{ formatTime(order.orderCreateTime || order.createTime) }}</span>
        </div>
      </div>

      <div class="order-card__footer">
        <button class="order-card__action order-card__action--copy" @click="emit('copySid', order.orderId || '')">
          <IconCopy />
          <span>复制订单ID</span>
        </button>
        <button
          v-if="order.orderId"
          class="order-card__action order-card__action--detail"
          @click="handleClickDetail(order)"
        >
          <IconEye />
          <span>详情</span>
          <span class="detail-tooltip">单击查询本地，双击查询闲鱼服务器</span>
        </button>
        <button
          v-if="order.orderId"
          class="order-card__action order-card__action--ship"
          :class="{ 'order-card__action--loading': order.confirming }"
          @click="emit('confirmShipment', order)"
        >
          <IconTruck />
          <span>{{ order.confirming ? '处理中' : '确认发货' }}</span>
        </button>
      </div>
    </div>

    <div v-if="!loading && orderList.length === 0" class="empty-state">
      <div class="empty-state__icon"><IconEmpty /></div>
      <p class="empty-state__text">暂无发货记录</p>
    </div>
  </div>

  <div v-else class="table-container" :class="{ 'table-container--loading': loading }">
    <table class="table" v-if="orderList.length > 0">
      <thead class="table__head">
        <tr>
          <th v-if="selectable" class="table__th table__th--checkbox">
            <label class="checkbox" @click.stop>
              <input
                type="checkbox"
                :checked="allSelected"
                :indeterminate="indeterminate"
                @change="emit('toggleSelectAll')"
              />
              <span class="checkbox__box"></span>
            </label>
          </th>
          <th class="table__th">订单ID</th>
          <th class="table__th">商品名称</th>
          <th class="table__th table__th--center">规格</th>
          <th class="table__th table__th--center">买家</th>
          <th class="table__th table__th--center">发货内容</th>
          <th class="table__th table__th--center">发货状态</th>
          <th class="table__th table__th--center">确认状态</th>
          <th class="table__th table__th--center">下单时间</th>
          <th class="table__th table__th--actions">操作</th>
        </tr>
      </thead>
      <tbody class="table__body">
        <tr v-for="order in orderList" :key="order.id" class="table__tr">
          <td v-if="selectable" class="table__td table__td--checkbox" @click.stop>
            <label class="checkbox">
              <input
                type="checkbox"
                :checked="isSelected(order.id)"
                @change="emit('toggleSelect', order.id)"
              />
              <span class="checkbox__box"></span>
            </label>
          </td>
          <td class="table__td">
            <span class="order-id">{{ order.orderId || '-' }}</span>
          </td>
          <td class="table__td table__td--title">
            <div class="order-title-cell">
              <span class="order-title-cell__name text-ellipsis-10" :title="order.goodsTitle || '-'">{{ order.goodsTitle || '-' }}</span>
            </div>
          </td>
          <td class="table__td table__td--center">
            <span v-if="order.skuName" class="sku-name-tag">{{ order.skuName }}</span>
            <span v-else class="table__td-placeholder">-</span>
          </td>
          <td class="table__td table__td--center">
            <span class="buyer-name">{{ order.buyerUserName || '-' }}</span>
          </td>
          <td class="table__td">
            <span class="content-text text-ellipsis-10" :title="order.content || '-'">{{ order.content || '-' }}</span>
          </td>
          <td class="table__td table__td--center">
            <span
              class="status-tag"
              :style="{
                color: getDeliveryColor(order.state),
                background: getDeliveryBg(order.state)
              }"
            >
              {{ getDeliveryText(order.state) }}
            </span>
            <span v-if="order.state !== 1 && order.failReason" class="fail-reason" :title="order.failReason">{{ order.failReason }}</span>
          </td>
          <td class="table__td table__td--center">
            <span
              class="status-tag"
              :style="{
                color: getConfirmColor(order.confirmState || 0),
                background: getConfirmBg(order.confirmState || 0)
              }"
            >
              {{ getConfirmText(order.confirmState || 0) }}
            </span>
          </td>
          <td class="table__td table__td--center">
            <span class="time-text">{{ formatTime(order.orderCreateTime || order.createTime) }}</span>
          </td>
          <td class="table__td table__td--actions">
            <button
              v-if="order.orderId"
              class="table__action table__action--detail"
              @click="handleClickDetail(order)"
            >
              <IconEye />
              <span>详情</span>
              <span class="detail-tooltip">单击查询本地，双击查询闲鱼服务器</span>
            </button>
            <button
              v-if="order.orderId"
              class="table__action table__action--ship"
              :class="{ 'table__action--loading': order.confirming }"
              @click="emit('confirmShipment', order)"
            >
              <IconTruck />
              <span>{{ order.confirming ? '处理中' : '确认发货' }}</span>
            </button>
            <span v-if="!order.orderId" class="table__action-placeholder">-</span>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="!loading && orderList.length === 0" class="empty-state">
      <div class="empty-state__icon"><IconEmpty /></div>
      <p class="empty-state__text">暂无发货记录</p>
    </div>
  </div>

  <!-- Order Detail Dialog -->
  <Transition name="overlay-fade">
    <div v-if="detailVisible" class="detail-overlay" @click.self="detailVisible = false">
      <div class="detail-dialog">
        <div class="detail-dialog__header">
          <h3 class="detail-dialog__title">订单详情</h3>
          <button class="detail-dialog__close" @click="detailVisible = false">&times;</button>
        </div>
        <div class="detail-dialog__body">
          <div v-if="detailLoading" class="detail-dialog__loading">
            <div class="detail-dialog__spinner"></div>
            <span>加载中...</span>
          </div>
          <template v-else-if="detailData">
            <div class="detail-dialog__section">
              <div class="detail-dialog__rows">
                <template v-if="detailFromServer && detailData.module">
                  <div v-if="detailData.module.merchantCommonData?.orderId" class="detail-dialog__row">
                    <span class="detail-dialog__label">订单ID</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantCommonData.orderId }}</span>
                  </div>
                  <div v-if="detailData.module.merchantCommonData?.orderStatus" class="detail-dialog__row">
                    <span class="detail-dialog__label">状态</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantCommonData.orderStatus }}</span>
                  </div>
                  <div v-if="detailData.module.merchantCommonData?.createTime" class="detail-dialog__row">
                    <span class="detail-dialog__label">下单时间</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantCommonData.createTime }}</span>
                  </div>
                  <div v-if="detailData.module.merchantCommonData?.paySuccessTime" class="detail-dialog__row">
                    <span class="detail-dialog__label">付款时间</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantCommonData.paySuccessTime }}</span>
                  </div>
                  <div v-if="detailData.module.merchantItemVO?.title" class="detail-dialog__row">
                    <span class="detail-dialog__label">商品</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantItemVO.title }}</span>
                  </div>
                  <div v-if="detailSkuText" class="detail-dialog__row detail-dialog__row--highlight">
                    <span class="detail-dialog__label">规格</span>
                    <span class="detail-dialog__value detail-dialog__sku">{{ detailSkuText }}</span>
                  </div>
                  <div v-if="detailData.module.merchantPriceVO?.totalPrice" class="detail-dialog__row">
                    <span class="detail-dialog__label">金额</span>
                    <span class="detail-dialog__value">¥{{ detailData.module.merchantPriceVO?.totalPrice }}</span>
                  </div>
                  <div v-if="detailData.module.merchantPriceVO?.buyNum" class="detail-dialog__row">
                    <span class="detail-dialog__label">数量</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantPriceVO?.buyNum }}</span>
                  </div>
                  <div v-if="detailData.module.merchantBuyerVO?.userNick" class="detail-dialog__row">
                    <span class="detail-dialog__label">买家</span>
                    <span class="detail-dialog__value">{{ detailData.module.merchantBuyerVO?.userNick }}</span>
                  </div>
                </template>
                <template v-else-if="!detailFromServer">
                  <div class="detail-dialog__tag detail-dialog__tag--local">本地数据</div>
                  <div v-if="detailData.orderId" class="detail-dialog__row">
                    <span class="detail-dialog__label">订单ID</span>
                    <span class="detail-dialog__value">{{ detailData.orderId }}</span>
                  </div>
                  <div v-if="detailData.goodsTitle" class="detail-dialog__row">
                    <span class="detail-dialog__label">商品</span>
                    <span class="detail-dialog__value">{{ detailData.goodsTitle }}</span>
                  </div>
                  <div v-if="detailSkuText" class="detail-dialog__row detail-dialog__row--highlight">
                    <span class="detail-dialog__label">规格</span>
                    <span class="detail-dialog__value detail-dialog__sku">{{ detailSkuText }}</span>
                  </div>
                  <div v-if="detailData.orderCreateTime" class="detail-dialog__row">
                    <span class="detail-dialog__label">下单时间</span>
                    <span class="detail-dialog__value">{{ detailData.orderCreateTime }}</span>
                  </div>
                  <div v-if="detailData.paySuccessTime" class="detail-dialog__row">
                    <span class="detail-dialog__label">付款时间</span>
                    <span class="detail-dialog__value">{{ detailData.paySuccessTime }}</span>
                  </div>
                  <div v-if="detailData.totalPrice" class="detail-dialog__row">
                    <span class="detail-dialog__label">金额</span>
                    <span class="detail-dialog__value">¥{{ detailData.totalPrice }}</span>
                  </div>
                  <div v-if="detailData.buyNum" class="detail-dialog__row">
                    <span class="detail-dialog__label">数量</span>
                    <span class="detail-dialog__value">{{ detailData.buyNum }}</span>
                  </div>
                  <div v-if="detailData.buyerUserName" class="detail-dialog__row">
                    <span class="detail-dialog__label">买家</span>
                    <span class="detail-dialog__value">{{ detailData.buyerUserName }}</span>
                  </div>
                  <div v-if="detailData.consignTime" class="detail-dialog__row">
                    <span class="detail-dialog__label">发货时间</span>
                    <span class="detail-dialog__value">{{ detailData.consignTime }}</span>
                  </div>
                  <div v-if="detailData.content" class="detail-dialog__row">
                    <span class="detail-dialog__label">发货内容</span>
                    <span class="detail-dialog__value detail-dialog__content">{{ detailData.content }}</span>
                  </div>
                  <div class="detail-dialog__row">
                    <span class="detail-dialog__label">发货状态</span>
                    <span class="detail-dialog__value" :style="{ color: detailData.state === 1 ? 'var(--success-500)' : 'var(--danger-500)' }">{{ detailData.state === 1 ? '成功' : '失败' }}</span>
                  </div>
                  <div v-if="detailData.failReason" class="detail-dialog__row">
                    <span class="detail-dialog__label">失败原因</span>
                    <span class="detail-dialog__value detail-dialog__fail">{{ detailData.failReason }}</span>
                  </div>
                  <div class="detail-dialog__row">
                    <span class="detail-dialog__label">确认状态</span>
                    <span class="detail-dialog__value">{{ detailData.confirmState === 1 ? '已确认' : '未确认' }}</span>
                  </div>
                  <div v-if="detailData.createTime" class="detail-dialog__row">
                    <span class="detail-dialog__label">记录时间</span>
                    <span class="detail-dialog__value">{{ detailData.createTime }}</span>
                  </div>
                </template>
                <div v-if="detailFromServer && !detailData.module" class="detail-dialog__raw">
                  <pre>{{ JSON.stringify(detailData, null, 2) }}</pre>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="detail-dialog__empty">暂无数据</div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.overlay-fade-enter-active,
.overlay-fade-leave-active {
  transition: opacity 0.2s ease;
}
.overlay-fade-enter-from,
.overlay-fade-leave-to {
  opacity: 0;
}
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
  --c-r-sm: 10px;
  --c-r-md: 14px;
  --c-ease: 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  padding-bottom: 24px;
  min-height: 100%;
}

.order-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--c-r-md);
  overflow: hidden;
  transition: all var(--c-ease);
  box-shadow: 0 8px 32px rgba(0,0,0,0.10), 0 1.5px 4px rgba(0,0,0,0.06);
  }

.order-card__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 0.5px solid var(--c-border-strong);
}

.order-card__id {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-1);
  font-family: 'SF Mono', 'Menlo', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 60%;
}

.order-card__status-group {
  display: flex;
  gap: 4px;
}

.order-card__status {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  line-height: 1;
  flex-shrink: 0;
}

.order-card__body {
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.order-card__row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  min-height: 20px;
}

.order-card__row svg {
  width: 13px;
  height: 13px;
  color: var(--c-text-3);
  flex-shrink: 0;
}

.order-card__label {
  color: var(--c-text-3);
  flex-shrink: 0;
  min-width: 32px;
}

.order-card__value {
  color: var(--c-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-card__footer {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  border-top: 0.5px solid var(--c-border-strong);
}

.order-card__action {
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

.order-card__action svg {
  width: 13px;
  height: 13px;
}

.order-card__action--copy {
  color: var(--c-accent);
  border-color: var(--border-1);
}

@media (hover: hover) {
  .order-card__action--copy:hover {
    background: rgba(0, 122, 255, 0.06);
  }
}

.order-card__action--ship {
  color: var(--c-success);
  border-color: var(--border-1);
}

@media (hover: hover) {
  .order-card__action--ship:hover {
    background: rgba(52, 199, 89, 0.06);
  }
}

.order-card__action--loading {
  opacity: 0.6;
  pointer-events: none;
}

.order-card__action:active {
  transform: scale(0.97);
}

.table-container {
  min-height: 100%;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

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

.table__th--center {
  text-align: center;
}

.table__th--actions {
  width: 100px;
  text-align: center;
}

.table__th--checkbox,
.table__td--checkbox {
  width: 44px;
  text-align: center;
  padding-left: 12px;
  padding-right: 4px;
}

.checkbox {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  position: relative;
}

.checkbox input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.checkbox__box {
  width: 18px;
  height: 18px;
  border: 1.5px solid var(--border-1);
  border-radius: var(--radius-xs);
  background: var(--bg-elevated);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all var(--c-ease);
  flex-shrink: 0;
}

.checkbox input:checked + .checkbox__box,
.checkbox input:indeterminate + .checkbox__box {
  background: var(--c-accent);
  border-color: var(--c-accent);
}

.checkbox input:checked + .checkbox__box::after {
  content: '';
  width: 9px;
  height: 5px;
  border-left: 2px solid #fff;
  border-bottom: 2px solid #fff;
  transform: rotate(-45deg) translateY(-1px);
}

.checkbox input:indeterminate + .checkbox__box::after {
  content: '';
  width: 8px;
  height: 2px;
  background: var(--bg-elevated);
  border-radius: 1px;
}

.order-card__header .checkbox {
  margin-right: 2px;
}

.table__tr {
  transition: background var(--c-ease);
}

.table__tr:not(:last-child) .table__td {
  border-bottom: 0.5px solid var(--c-border-strong);
}

@media (hover: hover) {
  .table__tr:hover .table__td {
    background: var(--bg-hover);
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

.table__td--center {
  text-align: center;
}

.table__td--actions {
  text-align: center;
}

.order-id {
  font-size: 12px;
  font-family: 'SF Mono', 'Menlo', monospace;
  color: var(--c-text-2);
}

.order-title-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.order-title-cell__name {
  font-weight: 500;
  color: var(--c-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}

.text-ellipsis-10 {
  max-width: 10em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}

.table__td-placeholder {
  color: var(--c-text-3);
}

.buyer-name {
  font-size: 13px;
  color: var(--c-text-2);
}

.content-text {
  font-size: 12px;
  color: var(--c-text-2);
}

.time-text {
  font-size: 12px;
  color: var(--c-text-2);
  font-variant-numeric: tabular-nums;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: var(--radius-sm);
  line-height: 1;
}

.fail-reason {
  display: block;
  font-size: 11px;
  color: var(--danger-500);
  margin-top: 2px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-card__fail-reason {
  display: block;
  font-size: 11px;
  color: var(--danger-500);
  margin-top: 2px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sku-name-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  color: var(--warning-500);
  background: rgba(255, 149, 0, 0.1);
  white-space: nowrap;
}

.order-card__sku {
  font-size: 11px;
  color: var(--warning-500);
}

.table__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 30px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--radius-xs);
  border: 1px solid var(--border-1);
  color: var(--c-success);
  background: transparent;
  cursor: pointer;
  transition: all var(--c-ease);
  -webkit-tap-highlight-color: transparent;
}

.table__action svg {
  width: 13px;
  height: 13px;
}

@media (hover: hover) {
  .table__action--ship:hover {
    background: rgba(52, 199, 89, 0.06);
  }
}

.table__action--detail {
  border-color: var(--border-1);
  color: var(--c-accent);
}

@media (hover: hover) {
  .table__action--detail:hover {
    background: rgba(0, 122, 255, 0.06);
  }
}

.order-card__action--detail {
  color: var(--c-accent);
  border-color: var(--border-1);
  position: relative;
}

.table__action--detail {
  position: relative;
}

.detail-tooltip {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 400;
  color: #fff;
  background: rgba(0, 0, 0, 0.75);
  border-radius: var(--radius-xs);
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s ease;
  z-index: 10;
}

.table__action--detail:hover .detail-tooltip,
.order-card__action--detail:hover .detail-tooltip {
  opacity: 1;
  transition-delay: 0.2s;
}

@media (hover: hover) {
  .order-card__action--detail:hover {
    background: rgba(0, 122, 255, 0.06);
  }
}

.table__action--loading {
  opacity: 0.6;
  pointer-events: none;
}

.table__action:active {
  transform: scale(0.95);
}

.table__action-placeholder {
  color: var(--c-text-3);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 16px;
  gap: 12px;
}

.empty-state__icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-3);
  opacity: 0.35;
}

.empty-state__icon svg {
  width: 36px;
  height: 36px;
}

.empty-state__text {
  font-size: 14px;
  color: var(--c-text-3);
}

.card-list--loading,
.table-container--loading {
  opacity: 0.5;
  pointer-events: none;
}

/* Detail Dialog */
.detail-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(15, 23, 42, 0.45);
  z-index: 950;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.detail-dialog {
  width: 100%;
  max-width: 480px;
  max-height: 80vh;
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  box-shadow: 0 16px 48px rgba(0,0,0,0.16), 0 2px 8px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.detail-dialog__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 0.5px solid var(--c-border-strong);
}

.detail-dialog__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
}

.detail-dialog__close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: var(--text-3);
  background: none;
  border: none;
  cursor: pointer;
  border-radius: var(--radius-xs);
}

.detail-dialog__close:hover {
  background: var(--bg-muted);
}

.detail-dialog__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  min-height: 120px;
}

.detail-dialog__loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 0;
  color: var(--text-3);
  font-size: 13px;
}

.detail-dialog__spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-1);
  border-top-color: var(--brand-600);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.detail-dialog__rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.detail-dialog__row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  font-size: 13px;
}

.detail-dialog__label {
  color: var(--text-3);
  min-width: 60px;
  flex-shrink: 0;
  line-height: 1.5;
}

.detail-dialog__value {
  color: var(--text-1);
  word-break: break-all;
  line-height: 1.5;
}

.detail-dialog__row--highlight .detail-dialog__label,
.detail-dialog__row--highlight .detail-dialog__value {
  font-weight: 600;
}

.detail-dialog__sku {
  color: var(--warning-500);
  background: rgba(255, 149, 0, 0.08);
  padding: 2px 8px;
  border-radius: var(--radius-xs);
  font-size: 12px;
}

.detail-dialog__tag {
  display: inline-block;
  padding: 4px 10px;
  font-size: 11px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  margin-bottom: 12px;
}

.detail-dialog__tag--local {
  color: var(--brand-600);
  background: rgba(0, 122, 255, 0.1);
}

.detail-dialog__content {
  word-break: break-all;
  white-space: pre-wrap;
  font-size: 12px;
  color: var(--c-text-secondary);
}

.detail-dialog__fail {
  color: var(--danger-500);
  font-size: 12px;
}

.detail-dialog__raw {
  overflow-x: auto;
}

.detail-dialog__raw pre {
  font-size: 11px;
  color: var(--text-3);
  white-space: pre-wrap;
  word-break: break-all;
}

.detail-dialog__empty {
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
  padding: 40px 0;
}

@media screen and (max-width: 480px) {
  .card-list {
    padding: 12px;
    gap: 8px;
  }

  .order-card__header {
    padding: 8px 10px;
  }

  .order-card__body {
    padding: 8px 10px;
    gap: 6px;
  }

  .order-card__footer {
    padding: 6px 10px;
  }

  .order-card__action {
    height: 30px;
    font-size: 11px;
  }
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

.order-card {
  background: var(--glass-bg-soft);
  border-color: var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

@media (hover: hover) {
  .order-card:hover {
    border-color: var(--glass-border-strong);
    box-shadow: var(--glass-shadow-float);
  }
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

.checkbox__box {
  background: var(--glass-bg-soft);
  border-color: var(--glass-border-strong);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.status-tag,
.order-card__status {
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

.table__action,
.order-card__action {
  background: color-mix(in srgb, var(--glass-bg-soft) 78%, transparent);
  border-color: var(--glass-border);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

@media (hover: hover) {
  .table__action--ship:hover,
  .order-card__action--ship:hover {
    background: color-mix(in srgb, var(--success-500) 12%, var(--glass-bg-strong));
    border-color: color-mix(in srgb, var(--success-500) 34%, transparent);
  }

  .table__action--detail:hover,
  .order-card__action--copy:hover,
  .order-card__action--detail:hover {
    background: color-mix(in srgb, var(--brand-500) 12%, var(--glass-bg-strong));
    border-color: color-mix(in srgb, var(--brand-500) 34%, transparent);
  }
}

.detail-dialog {
  background: var(--glass-bg-strong);
  border-color: var(--glass-border-strong);
  box-shadow: var(--glass-shadow-float);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.detail-overlay {
  background: rgba(15, 23, 42, 0.42);
  -webkit-backdrop-filter: blur(3px);
  backdrop-filter: blur(3px);
}

@media (prefers-reduced-transparency: reduce) {
  .order-card,
  .table__th,
  .checkbox__box,
  .table__action,
  .order-card__action,
  .detail-dialog,
  .detail-overlay {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

</style>
