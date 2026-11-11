<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAlertList, getMonitoringOverview, operateAlert, type AlertEvent, type MonitoringOverview } from '@/api/monitoring'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'
import { usePermissions } from '@/composables/usePermissions'
import { toast } from '@/utils/toast'
import { formatTime } from '@/utils'

defineOptions({ name: 'MonitoringView' })

const { hasPermission } = usePermissions()
const canWrite = computed(() => hasPermission('monitoring:write'))

const alerts = ref<AlertEvent[]>([])
const overview = ref<MonitoringOverview | null>(null)
const loading = ref(true)
const statusFilter = ref('')

const statusLabels: Record<string, string> = {
  ACTIVE: '活跃', ACKED: '已确认', SILENCED: '已静默', RESOLVED: '已恢复'
}
const statusColors: Record<string, string> = {
  ACTIVE: 'var(--danger-500)', ACKED: 'var(--warning-500)', SILENCED: 'var(--text-3)', RESOLVED: 'var(--success-500)'
}
const levelColors: Record<string, string> = {
  ERROR: 'var(--danger-500)', WARNING: 'var(--warning-500)', INFO: 'var(--info-500)'
}

const loadAlerts = async () => {
  loading.value = true
  try {
    const res = await getAlertList(statusFilter.value || undefined, undefined, 100)
    if (res.code === 200) alerts.value = res.data || []
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '加载告警列表失败')
  } finally {
    loading.value = false
  }
}

const loadOverview = async () => {
  try {
    const res = await getMonitoringOverview()
    if (res.code === 200 && res.data) overview.value = res.data
  } catch {
    // 概览加载失败不阻断列表
  }
}

const handleAction = async (alert: AlertEvent, action: 'ack' | 'silence' | 'unsilence' | 'resolve') => {
  if (!canWrite.value) return
  try {
    await operateAlert(alert.alertKey, action)
    toast.success('操作成功')
    await Promise.all([loadAlerts(), loadOverview()])
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '操作失败')
  }
}

onMounted(() => {
  loadAlerts()
  loadOverview()
})
</script>

<template>
  <UiPageShell title="监控中心" subtitle="告警事件与通知状态">
    <template #actions>
      <div class="filter-bar">
        <select v-model="statusFilter" @change="loadAlerts">
          <option value="">全部状态</option>
          <option value="ACTIVE">活跃</option>
          <option value="ACKED">已确认</option>
          <option value="SILENCED">已静默</option>
          <option value="RESOLVED">已恢复</option>
        </select>
      </div>
    </template>

    <!-- 概览卡片 -->
    <div v-if="overview" class="overview-grid">
      <div class="overview-card">
        <div class="overview-card__value">{{ overview.activeAlerts }}</div>
        <div class="overview-card__label">活跃告警</div>
      </div>
      <div class="overview-card">
        <div class="overview-card__value">{{ overview.deliveryFailures }}</div>
        <div class="overview-card__label">投递失败</div>
      </div>
      <div class="overview-card">
        <div class="overview-card__value">{{ overview.pendingDeadLetters }}</div>
        <div class="overview-card__label">待处理死信</div>
      </div>
      <div class="overview-card">
        <div class="overview-card__value" :class="overview.notificationChannels?.configured ? 'ok' : 'warn'">
          {{ overview.notificationChannels?.configured ? '已配置' : '未配置' }}
        </div>
        <div class="overview-card__label">通知渠道</div>
      </div>
    </div>

    <!-- 告警列表 -->
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="alerts.length === 0" class="empty">暂无告警</div>
    <div v-else class="alert-list">
      <div v-for="alert in alerts" :key="alert.id" class="alert-card">
        <div class="alert-header">
          <span class="alert-title">{{ alert.title }}</span>
          <span class="alert-pill" :style="{ color: statusColors[alert.status] || 'var(--text-3)' }">
            {{ statusLabels[alert.status] || alert.status }}
          </span>
        </div>
        <div class="alert-key">{{ alert.alertKey }}</div>
        <div class="alert-message">{{ alert.message }}</div>
        <div class="alert-meta">
          <span class="alert-level" :style="{ color: levelColors[alert.level] || 'var(--text-3)' }">{{ alert.level }}</span>
          <span>触发 {{ alert.occurrences }} 次</span>
          <span>尝试 {{ alert.sendAttempts }} 次</span>
          <span>首次: {{ formatTime(alert.firstSeenAt) }}</span>
          <span v-if="alert.lastSeenAt">最近: {{ formatTime(alert.lastSeenAt) }}</span>
          <span v-if="alert.lastSentAt">发送: {{ formatTime(alert.lastSentAt) }}</span>
        </div>
        <div v-if="canWrite && alert.status !== 'RESOLVED'" class="alert-actions">
          <UiButton
            v-if="alert.status === 'ACTIVE'"
            size="small"
            variant="secondary"
            @click="handleAction(alert, 'ack')"
          >确认</UiButton>
          <UiButton
            v-if="alert.status === 'ACTIVE' || alert.status === 'ACKED'"
            size="small"
            variant="secondary"
            @click="handleAction(alert, 'silence')"
          >静默</UiButton>
          <UiButton
            v-if="alert.status === 'SILENCED'"
            size="small"
            @click="handleAction(alert, 'unsilence')"
          >解除静默</UiButton>
          <UiButton size="small" variant="ghost" @click="handleAction(alert, 'resolve')">恢复</UiButton>
        </div>
        <div v-if="alert.status === 'RESOLVED'" class="resolved-note">已恢复，不再投递</div>
      </div>
    </div>
  </UiPageShell>
</template>

<style scoped>
.loading,
.empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-base);
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
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.filter-bar select:focus {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px var(--ring-brand);
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--space-3);
}

.overview-card {
  padding: var(--space-4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.overview-card__value {
  font-size: 28px;
  font-weight: 780;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

.overview-card__value.ok {
  color: var(--success-500, var(--success-500));
}

.overview-card__value.warn {
  color: var(--danger-500);
}

.overview-card__label {
  margin-top: 4px;
  font-size: var(--text-sm);
  color: var(--text-3);
}

.alert-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.alert-card {
  padding: var(--space-4);
  border: 1px solid var(--glass-border);
  border-left: 3px solid var(--danger-500);
  border-radius: var(--radius-md);
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.alert-card:has(.resolved-note) {
  border-left-color: var(--text-3);
  opacity: 0.72;
}

.alert-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.alert-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
}

.alert-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid color-mix(in srgb, currentColor 28%, transparent);
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, currentColor 8%, transparent);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  white-space: nowrap;
}

.alert-key {
  margin-bottom: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  line-height: 1.4;
  color: var(--text-2);
  word-break: break-all;
}

.alert-message {
  margin-bottom: var(--space-2);
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--text-2);
  word-break: break-all;
}

.alert-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  font-size: var(--text-xs);
  color: var(--text-3);
}

.alert-level {
  font-weight: var(--weight-semibold);
}

.alert-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.resolved-note {
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-3);
}

@media (max-width: 767.98px) {
  .filter-bar {
    width: 100%;
  }

  .filter-bar select {
    width: 100%;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .overview-card,
  .alert-card {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface-solid);
  }
}
</style>