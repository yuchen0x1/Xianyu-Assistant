<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { showConfirm } from '@/utils/confirm'
import { toast } from '@/utils/toast'
import { getConnectionStatus, startConnection, stopConnection, type WebSocketStatus } from '@/api/websocket'
import { queryOperationLogs, type OperationLog } from '@/api/operation-log'
import { getRiskAutomationStatus, pauseRiskAutomation, resumeRiskAutomation, type RiskAutomationStatus } from '@/api/risk'
import { showSuccess, showError, showInfo } from '@/utils'
import { deriveAutomationHealth, deriveAutoReplyCard } from '../connection-health'
import { useRefreshOnPageResume } from '../useRefreshOnPageResume'
import CredentialModal from './CredentialModal.vue'
import ManualUpdateCookieModal from './ManualUpdateCookieModal.vue'
import QRUpdateDialog from './QRUpdateDialog.vue'

import IconKey from '@/components/icons/IconKey.vue'
import IconPlay from '@/components/icons/IconPlay.vue'
import IconStop from '@/components/icons/IconStop.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconLog from '@/components/icons/IconLog.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconAlert from '@/components/icons/IconAlert.vue'
import IconLink from '@/components/icons/IconLink.vue'
import IconQrCode from '@/components/icons/IconQrCode.vue'

interface Props {
  accountId: number | null
  accountName?: string
}

const props = defineProps<Props>()

const connectionStatus = ref<WebSocketStatus | null>(null)
const statusLoading = ref(false)
const operationLogs = ref<OperationLog[]>([])
let statusInterval: number | null = null
let statusRequestSequence = 0
const showManualUpdateCookieDialog = ref(false)
const showQRUpdateDialog = ref(false)
const showCredentialDialog = ref(false)
const riskStatus = ref<RiskAutomationStatus | null>(null)
const riskSaving = ref(false)

const loadConnectionStatus = async (silent = false) => {
  if (!isPageActive() || !props.accountId) return
  const requestSequence = ++statusRequestSequence
  const requestedAccountId = props.accountId
  if (!silent) statusLoading.value = true
  try {
    const response = await getConnectionStatus(requestedAccountId)
    if (!isPageActive()) return
    if (response.code === 0 || response.code === 200) {
      if (requestSequence === statusRequestSequence && props.accountId === requestedAccountId) {
        connectionStatus.value = response.data as WebSocketStatus
      }
    } else {
      throw new Error(response.msg || '获取连接状态失败')
    }
  } catch (error) {
    console.error('加载状态失败:', (error as Error).message)
  } finally {
    if (isPageActive() && requestSequence === statusRequestSequence) {
      statusLoading.value = false
    }
  }
}

const loadOperationLogs = async () => {
  if (!props.accountId) return
  try {
    const response = await queryOperationLogs({
      accountId: props.accountId,
      page: 1,
      pageSize: 20
    })
    if (response.code === 0 || response.code === 200) {
      const data = response.data
      operationLogs.value = (data?.logs || []).filter(
        (log: OperationLog) => log.operationModule === 'COOKIE' || log.operationModule === 'TOKEN'
      )
    }
  } catch (error) {
    console.error('加载操作日志失败:', (error as Error).message)
  }
}

const handleStartConnection = async () => {
  if (!props.accountId) return
  statusLoading.value = true
  try {
    const response = await startConnection(props.accountId)
    if (response.code === 0 || response.code === 200) {
      await loadConnectionStatus()
      toast.info('1、请勿使用闲鱼网页版进行消息回复，不然容易触发风控；2、刚开始使用会掉线且无法自动刷新的情况，属于正常，多挂几天就好了；3、有问题可以在"系统设置"->"关于"加入交流群；')
    } else if (response.code === 1001 && response.data?.needCaptcha) {
      showQRUpdateDialog.value = true
    } else {
      throw new Error(response.msg || '启动连接失败')
    }
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      showError('启动连接失败: ' + (error as Error).message)
    }
  } finally {
    statusLoading.value = false
  }
}

