<script setup lang="ts">
import { ref, watch, computed, onMounted, onUnmounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getConnectionStatus, startConnection, stopConnection, type WebSocketStatus } from '@/api/websocket'
import { queryOperationLogs, type OperationLog } from '@/api/operation-log'
import { getRiskAutomationStatus, pauseRiskAutomation, resumeRiskAutomation, type RiskAutomationStatus } from '@/api/risk'
import { getAccountList } from '@/api/account'
import { showSuccess, showError, showInfo } from '@/utils'
import { toast } from '@/utils/toast'
import { deriveAutomationHealth, deriveAutoReplyCard } from './connection-health'
import { useRefreshOnPageResume } from './useRefreshOnPageResume'
import DesktopDetail from './components/ConnectionDetail.vue'
import ManualUpdateCookieModal from './components/ManualUpdateCookieModal.vue'
import QRUpdateDialog from './components/QRUpdateDialog.vue'

import IconCookie from '@/components/icons/IconCookie.vue'
import IconKey from '@/components/icons/IconKey.vue'
import IconPlay from '@/components/icons/IconPlay.vue'
import IconStop from '@/components/icons/IconStop.vue'
import IconQrCode from '@/components/icons/IconQrCode.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconLog from '@/components/icons/IconLog.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconHelp from '@/components/icons/IconHelp.vue'
import { accountDisplayName } from '@/utils'

const route = useRoute()
const router = useRouter()
const accountId = computed(() => Number(route.params.id) || null)

const accountName = ref('')
const loadAccountName = async () => {
  if (!accountId.value) return
  try {
    const res = await getAccountList()
    if (res.code === 200 && res.data) {
      const acc = (res.data.accounts || res.data || []).find((a: { id: number }) => a.id === accountId.value)
      accountName.value = accountDisplayName(acc)
    }
  } catch {
    accountName.value = ''
  }
}

const isMobile = ref(false)
const checkScreenSize = () => { isMobile.value = window.innerWidth < 768 }
onMounted(() => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})
onUnmounted(() => { window.removeEventListener('resize', checkScreenSize) })

const connectionStatus = ref<WebSocketStatus | null>(null)
const statusLoading = ref(false)
const operationLogs = ref<OperationLog[]>([])
let statusInterval: number | null = null
let statusRequestSequence = 0
const showManualUpdateCookieDialog = ref(false)
const showQRUpdateDialog = ref(false)
const showCredentialSection = ref(false)
const riskStatus = ref<RiskAutomationStatus | null>(null)
const riskSaving = ref(false)

