<script setup lang="ts">
import { onMounted, ref, computed, inject, defineComponent, h, type Component } from 'vue'
import { accountDisplayName } from '@/utils'
import { useOrderManager, type DeliveryRecordItem } from './useOrderManager'
import { exportDeliveryRecords } from '@/api/order'
import { exportOrders } from '@/api/export'
import { toast } from '@/utils/toast'
import './orders.css'
import '@/styles/header-selectors.css'

import IconClipboard from '@/components/icons/IconClipboard.vue'
import IconSearch from '@/components/icons/IconSearch.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconFilter from '@/components/icons/IconFilter.vue'
import IconDownload from '@/components/icons/IconDownload.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconTruck from '@/components/icons/IconTruck.vue'

import OrderTable from './components/OrderTable.vue'

const goodsPanelCollapsed = ref(true)
const isDesktopCollapsed = computed(() => !isMobile.value && goodsPanelCollapsed.value)

const {
  loading,
  orderList,
  total,
  accounts,
  goodsList,
  goodsTotal,
  goodsLoading,
  goodsListRef,
  onlyOnSale,
  selectedGoodsId,
  queryParams,
  totalPages,
  loadAccounts,
  loadOrders,
  loadGoods,
  handleAccountChange,
  handleReset,
  showManualReviewQueue,
  clearStateFilter,
  handlePageChange,
  copySId,
  handleConfirmShipment,
  selectedOrderIds,
  batchConfirming,
  isAllSelected,
  isIndeterminate,
  toggleSelectOrder,
  toggleSelectAll,
  clearSelection,
  handleBatchConfirmShipment,
  handleGoodsScroll,
  selectGoods,
  toggleOnlyOnSale
} = useOrderManager()

const showFilterSheet = ref(false)
const isMobile = ref(false)

const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

// 导航栏注入
const setHeaderContent = inject<(content: Component | null) => void>('setHeaderContent')

const HeaderSelectors = defineComponent({
  setup() {
    return () => h('div', { class: 'header-selectors' }, [
      h('div', { class: 'header-select-wrap' }, [
        h('select', {
          class: 'header-select',
          onChange: (e: Event) => {
            const val = (e.target as HTMLSelectElement).value
            queryParams.xianyuAccountId = val ? parseInt(val) : undefined
            handleAccountChange()
          }
        }, [
          h('option', { value: '', disabled: true, selected: !queryParams.xianyuAccountId }, '账号'),
          ...accounts.value.map(acc =>
            h('option', {
              value: acc.id.toString(),
              selected: queryParams.xianyuAccountId === acc.id
            }, accountDisplayName(acc))
          )
        ]),
        h(IconChevronDown, { class: 'header-select-icon' })
      ]),
      h('button', {
        class: ['header-refresh-btn', { 'header-refresh-btn--loading': loading.value }],
        disabled: loading.value,
        onClick: loadOrders
      }, [
        h(IconRefresh, { class: 'header-refresh-icon' })
      ]),
      h('button', {
        class: 'header-filter-btn',
        onClick: openFilterSheet
      }, [
        h(IconFilter, { class: 'header-filter-icon' })
      ])
    ])
  }
})

onMounted(async () => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
  if (setHeaderContent) setHeaderContent(HeaderSelectors)
  await loadAccounts()
  if (setHeaderContent) setHeaderContent(HeaderSelectors)
  loadGoods()
  loadOrders()
})

const filterKeyword = ref('')

const openFilterSheet = () => {
  filterKeyword.value = queryParams.keyword || ''
  showFilterSheet.value = true
}

const applyFilter = () => {
  queryParams.keyword = filterKeyword.value || undefined
  queryParams.pageNum = 1
  showFilterSheet.value = false
  loadOrders()
}

const resetFilter = () => {
  filterKeyword.value = ''
  handleReset()
  showFilterSheet.value = false
}

const getPageButtons = () => {
  const buttons: number[] = []
  const maxVisible = 5
  let start = Math.max(1, queryParams.pageNum! - Math.floor(maxVisible / 2))
  const end = Math.min(totalPages.value, start + maxVisible - 1)
  start = Math.max(1, end - maxVisible + 1)
  for (let i = start; i <= end; i++) {
    buttons.push(i)
  }
  return buttons
}

const showConfirmDialog = ref(false)
const confirmTargetOrder = ref<DeliveryRecordItem | null>(null)

