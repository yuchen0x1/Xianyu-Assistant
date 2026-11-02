<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getJobList, getJobExecutions, getDeadLetters, resolveDeadLetter, retryDeadLetter, type JobDefinition, type JobExecution, type JobDeadLetter } from '@/api/jobs'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import { formatTime } from '@/utils'

defineOptions({ name: 'JobsView' })

const activeTab = ref('active')  // active / dead-letter
const jobs = ref<JobDefinition[]>([])
const deadLetters = ref<JobDeadLetter[]>([])
const loading = ref(true)
const selectedJob = ref<JobDefinition | null>(null)
const executions = ref<JobExecution[]>([])
const showExecDialog = ref(false)
const statusFilter = ref('')
const jobTypeFilter = ref('')

const statusLabels: Record<string, string> = {
  PENDING: '待执行', RUNNING: '执行中', SUCCESS: '已完成',
  FAILED: '失败', CANCELLED: '已取消', DEAD_LETTER: '死信'
}
const statusColors: Record<string, string> = {
  PENDING: '#ff9800', RUNNING: '#2196f3', SUCCESS: '#4caf50',
  FAILED: '#f44336', CANCELLED: '#9e9e9e', DEAD_LETTER: '#e91e63'
}

const loadJobs = async () => {
  loading.value = true
  try {
    const s = statusFilter.value || undefined
    const t = jobTypeFilter.value || undefined
    const res = await getJobList(s, t, 100)
    if (res.code === 200) jobs.value = res.data || []
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '加载任务列表失败')
  } finally {
    loading.value = false
  }
}

const loadDeadLetters = async () => {
  loading.value = true
  try {
    const res = await getDeadLetters(100)
    if (res.code === 200) deadLetters.value = res.data || []
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '加载死信列表失败')
  } finally {
    loading.value = false
  }
}

const viewExecutions = async (job: JobDefinition) => {
  selectedJob.value = job
  showExecDialog.value = true
  try {
    const res = await getJobExecutions(job.id)
    if (res.code === 200) executions.value = res.data || []
  } catch {
    executions.value = []
  }
}

const handleRetryDeadLetter = async (dl: JobDeadLetter) => {
  await showConfirm('确定重试该死信任务？')
  try {
    await retryDeadLetter(dl.id)
    toast.success('已重试')
    await loadDeadLetters()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '重试失败')
  }
}

const handleResolveDeadLetter = async (dl: JobDeadLetter) => {
  await showConfirm('标记为已处理？')
  try {
    await resolveDeadLetter(dl.id, '已人工确认')
    toast.success('已处理')
    await loadDeadLetters()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '操作失败')
  }
}

onMounted(() => { loadJobs() })
</script>

