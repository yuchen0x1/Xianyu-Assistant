<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getTodoOverview, type TodoItem, type TodoOverview } from '@/api/todo'
import IconChat from '@/components/icons/IconChat.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconRobot from '@/components/icons/IconRobot.vue'

const router = useRouter()
const overview = ref<TodoOverview | null>(null)
const loading = ref(true)
const loadError = ref('')
const activeType = ref<'pendingShipment' | 'pendingReply' | 'pendingReview' | 'pendingEvaluation'>('pendingShipment')

import { useVisibilityPolling } from '@/composables/useVisibilityPolling'

const autoRefresh = ref(false)
const refreshInterval = ref(15)
const refreshOptions = [5, 10, 15, 30, 60]
const autoRefreshIntervalMs = computed(() => refreshInterval.value * 1000)
const { restart: startAutoRefresh, stop: stopAutoRefresh } = useVisibilityPolling({
  enabled: autoRefresh,
  intervalMs: autoRefreshIntervalMs,
  tick: () => { if (!loading.value) void load() }
})

onMounted(() => { void load() })
onUnmounted(() => { stopAutoRefresh() })

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getTodoOverview(50)
    if ((res.code === 200 || res.code === 0) && res.data) {
      overview.value = res.data
    } else {
      overview.value = null
      loadError.value = res.msg || '加载待办中心失败'
    }
  } catch (e) {
    console.error('加载待办中心失败:', e)
    overview.value = null
    loadError.value = '加载待办中心失败，请重试'
  } finally {
    loading.value = false
  }
}

const toggleAutoRefresh = () => { if (autoRefresh.value) startAutoRefresh(); else stopAutoRefresh() }

const tabs = [
  { key: 'pendingShipment' as const, label: '待发货订单', icon: IconTruck, color: 'var(--brand-500)' },
  { key: 'pendingReply' as const, label: '待回复消息', icon: IconChat, color: 'var(--cyan-500)' },
  { key: 'pendingReview' as const, label: '发货待复核', icon: IconRobot, color: 'var(--warning-500)' },
  { key: 'pendingEvaluation' as const, label: '自动评价待确认', icon: IconShoppingBag, color: 'var(--purple-500)' }
]

const activeList = computed<TodoItem[]>(() => {
  if (!overview.value) return []
  switch (activeType.value) {
    case 'pendingShipment': return overview.value.pendingShipment
    case 'pendingReply': return overview.value.pendingReply
    case 'pendingReview': return overview.value.pendingReview
    case 'pendingEvaluation': return overview.value.pendingEvaluation
    default: return []
  }
})

const countFor = (key: string): number => {
  if (!overview.value) return 0
  switch (key) {
    case 'pendingShipment': return overview.value.summary.pendingShipmentCount
    case 'pendingReply': return overview.value.summary.pendingReplyCount
    case 'pendingReview': return overview.value.summary.pendingReviewCount
    case 'pendingEvaluation': return overview.value.summary.pendingEvaluationCount
    default: return 0
  }
}

const totalCount = computed(() => overview.value?.summary.total ?? 0)

const goHandle = (item: TodoItem) => {
  switch (item.type) {
    case 'pendingShipment':
      void router.push({ path: '/pending-orders' })
      break
    case 'pendingReply':
      void router.push({ path: '/messages' })
      break
    case 'pendingReview':
      void router.push({ path: '/orders' })
      break
    case 'pendingEvaluation':
      void router.push({ path: '/reviews' })
      break
  }
}

const formatTime = (t?: string | null): string => {
  if (!t) return ''
  if (/^\d{13}$/.test(t)) {
    const d = new Date(Number(t))
    return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  }
  return t.replace(/^\d{4}-/, '').replace(/-/g, '/').slice(0, 16)
}
</script>

