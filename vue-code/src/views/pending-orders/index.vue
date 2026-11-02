<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { getAccountList } from '@/api/account'
import { accountDisplayName } from '@/utils'
import { getPendingOrders, deliverPendingOrders, consignDummyDelivery, type PendingOrder } from '@/api/order'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import type { Account } from '@/types'


const accounts = ref<Account[]>([])
const selectedAccountId = ref<number | null>(null)
const pendingOrders = ref<PendingOrder[]>([])
const loading = ref(false)
const loadError = ref('')
const accountsError = ref('')
const delivering = ref(false)
const deliveringOrderId = ref<string | null>(null)
let orderLoadSequence = 0

const autoRefresh = ref(false)
const refreshInterval = ref(10)
const refreshOptions = [5, 10, 15, 30, 60]
let refreshTimer: ReturnType<typeof setInterval> | null = null

const autoDeliver = ref(false)
const autoDeliverAccountId = ref<number | null>(null)
let deliverTimer: ReturnType<typeof setInterval> | null = null

const lastDeliverCount = ref<number | null>(null)

onMounted(() => { loadAccounts() })
onUnmounted(() => { stopAutoRefresh(); stopAutoDeliver() })

const loadAccounts = async () => {
  accountsError.value = ''
  try {
    const res = await getAccountList()
    if ((res.code === 200 || res.code === 0) && res.data) {
      accounts.value = res.data.accounts || []
      if (accounts.value.length > 0 && !selectedAccountId.value) {
        selectedAccountId.value = Number(accounts.value[0].id)
        loadOrders()
      }
    } else {
      accountsError.value = res.msg || '加载账号列表失败'
    }
  } catch (e) { console.error('加载账号列表失败:', e); accountsError.value = '加载账号列表失败，请重试' }
}

const loadOrders = async () => {
  const accountId = selectedAccountId.value
  if (!accountId) {
    pendingOrders.value = []
    loadError.value = ''
    loading.value = false
    return
  }
  const requestSequence = ++orderLoadSequence
  loading.value = true
  loadError.value = ''
  try {
    const res = await getPendingOrders(accountId)
    if (requestSequence !== orderLoadSequence || accountId !== selectedAccountId.value) return
    if ((res.code === 200 || res.code === 0) && res.data) {
      pendingOrders.value = res.data
    } else {
      pendingOrders.value = []
      loadError.value = res.msg || '加载待发货订单失败'
    }
  } catch (e) {
    if (requestSequence !== orderLoadSequence || accountId !== selectedAccountId.value) return
    console.error('加载待发货订单失败:', e)
    pendingOrders.value = []
    loadError.value = '加载待发货订单失败，请重试'
  } finally {
    if (requestSequence === orderLoadSequence) loading.value = false
  }
}

const onAccountChange = () => {
  if (autoDeliverAccountId.value !== null) {
    selectedAccountId.value = autoDeliverAccountId.value
    return
  }
  orderLoadSequence += 1
  pendingOrders.value = []
  loadError.value = ''
  void loadOrders()
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  if (autoRefresh.value) { refreshTimer = setInterval(() => { if (!loading.value) void loadOrders() }, refreshInterval.value * 1000) }
}
const stopAutoRefresh = () => { if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null } }
const toggleAutoRefresh = () => { if (autoRefresh.value) { startAutoRefresh() } else { stopAutoRefresh() } }
const onIntervalChange = () => {
  if (autoRefresh.value) startAutoRefresh()
  if (autoDeliver.value) startAutoDeliver()
}

const handleDeliver = async (accountId: number | null) => {
  if (!accountId || delivering.value) return
  delivering.value = true
  lastDeliverCount.value = null
  try {
    const res = await deliverPendingOrders(accountId)
    if ((res.code === 200 || res.code === 0) && res.data != null) {
      lastDeliverCount.value = res.data
      if (accountId === selectedAccountId.value) void loadOrders()
    } else {
      lastDeliverCount.value = null
      console.error('自动发货失败:', res.msg)
      toast.error(res.msg || '自动发货失败')
    }
  } catch (e) { console.error('自动发货失败:', e); toast.error('自动发货失败，请稍后重试') }
  finally {
    delivering.value = false
    if (!autoDeliver.value) autoDeliverAccountId.value = null
  }
}

