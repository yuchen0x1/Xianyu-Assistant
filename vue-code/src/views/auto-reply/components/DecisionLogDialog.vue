<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { getAutoReplyDecisions, type AutoReplyDecision } from '@/api/goods'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

interface ReasonMeta {
  label: string
  hint: string
  tone: 'info' | 'warning' | 'danger' | 'neutral'
}

const REASON_META: Record<string, ReasonMeta> = {
  HUMAN_TAKEOVER: { label: '人工接管', hint: '会话已被人工接管，自动回复让位', tone: 'info' },
  GOODS_NOT_FOUND: { label: '商品未同步', hint: '商品未同步到本地，请先同步商品', tone: 'warning' },
  NO_REPLY_STRATEGY: { label: '无回复策略', hint: '关键词回复与AI回复均未开启', tone: 'neutral' },
  NO_REPLY_CONTENT: { label: '未生成回复', hint: '策略已执行但未生成有效内容，请检查AI配置或关键词规则', tone: 'danger' }
}

const loading = ref(false)
const list = ref<AutoReplyDecision[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const filterReason = ref<string>('')
const filterAccountId = ref<string>('')
const errorMessage = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

function reasonMeta(code: string): ReasonMeta {
  return REASON_META[code] ?? { label: code, hint: '', tone: 'neutral' }
}

function formatTime(value: string | null): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

async function fetchDecisions() {
  loading.value = true
  errorMessage.value = ''
  try {
    const accountId = filterAccountId.value.trim() ? Number(filterAccountId.value.trim()) : null
    const res = await getAutoReplyDecisions({
      xianyuAccountId: accountId,
      reasonCode: filterReason.value || null,
      pageNum: pageNum.value,
      pageSize
    })
    list.value = res.data?.list ?? []
    total.value = res.data?.totalCount ?? 0
  } catch {
    errorMessage.value = '加载失败，请稍后重试'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      pageNum.value = 1
      fetchDecisions()
    }
  }
)

watch(filterReason, () => {
  pageNum.value = 1
  fetchDecisions()
})

function changePage(delta: number) {
  const next = pageNum.value + delta
  if (next < 1 || next > totalPages.value) return
  pageNum.value = next
  fetchDecisions()
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="ar__dialog-overlay" @click.self="emit('close')">
      <div class="dl-dialog" role="dialog" aria-modal="true" aria-label="未回复原因记录">
        <div class="ar__records-dialog-header">
          <h3 class="ar__records-dialog-title">未回复原因记录</h3>
          <span v-if="!loading" class="dl-count">共 {{ total }} 条</span>
          <button class="ar__detail-dialog-close" aria-label="关闭" @click="emit('close')">&times;</button>
        </div>

        <div class="dl-filters">
          <select v-model="filterReason" class="dl-filter-select" aria-label="按原因筛选">
            <option value="">全部原因</option>
            <option v-for="(meta, code) in REASON_META" :key="code" :value="code">{{ meta.label }}</option>
          </select>
          <input
            v-model="filterAccountId"
            class="dl-filter-input"
            placeholder="按账号ID筛选"
            type="number"
            min="1"
            @keydown.enter="pageNum = 1; fetchDecisions()"
          />
          <button class="dl-filter-btn" @click="pageNum = 1; fetchDecisions()">查询</button>
        </div>

        <div class="ar__records-dialog-body">
          <div v-if="loading" class="ar__loading">
            <div class="ar__spinner"></div>
            <span>加载中...</span>
          </div>

          <div v-else-if="errorMessage" class="dl-empty">{{ errorMessage }}</div>

          <div v-else-if="list.length === 0" class="dl-empty">
            <span>没有未回复记录，所有买家消息都有据可查</span>
          </div>

          <template v-else>
            <div v-for="item in list" :key="item.id" class="dl-item">
              <div class="dl-item-head">
                <span class="dl-badge" :class="`dl-badge--${reasonMeta(item.reasonCode).tone}`">
                  {{ reasonMeta(item.reasonCode).label }}
                </span>
                <span class="dl-meta">账号 #{{ item.xianyuAccountId }}</span>
                <span v-if="item.xyGoodsId" class="dl-meta">商品 {{ item.xyGoodsId }}</span>
                <span class="dl-time">{{ formatTime(item.createTime) }}</span>
              </div>
              <p v-if="item.buyerMessage" class="dl-message" :title="item.buyerMessage">{{ item.buyerMessage }}</p>
              <p v-if="reasonMeta(item.reasonCode).hint" class="dl-hint">{{ reasonMeta(item.reasonCode).hint }}</p>
            </div>
          </template>
        </div>

        <div v-if="total > pageSize" class="dl-pagination">
          <button class="dl-page-btn" :disabled="pageNum <= 1" @click="changePage(-1)">上一页</button>
          <span class="dl-page-info">{{ pageNum }} / {{ totalPages }}</span>
          <button class="dl-page-btn" :disabled="pageNum >= totalPages" @click="changePage(1)">下一页</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.dl-dialog {
  display: flex;
  flex-direction: column;
  width: min(640px, calc(100vw - 32px));
  max-height: min(70vh, 640px);
  background: var(--surface-solid);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.dl-count {
  font-size: var(--text-sm);
  color: var(--text-3);
}

.dl-filters {
  display: flex;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-1);
}

.dl-filter-select,
.dl-filter-input {
  height: 30px;
  padding: 0 8px;
  font-size: var(--text-sm);
  color: var(--text-1);
  background: var(--surface-solid);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
}

.dl-filter-input {
  width: 130px;
}

.dl-filter-btn {
  height: 30px;
  padding: 0 12px;
  font-size: var(--text-sm);
  color: var(--text-inverse);
  background: var(--brand-600);
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.dl-filter-btn:hover {
  background: var(--brand-700);
}

.dl-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
  font-size: var(--text-md);
  color: var(--text-3);
}

.dl-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-1);
}

.dl-item:last-child {
  border-bottom: none;
}

.dl-item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.dl-badge {
  padding: 2px 8px;
  font-size: var(--text-xs);
  font-weight: 600;
  border-radius: var(--radius-pill);
}

.dl-badge--info {
  color: var(--purple-600, var(--info-500));
  background: var(--purple-50, var(--info-50));
}

.dl-badge--warning {
  color: var(--warning-600);
  background: var(--warning-50);
}

.dl-badge--danger {
  color: var(--danger-600);
  background: var(--danger-50);
}

.dl-badge--neutral {
  color: var(--text-2);
  background: var(--border-1);
}

.dl-meta {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.dl-time {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.dl-message {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--text-1);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.dl-hint {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--text-3);
}

.dl-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px;
  border-top: 1px solid var(--border-1);
}

.dl-page-btn {
  height: 28px;
  padding: 0 10px;
  font-size: var(--text-sm);
  color: var(--text-2);
  background: var(--surface-solid);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.dl-page-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.dl-page-info {
  font-size: var(--text-sm);
  color: var(--text-2);
}
</style>