const loadConnectionStatus = async (silent = false) => {
  if (!isPageActive() || !accountId.value) return
  const requestSequence = ++statusRequestSequence
  const requestedAccountId = accountId.value
  if (!silent) statusLoading.value = true
  try {
    const response = await getConnectionStatus(requestedAccountId)
    if (!isPageActive()) return
    if (response.code === 0 || response.code === 200) {
      if (requestSequence === statusRequestSequence && accountId.value === requestedAccountId) {
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
  if (!accountId.value) return
  try {
    const response = await queryOperationLogs({
      accountId: accountId.value,
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
  if (!accountId.value) return
  statusLoading.value = true
  try {
    const response = await startConnection(accountId.value)
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
  if (!accountId.value) return
  if (!confirm('断开连接后将无法接收消息和执行自动化流程，确定要断开连接吗？')) return

  statusLoading.value = true
  try {
    const response = await stopConnection(accountId.value)
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
  if (!accountId.value) return
  try {
    const response = await getRiskAutomationStatus(accountId.value)
    if (response.code === 0 || response.code === 200) {
      riskStatus.value = response.data ?? null
    }
  } catch {
    // 状态加载失败不阻断页面
  }
}

const handlePauseAutomation = async () => {
  if (!accountId.value) return
  if (!confirm('暂停后该账号的自动发货、自动回复等自动化任务将被阻止，确定暂停吗？')) return
  riskSaving.value = true
  try {
    const response = await pauseRiskAutomation(accountId.value, '手动暂停')
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
  if (!accountId.value) return
  if (!confirm('恢复后将允许该账号继续执行自动化任务，确定恢复吗？')) return
  riskSaving.value = true
  try {
    const response = await resumeRiskAutomation(accountId.value)
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

const handleBack = () => {
  router.push('/connection')
}

const getCookieStatusText = (status?: number) => {
  if (status === undefined || status === null) return '未知'
  const map: Record<number, string> = { 1: '有效', 2: '过期', 3: '失效' }
  return map[status] || '未知'
}

const getCookieStatusColor = (status?: number) => {
  if (status === 1) return 'var(--success-500)'
  if (status === 2) return 'var(--warning-500)'
  if (status === 3) return 'var(--danger-500)'
  return 'var(--text-2)'
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

const isTokenExpired = (timestamp?: number) => {
  if (!timestamp) return false
  return Date.now() > timestamp
}

const getTokenStatusText = (timestamp?: number) => {
  if (!timestamp) return '未设置'
  return isTokenExpired(timestamp) ? '已过期' : '有效'
}

const getTokenStatusColor = (timestamp?: number) => {
  if (!timestamp) return 'var(--text-2)'
  return isTokenExpired(timestamp) ? 'var(--danger-500)' : 'var(--success-500)'
}

const getMH5TkStatusText = (configured?: boolean) => {
  if (!configured) return '未设置'
  return '有效'
}

const getMH5TkStatusColor = (configured?: boolean) => {
  if (!configured) return 'var(--text-2)'
  return 'var(--success-500)'
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
const riskMobileClass = computed(() => {
  if (riskStatus.value?.paused) return 'cap-card--err'
  if (riskCooldown.value > 0) return 'cap-card--warn'
  return 'cap-card--ok'
})
const riskCardText = computed(() => {
  if (riskStatus.value?.paused) return '已暂停'
  if (riskCooldown.value > 0) return '冷却中（约 ' + riskCooldown.value + ' 秒）'
  return '运行正常'
})
const websocketStatusText = computed(() => {
  const status = connectionStatus.value
  if (!status) return '未连接'
  if (status.connected) return '已连接'
  if (status.captchaPending) return '等待人工验证'
  if (status.reconnecting) return `自动重连中${status.reconnectAttempt ? ` · 第 ${status.reconnectAttempt} 次` : ''}`
  return '未连接'
})

const { isActive: isPageActive } = useRefreshOnPageResume(handlePageResume)

watch(accountId, (newId) => {
  if (newId && isMobile.value) {
    // 桌面端渲染的是 <DesktopDetail>，它自己有一路 10 秒轮询。这里再起一路
    // 会让同一账号每 10 秒发 5 个重复请求、互相覆盖结果，所以只在移动端轮询。
    loadAccountName()
    loadConnectionStatus()
    loadOperationLogs()
    loadRiskStatus()
    if (statusInterval) clearInterval(statusInterval)
    statusInterval = window.setInterval(() => {
      if (accountId.value) {
        loadConnectionStatus(true)
        loadRiskStatus()
      }
    }, 10000)
  } else if (newId) {
    loadAccountName()
    if (statusInterval) {
      clearInterval(statusInterval)
      statusInterval = null
    }
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

// 窗口在桌面/移动端之间切换时同步轮询的启停，避免移动端尺寸下没有数据
watch(isMobile, (mobile) => {
  if (!accountId.value) return
  if (statusInterval) {
    clearInterval(statusInterval)
    statusInterval = null
  }
  if (mobile) {
    loadConnectionStatus()
    loadOperationLogs()
    loadRiskStatus()
    statusInterval = window.setInterval(() => {
      if (accountId.value) {
        loadConnectionStatus(true)
        loadRiskStatus()
      }
    }, 10000)
  }
})

onBeforeUnmount(() => {
  statusRequestSequence++
  if (statusInterval) clearInterval(statusInterval)
})
</script>

<template>
  <!-- Desktop: reuse desktop component -->
  <DesktopDetail v-if="!isMobile" :account-id="accountId" />

  <!-- Mobile: custom native-style page -->
  <div v-else class="page">
    <header class="page__header">
      <button class="page__back" type="button" @click="handleBack">
        <IconChevronLeft />
        <span>账号列表</span>
      </button>
      <button
        class="page__action"
        type="button"
        aria-label="刷新连接状态"
        @click="handleRefresh"
        :disabled="statusLoading"
      >
        <IconRefresh />
      </button>
    </header>

    <div class="page__scroll" :class="{ 'page__scroll--loading': statusLoading }">
      <div v-if="connectionStatus" class="page__body">
        <div v-if="accountName" class="page__account-name">{{ accountName }}</div>
        <section
          v-if="automationHealth"
          class="mobile-health"
          :class="`mobile-health--${automationHealth.tone}`"
        >
          <div class="mobile-health__header">
            <div class="mobile-health__signal">
              <span></span>
            </div>
            <div class="mobile-health__copy">
              <small>自动化健康</small>
              <strong>{{ automationHealth.title }}</strong>
              <span>{{ automationHealth.detail }}</span>
            </div>
          </div>
          <div class="mobile-health__timeline">
            <div>
              <span>同步消息</span>
              <strong>{{ connectionStatus.webSocketMessageCount || 0 }} 条</strong>
              <small>{{ formatTimestamp(connectionStatus.lastWebSocketMessageAt) }}</small>
            </div>
            <div>
              <span>回复成功</span>
              <strong>{{ connectionStatus.lastSuccessfulReplyAt ? '已发送' : '暂无' }}</strong>
              <small>{{ formatTimestamp(connectionStatus.lastSuccessfulReplyAt) }}</small>
            </div>
          </div>
          <div v-if="automationHealth.action !== 'none'" class="mobile-health__actions">
            <button
              v-if="automationHealth.action === 'captcha'"
              class="act-btn act-btn--warning"
              type="button"
              @click="handleOpenCaptcha"
            >
              <IconQrCode /><span>扫码更新</span>
            </button>
            <button
              v-else-if="automationHealth.action === 'connect'"
              class="act-btn act-btn--success"
              type="button"
              @click="handleStartConnection"
            >
              <IconPlay /><span>重新连接</span>
            </button>
          </div>
        </section>
        <div class="cap-section">
          <div class="cap-card" :class="canSyncGoods ? 'cap-card--ok' : 'cap-card--err'">
            <div class="cap-card__dot"></div>
            <div class="cap-card__text">
              <span class="cap-card__label">Cookie 状态</span>
              <span class="cap-card__desc">{{ canSyncGoods ? '有效' : '无效' }}</span>
            </div>
            <button class="act-btn act-btn--outline act-btn--card" @click="showCredentialSection = !showCredentialSection">
              <IconKey /><span>{{ showCredentialSection ? '收起' : '凭证' }}</span>
            </button>
          </div>
          <div class="cap-card" :class="isCaptchaWaiting || isAutoReconnecting ? 'cap-card--warn' : canAutoReply ? 'cap-card--ok' : 'cap-card--err'">
            <div class="cap-card__dot"></div>
            <div class="cap-card__text">
              <span class="cap-card__label">Websocket 状态</span>
              <span class="cap-card__desc">{{ websocketStatusText }}</span>
              <span v-if="isCaptchaWaiting" class="cap-card__desc">请在闲鱼 App 完成官方验证后重新扫码，同步最新 Cookie/Token</span>
            </div>
            <button
              v-if="connectionStatus.connected === true"
              class="act-btn act-btn--danger act-btn--card"
              @click="handleStopConnection"
            >
              <IconStop /><span>断开</span>
            </button>
            <button
              v-else-if="!isCaptchaWaiting && !isAutoReconnecting"
              class="act-btn act-btn--success act-btn--card"
              @click="handleStartConnection"
            >
              <IconPlay /><span>连接</span>
            </button>
          </div>
          <div class="cap-card" :class="connectionStatus.autoDeliveryOn ? 'cap-card--ok' : 'cap-card--err'">
            <div class="cap-card__dot"></div>
            <div class="cap-card__text">
              <span class="cap-card__label">自动发货</span>
              <span class="cap-card__desc">{{ connectionStatus.autoDeliveryOn ? (connectionStatus.connected ? 'WS 发货' : '凭证发货') : '未开启' }}</span>
            </div>
          </div>
          <div
            class="cap-card"
            :class="autoReplyCard?.tone === 'success' ? 'cap-card--ok' : autoReplyCard?.tone === 'warning' ? 'cap-card--warn' : 'cap-card--err'"
          >
            <div class="cap-card__dot"></div>
            <div class="cap-card__text">
              <span class="cap-card__label">自动回复</span>
              <span class="cap-card__desc">{{ autoReplyCard?.label }}</span>
            </div>
          </div>
          <div class="cap-card" :class="riskMobileClass">
            <div class="cap-card__dot"></div>
            <div class="cap-card__text">
              <span class="cap-card__label">自动化保护</span>
              <span class="cap-card__desc">{{ riskCardText }}</span>
              <span v-if="riskStatus?.paused && riskStatus.pauseReason" class="cap-card__desc">原因：{{ riskStatus.pauseReason }}</span>
            </div>
            <button
              v-if="riskStatus?.paused"
              class="act-btn act-btn--success act-btn--card"
              :disabled="riskSaving"
              @click="handleResumeAutomation"
            >
              <IconPlay /><span>恢复</span>
            </button>
            <button
              v-else-if="riskCooldown > 0"
              class="act-btn act-btn--outline act-btn--card"
              :disabled="riskSaving"
              @click="handleResumeAutomation"
            >
              <IconRefresh /><span>立即恢复</span>
            </button>
            <button
              v-else
              class="act-btn act-btn--outline act-btn--card"
              :disabled="riskSaving"
              @click="handlePauseAutomation"
            >
              <IconStop /><span>暂停</span>
            </button>
          </div>
        </div>

        <div class="action-row action-row--sub">
          <button class="act-btn act-btn--outline" @click="showQRUpdateDialog = true">
            <IconQrCode /><span>扫码更新</span>
          </button>
        </div>

        <Transition name="slide">
          <div v-if="showCredentialSection" class="cred-section">
            <div class="cred-block">
              <div class="cred-block__head">
                <div class="cred-block__left">
                  <div class="cred-block__icon cred-block__icon--cookie"><IconCookie /></div>
                  <span class="cred-block__name">Cookie</span>
                </div>
                <span class="cred-block__status" :style="{ color: getCookieStatusColor(connectionStatus.cookieStatus) }">
                  {{ getCookieStatusText(connectionStatus.cookieStatus) }}
                </span>
              </div>
              <div class="cred-block__body" v-if="connectionStatus.cookieConfigured">
                <div class="cred-block__code">凭证已安全保存</div>
                <div class="cred-block__meta">
                  <span>{{ connectionStatus.cookieLength || 0 }} 字符</span>
                </div>
              </div>
              <div class="cred-block__empty" v-else>未设置</div>
              <div class="cred-block__foot">
                <button class="act-btn act-btn--tiny" @click="showManualUpdateCookieDialog = true">
                  <span>手动更新</span>
                </button>
              </div>
            </div>

            <div class="cred-block">
              <div class="cred-block__head">
                <div class="cred-block__left">
                  <div class="cred-block__icon cred-block__icon--token"><IconKey /></div>
                  <span class="cred-block__name">WebSocket Token</span>
                </div>
                <span class="cred-block__status" :style="{ color: getTokenStatusColor(connectionStatus.tokenExpireTime) }">
                  {{ getTokenStatusText(connectionStatus.tokenExpireTime) }}
                </span>
              </div>
              <div class="cred-block__body" v-if="connectionStatus.websocketTokenConfigured">
                <div class="cred-block__code">凭证已安全保存</div>
                <div class="cred-block__meta">
                  <span>{{ connectionStatus.websocketTokenLength || 0 }} 字符</span>
                </div>
              </div>
              <div class="cred-block__empty" v-else>未设置</div>
              <div class="cred-block__foot" v-if="connectionStatus.tokenExpireTime">
                <span class="cred-block__expire">过期时间: {{ formatTimestamp(connectionStatus.tokenExpireTime) }}</span>
              </div>
            </div>

            <div class="cred-block" v-if="connectionStatus.h5TokenConfigured">
              <div class="cred-block__head">
                <div class="cred-block__left">
                  <div class="cred-block__icon cred-block__icon--h5"><IconHelp /></div>
                  <span class="cred-block__name">H5 Token</span>
                </div>
                <span class="cred-block__status" :style="{ color: getMH5TkStatusColor(connectionStatus.h5TokenConfigured) }">
                  {{ getMH5TkStatusText(connectionStatus.h5TokenConfigured) }}
                </span>
              </div>
              <div class="cred-block__body">
                <div class="cred-block__code">凭证已安全保存</div>
                <div class="cred-block__meta">
                  <span>{{ connectionStatus.h5TokenLength || 0 }} 字符</span>
                </div>
              </div>
            </div>
          </div>
        </Transition>

        <div class="log-section">
          <div class="log-section__header">
            <div class="log-section__title"><IconLog /><span>操作日志</span></div>
          </div>
          <div class="log-container">
            <div v-for="log in operationLogs" :key="log.id" class="log-entry">
              <span class="log-entry__time">{{ formatTimestamp(log.createTime) }}</span>
              <span class="log-entry__desc">{{ log.operationDesc }}</span>
              <span class="log-entry__status" :style="{ color: getOperationStatusColor(log.operationStatus) }">
                {{ getOperationStatusText(log.operationStatus) }}
              </span>
            </div>
            <div v-if="operationLogs.length === 0" class="log-empty">暂无日志</div>
          </div>
        </div>
      </div>

      <div v-else-if="!statusLoading" class="page__empty">
        <p>加载中...</p>
      </div>
    </div>

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
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-muted);
  overflow: hidden;
}

.page__header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border-1);
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 10;
}

.page__back {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 16px;
  font-weight: 500;
  color: var(--brand-600);
  cursor: pointer;
  background: none;
  border: none;
  padding: 4px 0;
  -webkit-tap-highlight-color: transparent;
}

.page__back svg { width: 22px; height: 22px; }

.page__title {
  flex: 1;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
  letter-spacing: 0;
}

.page__action {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-muted);
  border: none;
  border-radius: var(--radius-sm);
  color: var(--brand-600);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.page__action:disabled { opacity: 0.4; }
.page__action svg { width: 18px; height: 18px; }

.page__scroll {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}

.page__scroll::-webkit-scrollbar { display: none; }
.page__scroll--loading { opacity: 0.5; pointer-events: none; }

.page__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  padding-bottom: 32px;
}

.page__empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-2);
  font-size: 14px;
}

/* Capability Cards */
.cap-section {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.cap-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: var(--radius-sm);
  border: 1px solid;
}

.cap-card--ok {
  background: rgba(52, 199, 89, 0.06);
  border-color: var(--border-1);
}

.cap-card--err {
  background: rgba(255, 59, 48, 0.06);
  border-color: var(--border-1);
}

.cap-card--warn {
  border-color: var(--border-1);
  background: rgba(255, 159, 10, 0.1);
}

.cap-card__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.cap-card--ok .cap-card__dot { background: var(--success-500); }
.cap-card--err .cap-card__dot { background: var(--danger-500); }
.cap-card--warn .cap-card__dot { background: var(--warning-500); }

.cap-card__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cap-card__label {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.cap-card--ok .cap-card__label { color: var(--success-500); }
.cap-card--err .cap-card__label { color: var(--danger-500); }
.cap-card--warn .cap-card__label { color: var(--warning-600); }

.cap-card__desc {
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.3;
}

.page__account-name {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: -4px;
}

.mobile-health {
  overflow: hidden;
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-top: 4px solid var(--text-3);
  border-radius: 8px;
}

.mobile-health--success { border-top-color: var(--success-600); }
.mobile-health--warning { border-top-color: var(--warning-600); }
.mobile-health--danger { border-top-color: var(--danger-500); }
.mobile-health--info { border-top-color: var(--brand-600); }

.mobile-health__header {
  display: flex;
  padding: 14px;
  align-items: flex-start;
  gap: 11px;
}

.mobile-health__signal {
  display: flex;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  background: var(--bg-hover);
  border-radius: var(--radius-xs);
}

.mobile-health__signal span {
  width: 9px;
  height: 9px;
  background: var(--text-3);
  border-radius: 50%;
}

.mobile-health--success .mobile-health__signal { background: var(--success-50); }
.mobile-health--success .mobile-health__signal span { background: var(--success-600); }
.mobile-health--warning .mobile-health__signal { background: var(--warning-50); }
.mobile-health--warning .mobile-health__signal span { background: var(--warning-600); }
.mobile-health--danger .mobile-health__signal { background: var(--danger-50); }
.mobile-health--danger .mobile-health__signal span { background: var(--danger-500); }
.mobile-health--info .mobile-health__signal { background: var(--brand-100); }
.mobile-health--info .mobile-health__signal span { background: var(--brand-600); }

.mobile-health__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.mobile-health__copy small {
  color: var(--text-3);
  font-size: 10px;
  font-weight: 700;
}

.mobile-health__copy strong {
  color: var(--text-1);
  font-size: 16px;
  line-height: 1.3;
}

.mobile-health__copy span {
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.45;
}

.mobile-health__timeline {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  background: var(--bg-muted);
  border-top: 1px solid var(--border-1);
  border-bottom: 1px solid var(--border-1);
}

.mobile-health__timeline > div {
  display: flex;
  min-width: 0;
  min-height: 66px;
  padding: 10px 12px;
  flex-direction: column;
  gap: 2px;
}

.mobile-health__timeline > div + div { border-left: 1px solid var(--border-1); }
.mobile-health__timeline span { color: var(--text-3); font-size: 10px; }
.mobile-health__timeline strong { color: var(--text-1); font-size: 12px; }
.mobile-health__timeline small {
  overflow: hidden;
  color: var(--text-3);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mobile-health__actions {
  display: grid;
  padding: 10px 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.mobile-health__actions > :only-child { grid-column: 1 / -1; }

.mobile-health__actions .act-btn {
  min-height: 40px;
  padding: 8px 10px;
  font-size: 13px;
  border-radius: var(--radius-xs);
}

.act-btn--warning {
  color: #ffffff;
  background: var(--warning-600);
}

.act-btn--card {
  flex-shrink: 0;
  flex: 0;
  padding: 8px 12px;
  font-size: 13px;
}

/* Action Buttons */
.action-row {
  display: flex;
  gap: 10px;
}

.action-row--sub {
  margin-top: -6px;
}

.act-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 12px 16px;
  font-size: 15px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  border: none;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  letter-spacing: 0;
  transition: all 0.15s ease;
  flex: 1;
}

.act-btn svg { width: 16px; height: 16px; }

.act-btn--success {
  background: var(--success-500);
  color: white;
  box-shadow: 0 4px 12px rgba(52, 199, 89, 0.3);
}

.act-btn--danger {
  background: var(--danger-500);
  color: white;
  box-shadow: 0 4px 12px rgba(255, 59, 48, 0.3);
}

.act-btn--outline {
  background: rgba(0, 122, 255, 0.08);
  color: var(--brand-600);
  border: 1px solid var(--border-1);
}

.act-btn--tiny {
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 500;
  background: rgba(0, 122, 255, 0.08);
  color: var(--brand-600);
  border-radius: 8px;
  flex: 0;
}

.act-btn:active { transform: scale(0.97); }

/* Credential Section */
.cred-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cred-block {
  background: white;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-1);
  overflow: hidden;
}

.cred-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border-1);
}

.cred-block__left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cred-block__icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.cred-block__icon svg { width: 16px; height: 16px; }

.cred-block__icon--cookie {
  background: rgba(255, 149, 0, 0.12);
  color: var(--warning-500);
}

.cred-block__icon--token {
  background: rgba(52, 199, 89, 0.12);
  color: var(--success-500);
}

.cred-block__icon--h5 {
  background: rgba(0, 122, 255, 0.12);
  color: var(--brand-600);
}

.cred-block__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.cred-block__status {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 8px;
  background: var(--bg-muted);
}

.cred-block__body {
  padding: 12px 16px;
}

.cred-block__code {
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  font-size: 11px;
  color: var(--text-2);
  line-height: 1.5;
  word-break: break-all;
  background: var(--bg-muted);
  padding: 10px;
  border-radius: 8px;
  max-height: 120px;
  overflow-y: auto;
  scrollbar-width: none;
}

.cred-block__code::-webkit-scrollbar { display: none; }

.cred-block__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-2);
}

.cred-block__copy {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 122, 255, 0.08);
  border: none;
  border-radius: 8px;
  color: var(--brand-600);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.cred-block__copy svg { width: 14px; height: 14px; }

.cred-block__empty {
  padding: 14px 16px;
  font-size: 13px;
  color: var(--text-2);
  font-style: italic;
}

.cred-block__foot {
  padding: 10px 16px;
  border-top: 1px solid var(--border-1);
  display: flex;
  align-items: center;
}

.cred-block__expire {
  font-size: 12px;
  color: var(--text-2);
}

/* Log Section */
.log-section {
  margin-top: 4px;
}

.log-section__header {
  margin-bottom: 10px;
}

.log-section__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.log-section__title svg { width: 16px; height: 16px; color: var(--text-2); }

.log-container {
  background: var(--text-1);
  border-radius: var(--radius-sm);
  padding: 12px;
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  font-size: 12px;
  max-height: 200px;
  overflow-y: auto;
  scrollbar-width: none;
}

.log-container::-webkit-scrollbar { display: none; }

.log-entry {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
  line-height: 1.5;
}

.log-entry:last-child { margin-bottom: 0; }

.log-entry__time {
  color: var(--text-2);
  font-size: 11px;
  flex-shrink: 0;
}

.log-entry__desc {
  color: var(--text-inverse);
  flex: 1;
  min-width: 0;
  word-break: break-all;
}

.log-entry__status {
  flex-shrink: 0;
  font-weight: 500;
}

.log-empty {
  text-align: center;
  color: var(--text-2);
  padding: 16px;
  font-size: 12px;
}

/* Transition */
.slide-enter-active,
.slide-leave-active {
  transition: all 0.25s ease;
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* Small screens */
@media screen and (max-width: 380px) {
  .page__body {
    padding: 12px;
    gap: 12px;
  }

  .cap-card { padding: 12px; }
  .act-btn { padding: 10px 14px; font-size: 14px; }
}
</style>

<style scoped>
@media screen and (max-width: 767.98px) {
  .page {
    background: var(--surface-page);
  }

  .page__header {
    min-height: 44px;
    padding: 0 2px 10px;
    background: transparent;
    border-bottom: 0;
  }

  .page__back,
  .page__action {
    min-height: 38px;
    color: var(--text-secondary);
    background: var(--surface-primary);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-xs);
  }

  .page__back {
    padding: 0 11px 0 8px;
    font-size: 13px;
    font-weight: 700;
  }

  .page__action {
    width: 38px;
    height: 38px;
  }

  .page__scroll {
    background: transparent;
  }

  .page__body {
    padding: 0;
    gap: 12px;
  }

  .page__account-name {
    color: var(--text-primary);
    font-size: 16px;
    font-weight: 700;
  }

  .cap-section {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .cap-card {
    min-height: 68px;
    padding: 12px;
    background: var(--surface-primary);
    border: 1px solid var(--border-subtle);
    border-radius: 8px;
    box-shadow: none;
  }

  .cap-card--ok,
  .cap-card--err {
    background: var(--surface-primary);
  }

  .cap-card__label {
    color: var(--text-primary);
    font-weight: 700;
  }

  .cap-card__desc {
    color: var(--text-secondary);
  }

  .act-btn {
    min-height: 36px;
    border-radius: var(--radius-xs);
  }

  .cred-block {
    background: var(--surface-primary);
    border: 1px solid var(--border-subtle);
    border-radius: 8px;
    box-shadow: none;
    }

  .log-container {
    color: var(--text-primary);
    background: var(--surface-primary);
    border: 1px solid var(--border-subtle);
    border-radius: 8px;
  }

  .log-entry {
    padding: 8px 10px;
    border-bottom: 1px solid var(--border-subtle);
  }

  .log-entry:last-child {
    border-bottom: 0;
  }

  .log-entry__time,
  .log-empty {
    color: var(--text-tertiary);
  }

  .log-entry__desc {
    color: var(--text-secondary);
  }
}
</style>