const startAutoDeliver = (accountId = autoDeliverAccountId.value ?? selectedAccountId.value) => {
  stopAutoDeliver()
  if (autoDeliver.value) {
    if (accountId === null) {
      autoDeliver.value = false
      return
    }
    autoDeliverAccountId.value = accountId
    void handleDeliver(accountId)
    deliverTimer = setInterval(() => { if (!delivering.value) void handleDeliver(accountId) }, refreshInterval.value * 1000)
  }
}
const stopAutoDeliver = () => {
  if (deliverTimer) { clearInterval(deliverTimer); deliverTimer = null }
  if (!delivering.value) autoDeliverAccountId.value = null
}
const toggleAutoDeliver = async () => {
  if (!autoDeliver.value) {
    stopAutoDeliver()
    return
  }
  try {
    await showConfirm('开启后将按所选间隔自动为该账号处理符合条件的待发货订单。请确认该账号的自动发货规则、库存和发货内容均已核对。', '确认开启自动发货')
    if (autoDeliver.value) startAutoDeliver()
  } catch {
    autoDeliver.value = false
    stopAutoDeliver()
  }
}

const totalCount = computed(() => pendingOrders.value.length)
const formatTime = (t?: string) => { if (!t) return ''; return t.replace(/^\d{4}-/, '').replace(/-/g, '/') }

const handleSingleDeliver = async (order: PendingOrder) => {
  const accountId = selectedAccountId.value
  if (!accountId) return
  const xyGoodsId = order.commonData?.itemId || order.itemVO?.itemId
  const orderId = order.commonData?.orderId
  if (!orderId || deliveringOrderId.value === orderId) return

  deliveringOrderId.value = orderId
  try {
    const res = await consignDummyDelivery({ xianyuAccountId: accountId, xyGoodsId: xyGoodsId || '', orderId })
    if ((res.code === 200 || res.code === 0) && res.data) {
      toast.success('凭证发货请求已完成')
      await loadOrders()
    } else {
      toast.error(res.msg || '凭证发货失败')
    }
  } catch (e) {
    console.error('发货失败:', e)
    toast.error('发货失败，请稍后重试')
  } finally {
    deliveringOrderId.value = null
  }
}
</script>