<template>
  <div class="todo">
    <div class="todo__header">
      <h1 class="todo__title">待办中心</h1>
      <div class="todo__header-actions">
        <label class="todo__toggle">
          <input type="checkbox" v-model="autoRefresh" @change="toggleAutoRefresh" />
          <span>自动刷新</span>
        </label>
        <select v-model.number="refreshInterval" @change="toggleAutoRefresh" class="todo__interval" :disabled="!autoRefresh">
          <option v-for="s in refreshOptions" :key="s" :value="s">{{ s }}s</option>
        </select>
        <button class="todo__refresh-btn" @click="load" :disabled="loading" title="刷新待办" aria-label="刷新待办">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21.5 2v6h-6"/><path d="M2.5 22v-6h6"/><path d="M21.5 8A10 10 0 0 0 3.5 6"/><path d="M2.5 16A10 10 0 0 0 20.5 18"/></svg>
        </button>
      </div>
    </div>

    <div v-if="loading" class="todo__state" role="status" aria-live="polite">
      <span class="todo__spinner" aria-hidden="true"></span>
      <span>加载中...</span>
    </div>

    <div v-else-if="loadError" class="todo__error" role="alert">
      <p>{{ loadError }}</p>
      <button class="todo__retry" @click="load">重试</button>
    </div>

    <template v-else>
      <div class="todo__summary">
        <button v-for="tab in tabs" :key="tab.key" type="button" class="todo__summary-card" :class="{ 'todo__summary-card--active': activeType === tab.key }" @click="activeType = tab.key" :aria-pressed="activeType === tab.key">
          <component :is="tab.icon" class="todo__summary-icon" aria-hidden="true" :style="{ color: tab.color }" />
          <div class="todo__summary-meta">
            <span class="todo__summary-label">{{ tab.label }}</span>
            <strong class="todo__summary-count">{{ countFor(tab.key) }}</strong>
          </div>
        </button>
        <div class="todo__summary-total">
          <span class="todo__summary-label">总计</span>
          <strong class="todo__summary-total-count">{{ totalCount }}</strong>
        </div>
      </div>

      <div class="todo__tabs">
        <button v-for="tab in tabs" :key="tab.key" class="todo__tab" :class="{ 'todo__tab--active': activeType === tab.key }" @click="activeType = tab.key">
          {{ tab.label }}
          <span v-if="countFor(tab.key) > 0" class="todo__tab-badge">{{ countFor(tab.key) }}</span>
        </button>
      </div>

      <div v-if="activeList.length === 0" class="todo__empty">
        <p>当前分类暂无待办</p>
      </div>

      <div v-else class="todo__list">
        <button v-for="item in activeList" :key="item.type + '-' + item.id" type="button" class="todo__card" @click="goHandle(item)">
          <div class="todo__card-body">
            <div class="todo__card-line">
              <span class="todo__card-label">账号</span>
              <span class="todo__card-value">{{ item.accountName }}</span>
            </div>
            <div class="todo__card-line">
              <span class="todo__card-label">标题</span>
              <span class="todo__card-value todo__card-value--title">{{ item.title }}</span>
            </div>
            <div v-if="item.description" class="todo__card-line">
              <span class="todo__card-label">买家</span>
              <span class="todo__card-value">{{ item.description }}</span>
            </div>
            <div v-if="item.extra" class="todo__card-line">
              <span class="todo__card-label">备注</span>
              <span class="todo__card-value todo__card-value--extra">{{ item.extra }}</span>
            </div>
            <div v-if="item.time" class="todo__card-line">
              <span class="todo__card-label">时间</span>
              <span class="todo__card-value">{{ formatTime(item.time) }}</span>
            </div>
          </div>
          <span class="todo__card-go">处理 ›</span>
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.todo { max-width: 960px; margin: 0 auto; padding: 24px 20px; }
.todo__header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.todo__title { font-size: 22px; font-weight: 700; color: var(--text-1); letter-spacing: 0; }
.todo__header-actions { display: flex; align-items: center; gap: 10px; }
.todo__toggle { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-2); cursor: pointer; user-select: none; }
.todo__toggle input { width: 14px; height: 14px; }
.todo__interval { padding: 4px 8px; font-size: 12px; border-radius: var(--radius-xs); border: 1px solid var(--glass-border); background: var(--glass-bg-soft); color: var(--text-1); }
.todo__refresh-btn { display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 8px; border: 1px solid var(--glass-border); background: var(--glass-bg-soft); color: var(--text-2); cursor: pointer; transition: all .15s; }
.todo__refresh-btn:hover { background: var(--bg-active); color: var(--brand-500); }
.todo__refresh-btn:disabled { opacity: .5; cursor: not-allowed; }