<template>
  <UiPageShell title="任务中心">
    <template #actions>
      <div class="filter-bar">
        <select v-model="statusFilter" @change="loadJobs">
          <option value="">全部状态</option>
          <option value="PENDING">待执行</option>
          <option value="RUNNING">执行中</option>
          <option value="FAILED">失败</option>
          <option value="SUCCESS">已完成</option>
          <option value="DEAD_LETTER">死信</option>
        </select>
        <select v-model="jobTypeFilter" @change="loadJobs">
          <option value="">全部类型</option>
          <option value="DELIVERY">发货</option>
          <option value="AUTO_REPLY">自动回复</option>
          <option value="TOKEN_REFRESH">Token刷新</option>
          <option value="PENDING_ORDER_POLL">订单轮询</option>
          <option value="SYNC_GOODS">同步商品</option>
          <option value="OFF_SHELF">定时下架</option>
          <option value="DELIVERY_REVIEW">发货核对</option>
        </select>
      </div>
    </template>

    <div class="tab-bar">
      <button :class="{ active: activeTab === 'active' }" @click="activeTab = 'active'; loadJobs()">任务列表</button>
      <button :class="{ active: activeTab === 'dead-letter' }" @click="activeTab = 'dead-letter'; loadDeadLetters()">死信队列</button>
    </div>

    <!-- 任务列表 -->
    <div v-if="activeTab === 'active'">
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="jobs.length === 0" class="empty">暂无任务</div>
      <div v-else class="job-list">
        <div v-for="job in jobs" :key="job.id" class="job-card" @click="viewExecutions(job)">
          <div class="job-header">
            <span class="job-type">{{ job.jobType }}</span>
            <span class="job-status" :style="{ color: statusColors[job.status] || '#888' }">
              {{ statusLabels[job.status] || job.status }}
            </span>
          </div>
          <div class="job-key">{{ job.idempotencyKey }}</div>
          <div class="job-meta">
            <span>重试: {{ job.retryCount }}/{{ job.maxRetries }}</span>
            <span v-if="job.nextRunTime">下次: {{ formatTime(job.nextRunTime) }}</span>
            <span>创建: {{ formatTime(job.createdTime) }}</span>
          </div>
          <div v-if="job.lastError" class="job-error">{{ job.lastError }}</div>
        </div>
      </div>
    </div>

    <!-- 死信列表 -->
    <div v-else>
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="deadLetters.length === 0" class="empty">暂无死信</div>
      <div v-else class="job-list">
        <div v-for="dl in deadLetters" :key="dl.id" class="job-card dead-letter" :class="{ resolved: dl.resolved === 1 }">
          <div class="job-header">
            <span class="job-type">{{ dl.jobType }}</span>
            <span class="job-status" :class="dl.resolved === 1 ? 'resolved-label' : 'unresolved-label'">
              {{ dl.resolved === 1 ? '已处理' : '未处理' }}
            </span>
          </div>
          <div class="job-key">{{ dl.idempotencyKey }}</div>
          <div class="job-meta">重试 {{ dl.finalRetryCount }} 次后失败</div>
          <div class="job-error">{{ dl.lastError }}</div>
          <div v-if="dl.resolved === 0" class="job-actions">
            <UiButton size="small" @click.stop="handleRetryDeadLetter(dl)">重试</UiButton>
            <UiButton size="small" variant="secondary" @click.stop="handleResolveDeadLetter(dl)">忽略</UiButton>
          </div>
        </div>
      </div>
    </div>

    <!-- 执行历史弹窗 -->
    <Teleport to="body">
      <div v-if="showExecDialog" class="dialog-overlay" @click.self="showExecDialog = false">
        <div class="dialog dialog-wide">
          <h3>执行历史 - {{ selectedJob?.jobType }} / {{ selectedJob?.idempotencyKey }}</h3>
          <div v-if="executions.length === 0" class="empty">暂无执行记录</div>
          <div v-else class="exec-list">
            <div v-for="exec in executions" :key="exec.id" class="exec-item">
              <div class="exec-header">
                <span class="exec-attempt">#{{ exec.attempt }}</span>
                <span :style="{ color: statusColors[exec.status] || '#888' }">{{ statusLabels[exec.status] || exec.status }}</span>
                <span class="exec-worker">{{ exec.workerId }}</span>
              </div>
              <div class="exec-time">
                <span>{{ formatTime(exec.startedAt) }}</span>
                <span v-if="exec.finishedAt"> → {{ formatTime(exec.finishedAt) }}</span>
              </div>
              <div v-if="exec.errorMessage" class="exec-error">{{ exec.errorMessage }}</div>
            </div>
          </div>
          <div class="dialog-actions"><UiButton @click="showExecDialog = false">关闭</UiButton></div>
        </div>
      </div>
    </Teleport>
  </UiPageShell>
</template>

<style scoped>
/* =========================================================
   Jobs Center - Liquid Glass Surfaces
   ========================================================= */
.loading,
.empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-base);
}