const handleStopConnection = async () => {
  if (!props.accountId) return
  try {
    await showConfirm(
      '断开连接后将无法接收消息和执行自动化流程，确定要断开连接吗？',
      '确认断开连接'
    )
  } catch { return }

  statusLoading.value = true
  try {
    const response = await stopConnection(props.accountId)
    if (response.code === 0 || response.code === 200) {
      showSuccess('连接已断开')
      await loadConnectionStatus()
    } else {
      throw new Error(response.msg || '断开连接失败')
    }
  } catch (error) {
    showError('断开连接失败: ' + (error as Error).message)
  } finally {
    statusLoading.value = false
  }
}

const handleRefresh = async () => {
  await Promise.all([loadConnectionStatus(), loadOperationLogs(), loadRiskStatus()])
  showInfo('状态已刷新')
}

const loadRiskStatus = async () => {
  if (!props.accountId) return
  try {
    const response = await getRiskAutomationStatus(props.accountId)
    if (response.code === 0 || response.code === 200) {
      riskStatus.value = response.data ?? null
    }
  } catch {
    // 状态加载失败不阻断页面
  }
}

const handlePauseAutomation = async () => {
  if (!props.accountId) return
  try {
    await showConfirm('暂停后该账号的自动发货、自动回复等自动化任务将被阻止，确定暂停吗？', '暂停自动化')
  } catch {
    return
  }
  riskSaving.value = true
  try {
    const response = await pauseRiskAutomation(props.accountId, '手动暂停')
    if (response.code !== 0 && response.code !== 200) {
      throw new Error(response.msg || '暂停失败')
    }
    toast.success('已暂停自动化')
    await loadRiskStatus()
  } catch (error) {
    showError('暂停自动化失败: ' + (error as Error).message)
  } finally {
    riskSaving.value = false
  }
}

const handleResumeAutomation = async () => {
  if (!props.accountId) return
  try {
    await showConfirm('恢复后将允许该账号继续执行自动化任务，确定恢复吗？', '恢复自动化')
  } catch {
    return
  }
  riskSaving.value = true
  try {
    const response = await resumeRiskAutomation(props.accountId)
    if (response.code !== 0 && response.code !== 200) {
      throw new Error(response.msg || '恢复失败')
    }
    toast.success('已恢复自动化')
    await loadRiskStatus()
  } catch (error) {
    showError('恢复自动化失败: ' + (error as Error).message)
  } finally {
    riskSaving.value = false
  }
}

const handleManualUpdateCookieSuccess = async () => {
  await loadConnectionStatus()
}

const handleQRUpdateSuccess = async () => {
  await loadConnectionStatus()
}

const handleOpenCaptcha = () => {
  showQRUpdateDialog.value = true
}

const handlePageResume = async () => {
  // Refresh status only; credential recovery is handled by the official QR flow.
  await loadConnectionStatus(true)
}

const formatTimestamp = (timestamp?: number | string) => {
  if (!timestamp) return '未设置'
  const normalized = typeof timestamp === 'string' && /^\d+$/.test(timestamp)
    ? Number(timestamp)
    : timestamp
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).replace(/\//g, '-')
}

const getOperationStatusText = (status: number) => {
  const map: Record<number, string> = { 1: '成功', 2: '失败', 3: '部分成功' }
  return map[status] || '未知'
}

const getOperationStatusColor = (status: number) => {
  if (status === 1) return 'var(--success-500)'
  if (status === 2) return 'var(--danger-500)'
  if (status === 3) return 'var(--warning-500)'
  return 'var(--text-2)'
}

const canSyncGoods = computed(() => connectionStatus.value?.cookieStatus === 1)
const canAutoReply = computed(() => connectionStatus.value?.connected === true)
const isCaptchaWaiting = computed(() => !canAutoReply.value && connectionStatus.value?.captchaPending === true)
const isAutoReconnecting = computed(() => !canAutoReply.value && connectionStatus.value?.reconnecting === true)
const automationHealth = computed(() => connectionStatus.value
  ? deriveAutomationHealth(connectionStatus.value)
  : null)
const autoReplyCard = computed(() => connectionStatus.value
  ? deriveAutoReplyCard(connectionStatus.value)
  : null)