.todo__state { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 60px 20px; color: var(--text-3); font-size: 14px; }
.todo__spinner { width: 18px; height: 18px; border: 2px solid var(--glass-border); border-top-color: var(--brand-500); border-radius: 50%; animation: todo-spin 0.6s linear infinite; }
@keyframes todo-spin { to { transform: rotate(360deg); } }
.todo__error { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 20px; color: var(--danger-500); font-size: 14px; }
.todo__retry { padding: 6px 18px; font-size: 13px; font-weight: 500; color: var(--brand-500); background: var(--glass-bg-strong); border: 1px solid var(--glass-border); border-radius: var(--radius-pill); cursor: pointer; }
.todo__empty { padding: 60px 20px; text-align: center; color: var(--text-3); font-size: 14px; }

.todo__summary { display: grid; grid-template-columns: repeat(4, 1fr) auto; gap: 12px; margin-bottom: 20px; }
.todo__summary-card, .todo__summary-total {
  display: flex; align-items: center; gap: 12px;
  padding: 16px; border-radius: var(--radius-md);
  background: var(--glass-bg-soft); border: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm); backdrop-filter: var(--glass-blur-sm);
  cursor: pointer; transition: border-color .15s, box-shadow .15s;
}
.todo__summary-card {
  appearance: none; color: var(--text-1); font: inherit; text-align: left;
}
.todo__summary-card:hover { border-color: var(--glass-border-strong); box-shadow: var(--glass-shadow-float); }
.todo__summary-card--active { border-color: var(--brand-500); box-shadow: var(--glow-brand); }
.todo__summary-card:focus-visible, .todo__card:focus-visible {
  outline: 2px solid var(--brand-500); outline-offset: 3px;
}
.todo__summary-icon { width: 26px; height: 26px; flex-shrink: 0; }
.todo__summary-meta { display: flex; flex-direction: column; }
.todo__summary-label { font-size: 12px; color: var(--text-2); }
.todo__summary-count { font-size: 22px; font-weight: 700; color: var(--text-1); line-height: 1.1; }
.todo__summary-total { cursor: default; flex-direction: column; align-items: flex-start; }
.todo__summary-total-count { font-size: 26px; font-weight: 700; color: var(--brand-500); line-height: 1.1; }

.todo__tabs { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.todo__tab { display: inline-flex; align-items: center; gap: 6px; padding: 7px 14px; font-size: 13px; font-weight: 500; color: var(--text-2); background: var(--glass-bg-soft); border: 1px solid var(--glass-border); border-radius: var(--radius-pill); cursor: pointer; transition: all .15s; }
.todo__tab:hover { color: var(--text-1); border-color: var(--glass-border-strong); }
.todo__tab--active { color: var(--text-brand); border-color: var(--brand-500); background: color-mix(in srgb, var(--brand-500) 14%, var(--glass-bg-strong)); }
.todo__tab-badge { min-width: 18px; padding: 0 5px; font-size: 11px; font-weight: 700; text-align: center; border-radius: 999px; background: var(--brand-500); color: #fff; }

.todo__list { display: flex; flex-direction: column; gap: 12px; }
.todo__card {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 16px; border-radius: var(--radius-md);
  background: var(--glass-bg-soft); border: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm); backdrop-filter: var(--glass-blur-sm);
  cursor: pointer; transition: border-color .15s, box-shadow .15s;
  appearance: none; width: 100%; color: var(--text-1); font: inherit; text-align: left;
}
@media (hover: hover) {
  .todo__card:hover { border-color: var(--glass-border-strong); box-shadow: var(--glass-shadow-float); }
}
.todo__card-body { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.todo__card-line { display: flex; align-items: baseline; gap: 8px; }
.todo__card-label { font-size: 12px; color: var(--text-3); flex-shrink: 0; min-width: 36px; }
.todo__card-value { font-size: 13px; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.todo__card-value--title { font-weight: 600; }
.todo__card-value--extra { color: var(--text-2); max-width: 320px; }
.todo__card-go { flex-shrink: 0; font-size: 13px; font-weight: 600; color: var(--brand-500); white-space: nowrap; }

@media (max-width: 768px) {
  .todo { padding: 16px 12px; }
  .todo__title { font-size: 18px; }
  .todo__summary { grid-template-columns: repeat(2, 1fr); }
  .todo__header { flex-direction: column; align-items: flex-start; gap: 10px; }
}
@media (prefers-reduced-transparency: reduce) {
  .todo__summary-card, .todo__summary-total, .todo__tab, .todo__card, .todo__interval, .todo__refresh-btn, .todo__retry {
    -webkit-backdrop-filter: none; backdrop-filter: none; background: var(--surface-solid);
  }
}
</style>
