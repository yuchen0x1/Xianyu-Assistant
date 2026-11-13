<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getWebhookDeliveries, type WebhookDelivery } from '@/api/webhooks'
import UiButton from '@/components/ui/UiButton.vue'
import { formatTime } from '@/utils'

const props = defineProps<{ webhookId: number }>()
const records = ref<WebhookDelivery[]>([])
const loading = ref(false)
const error = ref('')
const status = ref('')
let generation = 0
const visibleRecords = computed(() => records.value.filter(record => !status.value || record.status === status.value))
const statusLabels: Record<string, string> = { PENDING: '等待投递', SUCCESS: '成功', FAILED: '失败', UNKNOWN: '结果未知' }

async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const response = await getWebhookDeliveries(props.webhookId, 100)
    if (current !== generation) return
    if (response.code !== 200) throw new Error(response.message || '加载投递历史失败')
    records.value = response.data || []
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '加载投递历史失败'
  } finally {
    if (current === generation) loading.value = false
  }
}

watch(() => props.webhookId, () => {
  records.value = []
  status.value = ''
  void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section class="delivery-history" aria-label="投递历史" :aria-busy="loading">
    <div class="history-toolbar">
      <h3>投递历史</h3>
      <label>状态
        <select v-model="status" aria-label="投递状态">
          <option value="">全部状态</option>
          <option value="PENDING">等待投递</option>
          <option value="SUCCESS">成功</option>
          <option value="FAILED">失败</option>
          <option value="UNKNOWN">结果未知</option>
        </select>
      </label>
      <UiButton variant="secondary" :loading="loading" @click="load">刷新历史</UiButton>
    </div>
    <p class="history-note">最近 100 条记录 · 自动重试每分钟扫描 · 仅显示载荷字段名，不展示内容或密钥。测试消息不计入历史。</p>
    <p v-if="error" role="alert" class="history-error">{{ error }}，请刷新重试。</p>
    <p v-else-if="loading" role="status">正在加载投递记录…</p>
    <p v-else-if="!visibleRecords.length" role="status">{{ records.length ? '当前筛选下没有投递记录' : '暂无投递记录，监听事件触发后会显示在这里' }}</p>
    <ul v-else class="delivery-records">
      <li v-for="record in visibleRecords" :key="record.id" class="delivery-record">
        <div class="record-heading">
          <strong>{{ record.event }}</strong>
          <span :class="['delivery-status', record.status.toLowerCase()]">{{ statusLabels[record.status] || record.status }}</span>
        </div>
        <div class="record-meta">
          <span>#{{ record.id }} · {{ formatTime(record.createdTime) }}</span>
          <span>累计尝试 {{ record.attempts ?? 0 }} 次</span>
          <span v-if="record.httpStatus">HTTP {{ record.httpStatus }}</span>
        </div>
        <p v-if="record.lastError" class="history-error">{{ record.lastError }}</p>
        <p v-if="record.status === 'FAILED'">{{ record.nextAttemptAt ? `下次重试不早于 ${formatTime(record.nextAttemptAt)}` : '自动重试已停止，请检查端点配置与失败原因' }}</p>
        <p v-if="record.deliveredAt">送达时间：{{ formatTime(record.deliveredAt) }}</p>
        <p v-if="record.payloadSummary" class="history-note">载荷字段：{{ record.payloadSummary }}</p>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.delivery-history { margin-top: var(--space-4); padding-top: var(--space-4); border-top: 1px solid var(--glass-border); }
.history-toolbar, .record-heading, .record-meta { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-3); }
.history-toolbar h3 { margin: 0; font-size: var(--text-base); }
.history-toolbar label { display: flex; gap: var(--space-2); align-items: center; }
.history-toolbar select { color: var(--text-1); background: var(--bg-elevated); border: 1px solid var(--border-2); border-radius: var(--radius-sm); padding: 8px; }
.history-toolbar select:focus-visible { outline: 2px solid var(--brand-600); outline-offset: 2px; }
.history-note, .record-meta { color: var(--text-3); font-size: var(--text-sm); }
.history-error { color: var(--danger-500); }
.delivery-records { list-style: none; padding: 0; margin: var(--space-3) 0 0; display: grid; gap: var(--space-3); }
.delivery-record { padding: var(--space-3); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); background: var(--glass-bg-soft); overflow-wrap: anywhere; }
.delivery-record p { margin: var(--space-2) 0 0; }
.record-heading { justify-content: space-between; margin-bottom: var(--space-2); }
.delivery-status { font-size: var(--text-sm); }
.delivery-status.success { color: var(--success-600); }
.delivery-status.failed { color: var(--danger-500); }
</style>