const riskCooldown = computed(() => Math.max(0, riskStatus.value?.cooldownRemainingSeconds ?? 0))
const riskCardClass = computed(() => {
  if (riskStatus.value?.paused) return 'status-card--danger'
  if (riskCooldown.value > 0) return 'status-card--warning'
  return 'status-card--success'
})
const riskCardText = computed(() => {
  if (riskStatus.value?.paused) return '已暂停'
  if (riskCooldown.value > 0) return '冷却中（约 ' + riskCooldown.value + ' 秒）'
  return '运行正常'
})
const websocketCardClass = computed(() => {
  if (isCaptchaWaiting.value) return 'status-card--warning'
  if (isAutoReconnecting.value) return 'status-card--warning'
  return canAutoReply.value ? 'status-card--success' : 'status-card--danger'
})
const websocketStatusText = computed(() => {
  const status = connectionStatus.value
  if (!status) return '未连接'
  if (status.connected) return '已连接'
  if (status.captchaPending) return '等待人工验证'
  if (status.reconnecting) {
    const attempt = status.reconnectAttempt && status.reconnectAttempt > 0
      ? `第 ${status.reconnectAttempt} 次`
      : '排队中'
    return `自动重连中 · ${attempt}`
  }
  return '未连接'
})
const nextReconnectText = computed(() => {
  const timestamp = isAutoReconnecting.value ? connectionStatus.value?.nextReconnectAt : null
  if (!timestamp) return ''
  const seconds = Math.max(0, Math.ceil((timestamp - Date.now()) / 1000))
  return `约 ${seconds} 秒后重试`
})

const { isActive: isPageActive } = useRefreshOnPageResume(handlePageResume)

watch(() => props.accountId, (newId) => {
  if (newId) {
    loadConnectionStatus()
    loadOperationLogs()
    loadRiskStatus()
    if (statusInterval) clearInterval(statusInterval)
    statusInterval = window.setInterval(() => {
      if (props.accountId) {
        loadConnectionStatus(true)
        loadOperationLogs()
        loadRiskStatus()
      }
    }, 10000)
  } else {
    statusRequestSequence++
    connectionStatus.value = null
    operationLogs.value = []
    if (statusInterval) {
      clearInterval(statusInterval)
      statusInterval = null
    }
  }
}, { immediate: true })

onBeforeUnmount(() => {
  statusRequestSequence++
  if (statusInterval) clearInterval(statusInterval)
})
</script>