<template>
  <div class="po">
    <div class="po__header">
      <h1 class="po__title">待发货订单</h1>
    </div>

    <div class="po__toolbar">
      <label class="po__account-label" for="pending-orders-account">闲鱼账号</label>
      <select id="pending-orders-account" v-model="selectedAccountId" @change="onAccountChange" class="po__select" :disabled="accounts.length === 0 || loading || autoDeliver || delivering">
        <option v-for="a in accounts" :key="a.id" :value="Number(a.id)">
          {{ accountDisplayName(a) }}
        </option>
      </select>
      <span v-if="totalCount > 0" class="po__count">{{ totalCount }} 笔待发货</span>
    </div>

    <div class="po__controls">
      <div class="po__control-group">
        <button class="po__refresh-btn" @click="loadOrders" :disabled="loading" title="刷新待发货订单" aria-label="刷新待发货订单">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21.5 2v6h-6"/><path d="M2.5 22v-6h6"/><path d="M21.5 8A10 10 0 0 0 3.5 6"/><path d="M2.5 16A10 10 0 0 0 20.5 18"/></svg>
        </button>
        <label class="po__toggle">
          <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
          <span>自动刷新</span>
        </label>
        <select v-model.number="refreshInterval" @change="onIntervalChange" class="po__interval-select" :disabled="!autoRefresh && !autoDeliver">
          <option v-for="s in refreshOptions" :key="s" :value="s">{{ s }}s</option>
        </select>
      </div>
      <div class="po__control-group">
        <label class="po__toggle po__toggle--deliver">
          <input type="checkbox" v-model="autoDeliver" @change="toggleAutoDeliver" />
          <span>自动发货</span>
        </label>
      </div>
      <div v-if="lastDeliverCount !== null" class="po__deliver-result">
        已发货 {{ lastDeliverCount }} 笔
      </div>
    </div>

    <div v-if="loading" class="po__state" role="status" aria-live="polite">
      <span class="po__spinner" aria-hidden="true"></span>
      <span>加载中...</span>
    </div>

    <div v-else-if="accounts.length === 0 && !accountsError" class="po__empty">
      <IconShoppingBag class="po__empty-icon" aria-hidden="true" />
      <p>暂无账号，请先在「连接管理」中添加</p>
    </div>

    <div v-else-if="accountsError" class="po__error" role="alert">
      <p>{{ accountsError }}</p>
      <button class="po__retry" @click="loadAccounts">重试</button>
    </div>

    <div v-else-if="loadError" class="po__error" role="alert">
      <p>{{ loadError }}</p>
      <button class="po__retry" @click="loadOrders">重试</button>
    </div>

    <div v-else-if="pendingOrders.length === 0" class="po__empty">
      <IconShoppingBag class="po__empty-icon" aria-hidden="true" />
      <p>暂无待发货订单</p>
    </div>

    <div v-else class="po__list">
      <div v-for="order in pendingOrders" :key="order.commonData?.orderId" class="po__card">
        <div class="po__card-header">
          <span class="po__order-id">{{ order.commonData?.orderId }}</span>
          <div class="po__card-header-right">
            <span class="po__status-tag">{{ order.commonData?.orderStatus }}</span>
            <button class="po__single-deliver-btn" @click="handleSingleDeliver(order)" :disabled="deliveringOrderId === order.commonData?.orderId">
              {{ deliveringOrderId === order.commonData?.orderId ? '发货中' : '发货' }}
            </button>
          </div>
        </div>
        <div class="po__card-body">
          <div class="po__field"><span class="po__label">商品</span><span class="po__value po__value--title">{{ order.itemVO?.title }}</span></div>
          <div class="po__field"><span class="po__label">买家</span><span class="po__value">{{ order.buyerInfoVO?.userNick }}</span></div>
          <div class="po__field"><span class="po__label">金额</span><span class="po__value po__value--price">¥{{ order.priceVO?.totalPrice }}</span></div>
          <div class="po__field"><span class="po__label">数量</span><span class="po__value">{{ order.priceVO?.buyNum }}</span></div>
          <div class="po__field"><span class="po__label">下单</span><span class="po__value">{{ formatTime(order.commonData?.createTime) }}</span></div>
          <div class="po__field"><span class="po__label">付款</span><span class="po__value">{{ formatTime(order.commonData?.paySuccessTime) }}</span></div>
        </div>
        <div v-if="order.commonData?.tags?.length" class="po__card-tags">
          <span v-for="tag in order.commonData.tags" :key="tag" class="po__urgency-tag">{{ tag }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.po { max-width: 960px; margin: 0 auto; padding: 24px 20px; }
.po__header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.po__title { font-size: 22px; font-weight: 700; color: var(--c-text-1, var(--text-1)); letter-spacing: 0; }
.po__toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.po__account-label { color: var(--text-2); font-size: 12px; font-weight: 600; }
.po__select { padding: 8px 12px; font-size: 14px; border-radius: var(--radius-sm); border: 1px solid var(--border-1); background: var(--bg-elevated); color: var(--c-text-1, var(--text-1)); min-width: 180px; }
.po__count { font-size: 13px; font-weight: 500; color: var(--c-accent, var(--brand-600)); padding: 4px 10px; border-radius: 8px; background: var(--bg-active); }
.po__controls { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 20px; padding: 12px 16px; border-radius: var(--radius-sm); border: 1px solid var(--border-1); background: var(--bg-elevated); }
.po__control-group { display: flex; align-items: center; gap: 8px; }
.po__toggle { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--c-text-2, var(--text-2)); cursor: pointer; user-select: none; }
.po__toggle input { width: 14px; height: 14px; }
.po__toggle--deliver span { font-weight: 600; color: var(--success-500); }
.po__interval-select { padding: 4px 8px; font-size: 12px; border-radius: var(--radius-xs); border: 1px solid var(--border-1); background: var(--bg-elevated); }
.po__refresh-btn { display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 8px; border: 1px solid var(--border-1); background: var(--bg-elevated); color: var(--c-text-2, var(--text-2)); cursor: pointer; transition: all .15s; }
.po__refresh-btn:hover { background: var(--bg-active); color: var(--brand-600); border-color: var(--border-1); }
.po__refresh-btn:disabled { opacity: .5; cursor: not-allowed; }