.tab-bar {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  margin-bottom: var(--space-4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  background: var(--glass-bg-soft);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.tab-bar button {
  min-height: 32px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-xs);
  background: transparent;
  cursor: pointer;
  color: var(--text-2);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.tab-bar button:hover {
  color: var(--text-1);
  background: var(--glass-bg);
}

.tab-bar button.active {
  color: var(--brand-700);
  background: var(--glass-bg-strong);
  border-color: var(--glass-border);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.08);
  font-weight: var(--weight-semibold);
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.filter-bar select {
  min-height: 36px;
  padding: 0 34px 0 12px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-xs);
  background-color: var(--glass-bg-soft);
  color: var(--text-1);
  font-size: var(--text-md);
  outline: none;
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.filter-bar select:focus {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px var(--ring-brand);
}

.job-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.job-card {
  position: relative;
  overflow: hidden;
  padding: var(--space-4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
  cursor: pointer;
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
}

.job-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  background: var(--glass-highlight);
  pointer-events: none;
}

@media (hover: hover) {
  .job-card:hover {
    background: var(--glass-bg-strong);
    border-color: var(--glass-border-strong);
    box-shadow: var(--glass-shadow-float);
    transform: translateY(-1px);
  }
}

.job-card.dead-letter {
  border-left: 3px solid var(--danger-500);
}

.job-card.dead-letter.resolved {
  border-left-color: var(--text-3);
  opacity: 0.72;
}

.job-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.job-type {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
  letter-spacing: 0;
}

.job-status {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid color-mix(in srgb, currentColor 28%, transparent);
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, currentColor 8%, transparent);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  line-height: 1;
  white-space: nowrap;
}

.resolved-label {
  color: var(--text-3);
}

.unresolved-label {
  color: var(--danger-500);
}

.job-key {
  margin-bottom: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  line-height: 1.4;
  color: var(--text-2);
  word-break: break-all;
}

.job-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  font-size: var(--text-xs);
  color: var(--text-3);
}

.job-error {
  margin-top: var(--space-3);
  padding: 8px 10px;
  border: 1px solid color-mix(in srgb, var(--danger-500) 22%, transparent);
  border-radius: var(--radius-xs);
  background: var(--danger-50);
  color: var(--danger-600);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  line-height: 1.5;
  word-break: break-all;
}

.job-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.dialog-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(15, 23, 42, 0.38);
  z-index: var(--z-modal);
  -webkit-backdrop-filter: blur(10px) saturate(130%);
  backdrop-filter: blur(10px) saturate(130%);
}

.dialog {
  position: relative;
  width: min(720px, 100%);
  max-height: min(82vh, 640px);
  overflow-y: auto;
  padding: var(--space-5);
  border: 1px solid var(--glass-border-strong);
  border-radius: var(--radius-md);
  background: var(--glass-bg-strong);
  box-shadow: var(--glass-shadow-float);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.dialog::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  background: var(--glass-highlight);
  pointer-events: none;
}

.dialog h3 {
  margin: 0 0 var(--space-4);
  color: var(--text-1);
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  line-height: 1.3;
  letter-spacing: 0;
  word-break: break-all;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}

.exec-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.exec-item {
  padding: var(--space-3);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-xs);
  background: var(--glass-bg-soft);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.exec-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-1);
}

.exec-attempt {
  color: var(--text-1);
  font-weight: var(--weight-semibold);
  font-variant-numeric: tabular-nums;
}

.exec-worker {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--text-3);
}

.exec-time {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.exec-error {
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--danger-600);
  word-break: break-all;
}

@media (max-width: 767.98px) {
  .tab-bar {
    width: 100%;
  }

  .tab-bar button {
    flex: 1;
    padding: 0 8px;
  }

  .filter-bar {
    width: 100%;
  }

  .filter-bar select {
    flex: 1 1 140px;
  }

  .dialog {
    padding: var(--space-4);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .tab-bar,
  .filter-bar select,
  .job-card,
  .exec-item {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface-solid);
  }
}
</style>