<template>
  <div class="detail-panel">
    <div v-if="!accountId" class="detail-empty">
      <div class="detail-empty__icon"><IconLink /></div>
      <p class="detail-empty__text">请选择一个账号查看连接状态</p>
    </div>

    <div v-else class="detail-scroll" :class="{ 'detail-scroll--loading': statusLoading }">
      <div v-if="connectionStatus" class="detail-body">
        <div v-if="accountName" class="detail-account-name">{{ accountName }}</div>
        <section
          v-if="automationHealth"
          class="automation-health"
          :class="`automation-health--${automationHealth.tone}`"
        >
          <div class="automation-health__main">
            <div class="automation-health__signal">
              <component :is="automationHealth.tone === 'success' ? IconCheck : IconAlert" />
            </div>
            <div class="automation-health__copy">
              <span class="automation-health__eyebrow">自动化健康</span>
              <strong>{{ automationHealth.title }}</strong>
              <span>{{ automationHealth.detail }}</span>
            </div>
            <div class="automation-health__actions">
              <button
                v-if="automationHealth.action === 'captcha'"
                class="btn btn--warning btn--small"
                type="button"
                @click="handleOpenCaptcha"
              >
                <IconQrCode /><span>扫码更新</span>
              </button>
              <button
                v-else-if="automationHealth.action === 'connect'"
                class="btn btn--start btn--small"
                type="button"
                @click="handleStartConnection"
              >
                <IconPlay /><span>重新连接</span>
              </button>
            </div>
          </div>
          <div class="automation-health__metrics">
            <div>
              <span>同步消息</span>
              <strong>{{ connectionStatus.webSocketMessageCount || 0 }} 条</strong>
              <small>{{ formatTimestamp(connectionStatus.lastWebSocketMessageAt) }}</small>
            </div>
            <div>
              <span>最近客户消息</span>
              <strong>{{ connectionStatus.lastCustomerMessageAt ? '已识别' : '暂无' }}</strong>
              <small>{{ formatTimestamp(connectionStatus.lastCustomerMessageAt) }}</small>
            </div>
            <div>
              <span>最近回复成功</span>
              <strong>{{ connectionStatus.lastSuccessfulReplyAt ? '已发送' : '暂无' }}</strong>
              <small>{{ formatTimestamp(connectionStatus.lastSuccessfulReplyAt) }}</small>
            </div>
          </div>
        </section>
        <div class="status-cards">
          <div class="status-card" :class="canSyncGoods ? 'status-card--success' : 'status-card--danger'">
            <div class="status-card__icon">
              <component :is="canSyncGoods ? IconCheck : IconAlert" />
            </div>
            <div class="status-card__content">
              <span class="status-card__title">Cookie 状态</span>
              <span class="status-card__desc">{{ canSyncGoods ? '有效' : '无效' }}</span>
            </div>
            <button class="btn btn--ghost btn--small" @click="showCredentialDialog = true">
              <IconKey /><span>凭证详情</span>
            </button>
          </div>

          <div class="status-card" :class="websocketCardClass">
            <div class="status-card__icon">
              <component :is="canAutoReply ? IconCheck : IconAlert" />
            </div>
            <div class="status-card__content">
              <span class="status-card__title">Websocket 状态</span>
              <span class="status-card__desc">{{ websocketStatusText }}</span>
              <span v-if="isAutoReconnecting && nextReconnectText" class="status-card__meta">{{ nextReconnectText }}</span>
              <span v-else-if="isCaptchaWaiting" class="status-card__meta">请在闲鱼 App 完成官方验证后重新扫码，同步最新 Cookie/Token</span>
            </div>
            <button
              v-if="connectionStatus.connected === true"
              class="btn btn--stop btn--small"
              @click="handleStopConnection"
            >
              <IconStop /><span>断开</span>
            </button>
            <button
              v-else-if="!isAutoReconnecting && !isCaptchaWaiting"
              class="btn btn--start btn--small"
              @click="handleStartConnection"
            >
              <IconPlay /><span>连接</span>
            </button>
          </div>

          <div class="status-card" :class="connectionStatus.autoDeliveryOn ? 'status-card--success' : 'status-card--danger'">
            <div class="status-card__icon">
              <component :is="connectionStatus.autoDeliveryOn ? IconCheck : IconAlert" />
            </div>
            <div class="status-card__content">
              <span class="status-card__title">自动发货</span>
              <span class="status-card__desc">{{ connectionStatus.autoDeliveryOn ? (connectionStatus.connected ? 'WS 发货' : '凭证发货') : '未开启' }}</span>
            </div>
          </div>

          <div class="status-card" :class="`status-card--${autoReplyCard?.tone || 'danger'}`">
            <div class="status-card__icon">
              <component :is="autoReplyCard?.tone === 'success' ? IconCheck : IconAlert" />
            </div>
            <div class="status-card__content">
              <span class="status-card__title">自动回复</span>
              <span class="status-card__desc">{{ autoReplyCard?.label }}</span>
            </div>
                    <div class="status-card" :class="riskCardClass">
            <div class="status-card__icon">
              <component :is="riskStatus?.paused ? IconAlert : riskCooldown > 0 ? IconAlert : IconCheck" />
            </div>
            <div class="status-card__content">
              <span class="status-card__title">自动化保护</span>
              <span class="status-card__desc">{{ riskCardText }}</span>
              <span v-if="riskStatus?.paused && riskStatus.pauseReason" class="status-card__meta">原因：{{ riskStatus.pauseReason }}</span>
            </div>
            <button
              v-if="riskStatus?.paused"
              class="btn btn--start btn--small"
              :disabled="riskSaving"
              @click="handleResumeAutomation"
            >
              <IconPlay /><span>恢复</span>
            </button>
            <button
              v-else-if="riskCooldown > 0"
              class="btn btn--ghost btn--small"
              :disabled="riskSaving"
              @click="handleResumeAutomation"
            >
              <IconRefresh /><span>立即恢复</span>
            </button>
            <button
              v-else
              class="btn btn--ghost btn--small"
              :disabled="riskSaving"
              @click="handlePauseAutomation"
            >
              <IconStop /><span>暂停</span>
            </button>
          </div>