.po__deliver-result { font-size: 12px; font-weight: 500; color: var(--success-500); padding: 4px 10px; border-radius: 8px; background: var(--success-50); }
.po__state { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 60px 20px; color: var(--text-3); font-size: 14px; }
.po__spinner { width: 18px; height: 18px; border: 2px solid var(--border-1); border-top-color: var(--brand-600); border-radius: 50%; animation: po-spin 0.6s linear infinite; }
@keyframes po-spin { to { transform: rotate(360deg); } }
.po__empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 60px 20px; color: var(--text-3); font-size: 14px; }
.po__empty-icon { width: 40px; height: 40px; color: var(--text-3); }
.po__error { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 20px; color: var(--danger-500); font-size: 14px; }
.po__retry { padding: 6px 18px; font-size: 13px; font-weight: 500; color: var(--brand-600); background: var(--bg-active); border: 1px solid var(--border-1); border-radius: var(--radius-pill); cursor: pointer; transition: background var(--duration-fast) var(--ease-standard); }
.po__retry:hover { background: var(--bg-hover); }
.po__list { display: flex; flex-direction: column; gap: 12px; }
.po__card { border-radius: var(--radius-sm); border: 1px solid var(--border-1); background: var(--bg-elevated); padding: 16px; transition: box-shadow .2s; }
.po__card:hover { box-shadow: 0 4px 20px rgba(0,0,0,.08); }
.po__card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--border-1); }
.po__order-id { font-size: 13px; font-family: monospace; color: var(--c-text-2, var(--text-2)); }
.po__card-header-right { display: flex; align-items: center; gap: 8px; }
.po__single-deliver-btn { padding: 3px 10px; font-size: 12px; font-weight: 600; border-radius: var(--radius-xs); border: none; background: var(--success-500); color: #fff; cursor: pointer; transition: all .15s; white-space: nowrap; }
.po__single-deliver-btn:hover { background: var(--success-600); }
.po__single-deliver-btn:disabled { opacity: .5; cursor: not-allowed; }
.po__status-tag { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 8px; background: var(--warning-50); color: var(--warning-500); white-space: nowrap; }
.po__card-body { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.po__field { display: flex; align-items: baseline; gap: 6px; }
.po__label { font-size: 12px; color: var(--c-text-3, var(--text-2)); flex-shrink: 0; min-width: 32px; }
.po__value { font-size: 13px; color: var(--c-text-1, var(--text-1)); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.po__value--title { font-weight: 500; }
.po__value--price { font-weight: 600; color: var(--warning-500); }
.po__card-tags { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--border-1); display: flex; gap: 6px; }
.po__urgency-tag { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: var(--radius-xs); background: var(--danger-50); color: var(--danger-500); }
@media (max-width: 768px) {
  .po { padding: 16px 12px; }
  .po__title { font-size: 18px; }
  .po__card-body { grid-template-columns: 1fr; }
  .po__select { min-width: 140px; }
  .po__controls { flex-direction: column; align-items: flex-start; gap: 10px; }
}

/* ============================================================
   Liquid Glass Surfaces
   ============================================================ */
.po__toolbar,
.po__controls {
  background: var(--glass-bg);
  border-color: var(--glass-border);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.po__select,
.po__interval-select,
.po__refresh-btn {
  background: color-mix(in srgb, var(--glass-bg-soft) 82%, transparent);
  border-color: var(--glass-border);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.po__select:focus,
.po__interval-select:focus {
  outline: none;
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px var(--ring-brand);
}

.po__count,
.po__deliver-result {
  border: 1px solid var(--glass-border);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.po__card {
  background: var(--glass-bg-soft);
  border-color: var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

@media (hover: hover) {
  .po__card:hover {
    border-color: var(--glass-border-strong);
    box-shadow: var(--glass-shadow-float);
  }
}

.po__card-header,
.po__card-tags {
  border-color: var(--glass-border);
}

.po__single-deliver-btn,
.po__status-tag,
.po__urgency-tag {
  border: 1px solid color-mix(in srgb, currentColor 24%, transparent);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

.po__retry {
  background: color-mix(in srgb, var(--brand-500) 10%, var(--glass-bg-strong));
  border-color: var(--glass-border);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

@media (prefers-reduced-transparency: reduce) {
  .po__toolbar,
  .po__controls,
  .po__select,
  .po__interval-select,
  .po__refresh-btn,
  .po__count,
  .po__deliver-result,
  .po__card,
  .po__retry {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface-solid);
  }
}

</style>