const openConfirmDialog = (order: DeliveryRecordItem) => {
  confirmTargetOrder.value = order
  showConfirmDialog.value = true
}

const executeConfirmShipment = async () => {
  if (confirmTargetOrder.value) {
    await handleConfirmShipment(confirmTargetOrder.value)
  }
  showConfirmDialog.value = false
  confirmTargetOrder.value = null
}

// 批量确认发货
const showBatchConfirmDialog = ref(false)

const openBatchConfirmDialog = () => {
  if (!selectedOrderIds.value.length) return
  showBatchConfirmDialog.value = true
}

const executeBatchConfirmShipment = async () => {
  showBatchConfirmDialog.value = false
  await handleBatchConfirmShipment()
}

// 导出发货记录 CSV
const exporting = ref(false)
const handleExport = async () => {
  if (!queryParams.xianyuAccountId) {
    toast.error('请先选择账号')
    return
  }
  exporting.value = true
  try {
    const res = await exportDeliveryRecords({
      xianyuAccountId: queryParams.xianyuAccountId,
      xyGoodsId: queryParams.xyGoodsId,
      keyword: queryParams.keyword,
      state: queryParams.state,
      pageNum: 1,
      pageSize: 10000
    })
    const blob = res.data as Blob
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    const dateStr = new Date().toISOString().slice(0, 10)
    link.download = `delivery_records_${dateStr}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    toast.success('导出成功')
  } catch (e) {
    console.error('导出失败:', e)
    toast.error('导出失败')
  } finally {
    exporting.value = false
  }
}

// 导出订单 Excel
const exportingExcel = ref(false)
const handleExportExcel = async () => {
  exportingExcel.value = true
  try {
    const res = await exportOrders({
      accountId: queryParams.xianyuAccountId,
      status: queryParams.state,
      keyword: queryParams.keyword
    })
    const blob = res.data as Blob
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `orders_${new Date().toISOString().slice(0, 10)}.xlsx`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    toast.success('导出成功')
  } catch (e) {
    console.error('导出失败:', e)
    toast.error('导出失败')
  } finally {
    exportingExcel.value = false
  }
}
</script>

<template>
  <div class="orders">
    <div class="orders__header">
      <div class="orders__title-row">
        <div class="orders__title-icon">
          <IconClipboard />
        </div>
        <h1 class="orders__title">发货记录</h1>
      </div>
      <div class="orders__actions">
        <div class="orders__select-wrap">
          <select
            v-model="queryParams.xianyuAccountId"
            class="orders__select"
            @change="handleAccountChange"
          >
            <option :value="undefined" disabled>选择账号</option>
            <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
              {{ accountDisplayName(acc) }}
            </option>
          </select>
          <span class="orders__select-icon">
            <IconChevronDown />
          </span>
        </div>
        <template v-if="!isMobile">
          <div class="orders__input-wrap">
            <input
              v-model="queryParams.keyword"
              class="orders__input"
              placeholder="商品名称/规格/买家/发货内容"
              @keyup.enter="loadOrders"
            />
          </div>
          <button class="btn btn--primary" @click="loadOrders">
            <IconSearch />
            <span>查询</span>
          </button>
          <button class="btn btn--ghost" @click="handleReset">
            重置
          </button>
          <button class="btn btn--secondary" :disabled="exporting" @click="handleExport">
            <IconDownload />
            <span>{{ exporting ? '导出中...' : '导出' }}</span>
          </button>
          <button class="btn btn--secondary" :disabled="exportingExcel" @click="handleExportExcel">
            <IconDownload />
            <span>{{ exportingExcel ? '导出中...' : '导出Excel' }}</span>
          </button>
          <button
            class="btn btn--review"
            :class="{ 'btn--review-active': queryParams.state === 0 }"
            :aria-pressed="queryParams.state === 0"
            @click="queryParams.state === 0 ? clearStateFilter() : showManualReviewQueue()"
          >
            {{ queryParams.state === 0 ? '查看全部记录' : '待人工核对' }}
          </button>
          <span v-if="total > 0" class="orders__count">
            共 {{ total }} 条
          </span>
        </template>
        <button
          class="btn btn--secondary"
          :class="{ 'btn--loading': loading }"
          :disabled="loading"
          @click="loadOrders"
        >
          <IconRefresh />
          <span class="mobile-hidden">刷新</span>
        </button>
        <button v-if="isMobile" class="btn btn--secondary" @click="openFilterSheet">
          <IconFilter />
          <span>筛选</span>
        </button>
      </div>
    </div>

    <Transition name="overlay-fade">
      <div v-if="selectedOrderIds.length > 0" class="orders__batch-bar">
        <span class="orders__batch-count">
          已选 <strong>{{ selectedOrderIds.length }}</strong> 条
        </span>
        <div class="orders__batch-actions">
          <button class="btn btn--ghost btn--sm" @click="toggleSelectAll">
            {{ isAllSelected ? '取消全选' : '全选本页' }}
          </button>
          <button class="btn btn--ghost btn--sm" @click="clearSelection">取消选择</button>
          <button
            class="btn btn--primary btn--sm"
            :disabled="batchConfirming"
            @click="openBatchConfirmDialog"
          >
            <IconTruck />
            <span>{{ batchConfirming ? '处理中...' : '批量确认发货' }}</span>
          </button>
        </div>
      </div>
    </Transition>

    <div class="orders__body" :class="{ 'orders__body--no-goods': isMobile }">
      <div
        v-if="!isMobile"
        class="orders__goods-panel"
        :class="{ 'orders__goods-panel--collapsed': isDesktopCollapsed }"
      >
        <template v-if="!isDesktopCollapsed">
          <div class="orders__goods-toolbar">
            <span class="orders__goods-toolbar-title">商品列表</span>
            <span v-if="goodsTotal > 0" class="orders__goods-toolbar-count">共 {{ goodsTotal }} 件</span>
            <button class="orders__only-on-sale-btn" :class="{ 'orders__only-on-sale-btn--active': onlyOnSale }" @click="toggleOnlyOnSale">
              {{ onlyOnSale ? '在售' : '全部' }}
            </button>
          </div>

          <div
            class="orders__goods-list"
            ref="goodsListRef"
            @scroll="handleGoodsScroll"
          >
            <div v-if="goodsLoading && goodsList.length === 0" class="orders__loading">
              <div class="orders__spinner"></div>
              <span>加载中...</span>
            </div>

            <div
              v-for="goods in goodsList"
              :key="goods.item.xyGoodId"
              class="orders__goods-item"
              :class="{ 'orders__goods-item--active': selectedGoodsId === goods.item.xyGoodId, 'orders__goods-item--offline': goods.item.status !== 0 }"
              @click="selectGoods(goods)"
            >
              <img
                :src="goods.item.coverPic"
                :alt="goods.item.title"
                class="orders__goods-cover"
              />
              <div class="orders__goods-info">
                <div class="orders__goods-title">{{ goods.item.title }}</div>
                <div class="orders__goods-meta">
                  <span class="orders__goods-price">¥{{ goods.item.soldPrice }}</span>
                  <span
                    class="orders__goods-status"
                    :class="`orders__goods-status--${goods.item.status === 0 ? 'on-sale' : goods.item.status === 1 ? 'off-shelf' : 'sold'}`"
                  >
                    {{ goods.item.status === 0 ? '在售' : goods.item.status === 1 ? '已下架' : goods.item.status === -1 ? '已删除' : '已售出' }}
                  </span>
                </div>
              </div>
            </div>

            <div v-if="goodsLoading && goodsList.length > 0" class="orders__loading">
              <div class="orders__spinner"></div>
              <span>加载中...</span>
            </div>

            <div
              v-if="!goodsLoading && goodsList.length > 0 && goodsList.length >= goodsTotal"
              class="orders__no-more"
            >
              已加载全部
            </div>

            <div v-if="!goodsLoading && goodsList.length === 0" class="orders__goods-empty">
              <IconPackage />
              <span class="orders__goods-empty-text">暂无商品</span>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="orders__goods-icons">
            <div
              v-for="goods in goodsList"
              :key="goods.item.xyGoodId"
              class="orders__goods-icon-item"
              :class="{ 'orders__goods-icon-item--active': selectedGoodsId === goods.item.xyGoodId }"
              :title="goods.item.title"
              @click="selectGoods(goods)"
            >
              <img :src="goods.item.coverPic" class="orders__goods-icon-img" />
            </div>
          </div>
        </template>
        <button
          class="orders__goods-toggle"
          :title="goodsPanelCollapsed ? '展开商品列表' : '折叠商品列表'"
          @click="goodsPanelCollapsed = !goodsPanelCollapsed"
        >
          <svg v-if="goodsPanelCollapsed" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
        </button>
      </div>

      <div class="orders__content">
      <div v-if="queryParams.state === 0" class="orders__review-notice" role="status">
        <strong>待人工核对</strong>
        <span>这些记录未收到平台确认，可能已发送成功。请先打开订单详情核对，系统不会自动重试或释放预占卡密。</span>
      </div>
      <div class="orders__table-wrap">
        <OrderTable
          :order-list="orderList"
          :loading="loading"
          :selected-ids="selectedOrderIds"
          :selectable="true"
          :all-selected="isAllSelected"
          :indeterminate="isIndeterminate"
          @copy-sid="copySId"
          @confirm-shipment="openConfirmDialog"
          @toggle-select="toggleSelectOrder"
          @toggle-select-all="toggleSelectAll"
        />
      </div>

      <div v-if="totalPages > 1" class="orders__pagination">
        <button
          class="orders__page-btn"
          :class="{ 'orders__page-btn--disabled': queryParams.pageNum! <= 1 }"
          @click="handlePageChange(queryParams.pageNum! - 1)"
        >
          <IconChevronLeft />
        </button>

        <template v-for="page in getPageButtons()" :key="page">
          <button
            class="orders__page-btn"
            :class="{ 'orders__page-btn--active': page === queryParams.pageNum }"
            @click="handlePageChange(page)"
          >
            {{ page }}
          </button>
        </template>

        <button
          class="orders__page-btn"
          :class="{ 'orders__page-btn--disabled': queryParams.pageNum! >= totalPages }"
          @click="handlePageChange(queryParams.pageNum! + 1)"
        >
          <IconChevronRight />
        </button>

        <span class="orders__page-info">{{ queryParams.pageNum }} / {{ totalPages }}</span>
      </div>
      </div>
    </div>

    <Transition name="overlay-fade">
      <div v-if="showFilterSheet" class="orders__filter-overlay" @click="showFilterSheet = false">
        <div
          class="orders__filter-sheet"
          :class="{ 'orders__filter-sheet--open': showFilterSheet }"
          @click.stop
        >
          <div class="orders__filter-sheet-handle"></div>
          <h3 class="orders__filter-sheet-title">筛选条件</h3>

          <div class="orders__filter-group">
            <label class="orders__filter-label">关键词</label>
            <input
              v-model="filterKeyword"
              class="orders__filter-input"
              placeholder="商品名称/规格/买家/发货内容"
            />
          </div>

          <div class="orders__filter-actions">
            <button class="btn btn--secondary" @click="resetFilter">重置</button>
            <button class="btn btn--primary" @click="applyFilter">查询</button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="overlay-fade">
      <div v-if="showConfirmDialog" class="orders__dialog-overlay" @click.self="showConfirmDialog = false">
        <div class="orders__dialog">
          <div class="orders__dialog-header">
            <h3 class="orders__dialog-title">确认发货</h3>
          </div>
          <div class="orders__dialog-body">
            <p class="orders__dialog-text">
              确认订单「{{ confirmTargetOrder?.orderId }}」已发货吗？
            </p>
          </div>
          <div class="orders__dialog-footer">
            <button
              class="orders__dialog-btn orders__dialog-btn--cancel"
              @click="showConfirmDialog = false"
            >
              取消
            </button>
            <button
              class="orders__dialog-btn orders__dialog-btn--confirm"
              @click="executeConfirmShipment"
            >
              确认
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="overlay-fade">
      <div v-if="showBatchConfirmDialog" class="orders__dialog-overlay" @click.self="showBatchConfirmDialog = false">
        <div class="orders__dialog">
          <div class="orders__dialog-header">
            <h3 class="orders__dialog-title">批量确认发货</h3>
          </div>
          <div class="orders__dialog-body">
            <p class="orders__dialog-text">
              确定要对已选 {{ selectedOrderIds.length }} 条记录批量确认发货吗？
            </p>
            <p class="orders__dialog-tip">系统将逐条调用闲鱼确认接口，未收到平台确认的记录仍会保留在「待人工核对」中。</p>
          </div>
          <div class="orders__dialog-footer">
            <button
              class="orders__dialog-btn orders__dialog-btn--cancel"
              :disabled="batchConfirming"
              @click="showBatchConfirmDialog = false"
            >
              取消
            </button>
            <button
              class="orders__dialog-btn orders__dialog-btn--confirm"
              :disabled="batchConfirming"
              @click="executeBatchConfirmShipment"
            >
              {{ batchConfirming ? '处理中...' : '确认' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
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
</style>