</div>
        </div>

        <div class="action-bar">
          <button class="btn btn--ghost btn--small" @click="handleRefresh" :disabled="statusLoading">
            <IconRefresh /><span>刷新状态</span>
          </button>
        </div>

        <div class="log-section">
          <div class="log-section__header">
            <div class="log-section__title">
              <IconLog />
              <span>操作日志</span>
            </div>
          </div>
          <div class="log-container">
            <div v-for="log in operationLogs" :key="log.id" class="log-entry">
              <span class="log-entry__time">{{ formatTimestamp(log.createTime) }}</span>
              <span class="log-entry__module">{{ log.operationModule }}</span>
              <span class="log-entry__desc">{{ log.operationDesc }}</span>
              <span class="log-entry__status" :style="{ color: getOperationStatusColor(log.operationStatus) }">
                {{ getOperationStatusText(log.operationStatus) }}
              </span>
            </div>
            <div v-if="operationLogs.length === 0" class="log-empty">暂无Cookie/Token相关日志</div>
          </div>
        </div>
      </div>
    </div>

    <CredentialModal
      v-model="showCredentialDialog"
      :connection-status="connectionStatus"
      @qr-update="showQRUpdateDialog = true"
      @manual-update="showManualUpdateCookieDialog = true"
    />

    <ManualUpdateCookieModal
      v-if="connectionStatus"
      v-model="showManualUpdateCookieDialog"
      :account-id="accountId || 0"
      current-cookie=""
      @success="handleManualUpdateCookieSuccess"
    />
    <QRUpdateDialog
      v-model="showQRUpdateDialog"
      :account-id="accountId || 0"
      @success="handleQRUpdateSuccess"
    />
  </div>
</template>

<style scoped>
.detail-panel {
  --c-bg: transparent;
  --c-surface: var(--bg-elevated);
  --c-border: var(--border-1);
  --c-text-1: var(--text-1);
  --c-text-2: var(--text-2);
  --c-text-3: var(--text-2);
  --c-accent: var(--brand-600);
  --c-danger: var(--danger-500);
  --c-success: var(--success-500);
  --c-warning: var(--warning-500);
  --c-r-sm: 8px;
  --c-r-md: 12px;
  --c-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.detail-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;
  color: var(--c-text-3);
}

.detail-empty__icon {
  width: 48px;
  height: 48px;
  opacity: 0.3;
}

.detail-empty__icon svg {
  width: 36px;
  height: 36px;
}

.detail-empty__text {
  font-size: 14px;
  margin: 0;
}

.detail-scroll {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  scrollbar-width: none;
}

.detail-scroll::-webkit-scrollbar {
  display: none;
}

.detail-scroll--loading {
  opacity: 0.5;
  pointer-events: none;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 20px 16px;
}

.status-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  width: 100%;
}

.status-card {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: var(--radius-sm);
  border: 1px solid;
  background: var(--bg-elevated);
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.status-card--success {
  border-color: var(--border-1);
  background: rgba(52, 199, 89, 0.08);
}

.status-card--success:hover {
  border-color: var(--border-1);
  background: rgba(52, 199, 89, 0.12);
}

.status-card--danger {
  border-color: var(--border-1);
  background: rgba(255, 59, 48, 0.08);
}

.status-card--danger:hover {
  border-color: var(--border-1);
  background: rgba(255, 59, 48, 0.12);
}

.status-card--warning {
  border-color: var(--border-1);
  background: rgba(255, 159, 10, 0.1);
}

.status-card--warning:hover {
  border-color: var(--border-1);
  background: rgba(255, 159, 10, 0.14);
}

.status-card__icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.status-card--success .status-card__icon {
  background: rgba(52, 199, 89, 0.2);
  color: var(--c-success);
}

.status-card--danger .status-card__icon {
  background: rgba(255, 59, 48, 0.2);
  color: var(--c-danger);
}

.status-card--warning .status-card__icon {
  background: rgba(255, 159, 10, 0.2);
  color: var(--c-warning, var(--warning-500));
}

.status-card__icon svg { width: 20px; height: 20px; }

.status-card__content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  min-width: 0;
}

.status-card__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text-1);
  letter-spacing: 0;
}

.status-card__desc {
  font-size: 11px;
  color: var(--c-text-3);
  line-height: 1.4;
}

.status-card__meta {
  font-size: 11px;
  color: var(--c-warning, var(--warning-600));
  line-height: 1.35;
}

.detail-account-name {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text-1);
  margin-bottom: -8px;
}

.automation-health {
  overflow: hidden;
  background: var(--surface-primary);
  border: 1px solid var(--border-subtle);
  border-left-width: 4px;
  border-radius: 8px;
}

.automation-health--success { border-left-color: var(--success-600); }
.automation-health--warning { border-left-color: var(--warning-600); }
.automation-health--danger { border-left-color: var(--danger-500); }
.automation-health--info { border-left-color: var(--brand-600); }
.automation-health--muted { border-left-color: var(--text-3); }

.automation-health__main {
  display: flex;
  min-height: 92px;
  padding: 14px;
  align-items: center;
  gap: 12px;
}

.automation-health__signal {
  display: flex;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  align-items: center;
  justify-content: center;
  color: var(--text-2);
  background: var(--bg-hover);
  border-radius: 8px;
}

.automation-health--success .automation-health__signal { color: var(--success-600); background: var(--success-50); }
.automation-health--warning .automation-health__signal { color: var(--warning-600); background: var(--warning-50); }
.automation-health--danger .automation-health__signal { color: var(--danger-600); background: var(--danger-50); }
.automation-health--info .automation-health__signal { color: var(--brand-700); background: var(--brand-100); }

.automation-health__signal svg { width: 19px; height: 19px; }

.automation-health__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.automation-health__eyebrow {
  color: var(--text-tertiary);
  font-size: 10px;
  font-weight: 700;
}

.automation-health__copy strong {
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.3;
}

.automation-health__copy > span:last-child {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.automation-health__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.btn--warning {
  color: #ffffff;
  background: #b45309;
  border: 1px solid #b45309;
}

.automation-health__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  background: var(--surface-secondary);
  border-top: 1px solid var(--border-subtle);
}

.automation-health__metrics > div {
  display: flex;
  min-width: 0;
  min-height: 62px;
  padding: 9px 12px;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--border-subtle);
}

.automation-health__metrics > div:last-child { border-right: 0; }
.automation-health__metrics span { color: var(--text-tertiary); font-size: 10px; }
.automation-health__metrics strong { color: var(--text-primary); font-size: 12px; }
.automation-health__metrics small {
  overflow: hidden;
  color: var(--text-tertiary);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-card--success .status-card__title { color: var(--c-success); }
.status-card--danger .status-card__title { color: var(--c-danger); }
.status-card--warning .status-card__title { color: var(--c-warning, var(--warning-600)); }

.status-card .btn {
  flex-shrink: 0;
}

.action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 16px;
  font-size: 15px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  border: none;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  -webkit-tap-highlight-color: transparent;
  letter-spacing: 0;
}

.btn svg { width: 16px; height: 16px; }

.btn--small {
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
}

.btn--small svg { width: 14px; height: 14px; }

.btn--start {
  background: var(--c-success);
  color: white;
  box-shadow: 0 4px 12px rgba(52, 199, 89, 0.3);
}

.btn--start:hover {
  box-shadow: 0 6px 16px rgba(52, 199, 89, 0.4);
  transform: translateY(-1px);
}

.btn--start:active { transform: scale(0.97); }

.btn--stop {
  background: var(--c-danger);
  color: white;
  box-shadow: 0 4px 12px rgba(255, 59, 48, 0.3);
}

.btn--stop:hover {
  box-shadow: 0 6px 16px rgba(255, 59, 48, 0.4);
  transform: translateY(-1px);
}

.btn--stop:active { transform: scale(0.97); }

.btn--ghost {
  background: rgba(0, 122, 255, 0.08);
  color: var(--c-accent);
  border-color: var(--border-1);
}

.btn--ghost:hover {
  background: rgba(0, 122, 255, 0.15);
}

.btn--ghost:active { transform: scale(0.97); }

.btn--ghost:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.log-section {
  margin-top: 20px;
  padding-bottom: 8px;
}

.log-section__header {
  margin-bottom: 10px;
}

.log-section__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text-1);
}

.log-section__title svg { width: 16px; height: 16px; }

.log-container {
  background: #2c3e50;
  color: #ecf0f1;
  border-radius: 8px;
  padding: 12px;
  font-family: 'Courier New', Consolas, monospace;
  font-size: 12px;
  max-height: 180px;
  overflow-y: auto;
}

.log-entry {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
  line-height: 1.5;
}

.log-entry:last-child { margin-bottom: 0; }

.log-entry__time {
  color: #95a5a6;
  font-size: 11px;
  flex-shrink: 0;
}

.log-entry__module {
  color: #3498db;
  flex-shrink: 0;
  font-weight: 500;
}

.log-entry__desc {
  color: #ecf0f1;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-entry__status {
  flex-shrink: 0;
  font-weight: 500;
}

.log-empty {
  text-align: center;
  color: #95a5a6;
  padding: 16px;
  font-size: 12px;
}
</style>

<style scoped>
.detail-panel {
  container-name: connection-detail;
  container-type: inline-size;
  background: var(--surface-primary);
}

.detail-empty {
  gap: 10px;
  color: var(--text-tertiary);
}

.detail-empty__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  color: #94a3b8;
  background: var(--surface-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  opacity: 1;
}

.detail-empty__icon svg {
  width: 24px;
  height: 24px;
}

.detail-empty__text {
  color: var(--text-tertiary);
  font-size: 13px;
}

.detail-scroll {
  scrollbar-width: thin;
}

.detail-body {
  gap: 16px;
  padding: 18px;
}

.detail-account-name {
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.3;
}

.status-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.status-card {
  min-height: 72px;
  padding: 12px;
  gap: 10px;
  background: var(--surface-primary);
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  }

.status-card:hover {
  background: var(--surface-secondary);
}

.status-card--success,
.status-card--success:hover {
  background: var(--surface-primary);
  border-color: #bbddc4;
}

.status-card--danger,
.status-card--danger:hover {
  background: var(--surface-primary);
  border-color: #efc7c4;
}

.status-card__icon {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-xs);
}

.status-card--success .status-card__icon {
  color: var(--success-600);
  background: var(--success-50);
}

.status-card--danger .status-card__icon {
  color: var(--danger-600);
  background: var(--danger-50);
}

.status-card__icon svg {
  width: 17px;
  height: 17px;
}

.status-card__content {
  gap: 2px;
}

.status-card__title {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.status-card__desc {
  color: var(--text-secondary);
  font-size: 11px;
  letter-spacing: 0;
}

.btn {
  min-height: 32px;
  padding: 0 10px;
  gap: 5px;
  font-size: 11px;
  font-weight: 700;
  border-radius: var(--radius-xs);
  box-shadow: none;
  }

.btn--ghost {
  color: var(--brand-700);
  background: var(--brand-50);
  border: 1px solid var(--brand-100);
}

.btn--ghost:hover {
  color: var(--brand-700);
  background: var(--brand-100);
}

.btn--start {
  color: #ffffff;
  background: var(--success-600);
  border: 1px solid var(--success-600);
}

.btn--stop {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.action-bar {
  margin: 0;
  padding: 0;
  border: 0;
}

.log-section {
  margin-top: 0;
  padding: 0;
}

.log-section__header {
  margin-bottom: 8px;
}

.log-section__title {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.log-container {
  max-height: 220px;
  padding: 0;
  overflow-y: auto;
  color: var(--text-primary);
  font-family: inherit;
  font-size: 11px;
  background: var(--surface-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
}

.log-entry {
  display: grid;
  min-height: 36px;
  margin: 0;
  padding: 8px 10px;
  align-items: start;
  grid-template-columns: 118px 64px minmax(0, 1fr) auto;
  gap: 8px;
  line-height: 1.45;
  border-bottom: 1px solid var(--border-subtle);
}

.log-entry:last-child {
  border-bottom: 0;
}

.log-entry__time,
.log-entry__module,
.log-entry__desc,
.log-entry__status {
  color: var(--text-secondary);
  font-size: 11px;
}

.log-entry__module {
  color: var(--brand-700);
  font-weight: 700;
}

.log-entry__desc {
  display: -webkit-box;
  overflow: hidden;
  white-space: normal;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.log-empty {
  padding: 24px 12px;
  color: var(--text-tertiary);
  font-size: 12px;
}

@container connection-detail (max-width: 620px) {
  .automation-health__main {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .automation-health__actions {
    width: 100%;
    padding-left: 50px;
  }

  .automation-health__metrics {
    grid-template-columns: 1fr;
  }

  .automation-health__metrics > div {
    min-height: 50px;
    border-right: 0;
    border-bottom: 1px solid var(--border-subtle);
  }

  .automation-health__metrics > div:last-child { border-bottom: 0; }

  .status-cards {
    grid-template-columns: 1fr;
  }

  .log-entry {
    grid-template-columns: 1fr auto;
  }

  .log-entry__time,
  .log-entry__module {
    display: none;
  }
}
</style>
