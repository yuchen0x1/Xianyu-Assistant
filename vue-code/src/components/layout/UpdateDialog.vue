<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { applySelfUpdate, checkUpdate, getSelfUpdateStatus, getSystemHealth, restartSelfUpdate } from '@/api/system'
import type { SelfUpdateStatus } from '@/api/system'
import IconClose from '@/components/icons/IconClose.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconSparkle from '@/components/icons/IconSparkle.vue'

declare const __APP_VERSION__: string

const appVersion = __APP_VERSION__ || '3.5.21'

const visible = ref(false)
const loading = ref(false)
const dialogContainer = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
let previouslyFocusedElement: HTMLElement | null = null
const updateInfo = ref<{
  currentVersion: string
  latestVersion: string
  hasUpdate: boolean
  updateContent: string
  publishedAt: string
  downloadUrl: string
  checkStatus?: 'disabled' | 'available' | 'unavailable' | string
  /** 更新通道：默认 GitHub，兼容旧后端的 gitee。 */
  channel?: 'github' | 'gitee' | string
  message?: string
} | null>(null)

// ============ 在线更新 ============
const selfUpdate = ref<SelfUpdateStatus | null>(null)
const updating = ref(false)
const updateError = ref('')
const restarting = ref(false)
let statusPollTimer: ReturnType<typeof setTimeout> | null = null
let healthPollTimer: ReturnType<typeof setTimeout> | null = null

const PHASE_DOWNLOADING = 'DOWNLOADING'
const PHASE_VERIFYING = 'VERIFYING'
const PHASE_SWAPPED = 'SWAPPED'
const PHASE_FAILED = 'FAILED'

const updatePhaseLabel = computed(() => {
  const phase = selfUpdate.value?.phase
  if (phase === PHASE_DOWNLOADING) {
    const total = selfUpdate.value?.totalBytes || 0
    const downloaded = selfUpdate.value?.downloadedBytes || 0
    const sizeText = total > 0 ? `（${formatSize(downloaded)} / ${formatSize(total)}）` : ''
    return `正在下载新版本 ${selfUpdate.value?.percent || 0}%${sizeText}`
  }
  if (phase === PHASE_VERIFYING) return '正在校验安装包...'
  if (phase === PHASE_SWAPPED) return '新版本已就绪'
  if (phase === 'RESTARTING') return '服务正在重启...'
  return ''
})

const formatSize = (bytes: number) => {
  if (bytes >= 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return Math.round(bytes / 1024) + ' KB'
}

const readMessage = (value: unknown) => typeof value === 'string' && value.trim() ? value.trim() : ''

const getResponseMessage = (response: unknown) => {
  if (!response || typeof response !== 'object') return ''
  const candidate = response as {
    msg?: unknown
    message?: unknown
    data?: unknown
  }
  return readMessage(candidate.msg)
    || readMessage(candidate.message)
    || readMessage(candidate.data)
}

const getErrorMessage = (error: unknown, fallback: string) => {
  if (error && typeof error === 'object') {
    const candidate = error as {
      message?: unknown
      response?: { data?: { msg?: unknown; message?: unknown } }
    }
    return readMessage(candidate.response?.data?.msg)
      || readMessage(candidate.response?.data?.message)
      || readMessage(candidate.message)
      || fallback
  }
  return readMessage(error) || fallback
}

const isSuccessfulResponse = (response: { code?: number }) =>
  response.code === 0 || response.code === 200

const isManualRestartStatus = (status: SelfUpdateStatus | null) =>
  status?.phase === PHASE_SWAPPED && /手动重启|人工重启/.test(status.message || '')

const finishManualRestart = (status: SelfUpdateStatus) => {
  selfUpdate.value = status
  restarting.value = false
  updating.value = false
  updateError.value = status.message || '新版本已就绪，请手动重启服务'
}

const loadSelfUpdateStatus = async () => {
  try {
    const res = await getSelfUpdateStatus()
    selfUpdate.value = res.data || null
    if (!res.data) {
      if (!updateError.value) updateError.value = getResponseMessage(res) || '无法获取在线更新状态，请稍后重试'
      return
    }
    if (res.data.phase === PHASE_FAILED) {
      updateError.value = res.data.message || '在线更新失败'
    } else if (!res.data.available && res.data.unavailableReason && !updateError.value) {
      updateError.value = res.data.unavailableReason
    }
  } catch (e) {
    selfUpdate.value = null
    if (!updateError.value) {
      updateError.value = getErrorMessage(e, '无法获取在线更新状态，请确认服务已启动后重试')
    }
  }
}

const startOnlineUpdate = async () => {
  if (!updateInfo.value || updating.value) return
  updateError.value = ''
  updating.value = true
  try {
    const res = await applySelfUpdate(updateInfo.value.latestVersion)
    if (res.code !== 200 && res.code !== 0) {
      updateError.value = res.msg || '无法开始在线更新'
      updating.value = false
      return
    }
    scheduleStatusPoll()
  } catch (e) {
    updateError.value = (e as Error)?.message || '无法开始在线更新'
    updating.value = false
  }
}

const scheduleStatusPoll = () => {
  if (statusPollTimer) clearTimeout(statusPollTimer)
  statusPollTimer = setTimeout(async () => {
    try {
      const res = await getSelfUpdateStatus()
      if (res.data) selfUpdate.value = res.data
    } catch (e) {
      if (!updateError.value) updateError.value = getErrorMessage(e, '在线更新状态获取失败，请稍后重试')
    }
    const phase = selfUpdate.value?.phase
    if (phase === PHASE_SWAPPED) {
      await triggerRestart()
      return
    }
    if (phase === PHASE_FAILED) {
      updateError.value = selfUpdate.value?.message || '在线更新失败'
      updating.value = false
      return
    }
    if (updating.value && visible.value) {
      scheduleStatusPoll()
    }
  }, 1000)
}

const triggerRestart = async () => {
  restarting.value = true
  try {
    const res = await restartSelfUpdate()
    if (!isSuccessfulResponse(res)) {
      updateError.value = getResponseMessage(res) || '无法重启服务，请稍后重试'
      updating.value = false
      restarting.value = false
      return
    }
  } catch (e) {
    updateError.value = getErrorMessage(e, '重启服务请求失败，请确认服务状态后重试')
    updating.value = false
    restarting.value = false
    return
  }

  // manual 模式不会让服务退出；替换完成后后端会恢复 SWAPPED 并附带手动重启提示。
  await new Promise(resolve => setTimeout(resolve, 1200))
  try {
    const res = await getSelfUpdateStatus()
    if (res.data) {
      selfUpdate.value = res.data
      if (isManualRestartStatus(res.data)) {
        finishManualRestart(res.data)
        return
      }
    }
  } catch {
    // exit/self 模式下服务可能已开始退出，交给健康探测判断。
  }
  await waitUntilBackOnline()
}

const waitUntilBackOnline = async () => {
  const target = updateInfo.value?.latestVersion
  const startedAt = Date.now()
  const poll = async () => {
    try {
      const statusRes = await getSelfUpdateStatus()
      if (statusRes.data) {
        selfUpdate.value = statusRes.data
        if (isManualRestartStatus(statusRes.data)) {
          finishManualRestart(statusRes.data)
          return
        }
      }
    } catch {
      // 服务重启中，继续探测健康状态
    }
    try {
      const res = await getSystemHealth()
      const version = res.data?.version
      if (version && target && version === target) {
        updating.value = false
        restarting.value = false
        // 更新成功：刷新页面加载新前端资源
        window.location.reload()
        return
      }
    } catch {
      // 服务重启中，继续等待
    }
    if (Date.now() - startedAt > 180000) {
      restarting.value = false
      updating.value = false
      updateError.value = '等待服务重启超时，请检查服务是否配置了自动重启（如 systemd Restart=always），或手动重启后刷新页面'
      return
    }
    healthPollTimer = setTimeout(poll, 2000)
  }
  // 重启刚触发，先等几秒再开始探测
  healthPollTimer = setTimeout(poll, 5000)
}

const stopPollTimers = () => {
  if (statusPollTimer) { clearTimeout(statusPollTimer); statusPollTimer = null }
  if (healthPollTimer) { clearTimeout(healthPollTimer); healthPollTimer = null }
}

onUnmounted(stopPollTimers)

type UpdateDisplayState = 'update' | 'latest' | 'warning'

const isMobile = ref(false)

const checkMobile = () => {
  isMobile.value = window.innerWidth < 768
}

const updateDisplayState = computed<UpdateDisplayState>(() => {
  const info = updateInfo.value
  if (!info) return 'warning'

  // Older servers do not send checkStatus. Preserve their established
  // hasUpdate contract while treating explicit non-success states as warnings.
  if (info.checkStatus === undefined || info.checkStatus === null || info.checkStatus === '') {
    return info.hasUpdate ? 'update' : 'latest'
  }
  if (info.checkStatus !== 'available') return 'warning'
  return info.hasUpdate ? 'update' : 'latest'
})
const isUpdateAvailable = computed(() => updateDisplayState.value === 'update')
const canOpenUpdate = computed(() => isUpdateAvailable.value && Boolean(updateInfo.value?.downloadUrl))
// 服务端支持在线自更新（以 JAR 部署）时才显示"立即在线更新"
const canUpdateOnline = computed(() =>
  isUpdateAvailable.value && Boolean(selfUpdate.value?.available) && !updating.value
  && !isManualRestartStatus(selfUpdate.value))
const displayCurrentVersion = computed(() => updateInfo.value?.currentVersion || appVersion)
// 下载/查看入口始终使用后端返回的 downloadUrl，这里仅用于展示来源。
const updateChannelLabel = computed(() => {
  const channel = updateInfo.value?.channel
  if (channel === 'github') return 'GitHub'
  if (channel === 'gitee') return 'Gitee'
  return ''
})
const statusLabel = computed(() => {
  if (updateDisplayState.value === 'update') return '发现新版本'
  if (updateDisplayState.value === 'latest') return '已是最新版本'
  if (updateInfo.value?.checkStatus === 'disabled') return '更新检查未启用'
  if (updateInfo.value?.checkStatus === 'unavailable') return '无法确认最新版本'
  return '更新状态未知'
})
const sourceMessage = computed(() => {
  if (updateError.value) return updateError.value
  if (!updateInfo.value) return ''
  if (updateInfo.value.checkStatus === 'unavailable') {
    return updateInfo.value.message || '更新源暂时不可用，请检查仓库权限或网络连接'
  }
  if (updateInfo.value.checkStatus === 'disabled') {
    return updateInfo.value.message || '在线更新检查未启用'
  }
  if (updateDisplayState.value === 'warning') {
    return updateInfo.value.message || '未能确认更新源状态，请重新检查'
  }
  return ''
})

const formattedDate = computed(() => {
  if (!updateInfo.value?.publishedAt) return ''
  const d = new Date(updateInfo.value.publishedAt)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
})

// 更新源（Gitee Release body）是 Markdown 源码，弹窗按纯文本展示：
// 去掉加粗/引用/标题/行内代码标记，保留列表与换行，纯文本展示避免引入渲染依赖与 XSS 面。
const changelogText = computed(() => {
  const raw = updateInfo.value?.updateContent
  if (!raw) return ''
  return raw
    .split(/\r?\n/)
    .filter(line => !/^>\s*Release:/i.test(line.trim()))
    .map(line => line
      .replace(/^\s*>\s?/, '')
      .replace(/^\s*#{1,6}\s+/, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/__([^_]+)__/g, '$1')
      .replace(/`([^`]+)`/g, '$1'))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
})

const getDialogFocusableElements = () => {
  if (!dialogContainer.value) return []
  return Array.from(dialogContainer.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )).filter(element => element.getClientRects().length > 0 && element.getAttribute('aria-hidden') !== 'true')
}

const focusDialog = async () => {
  await nextTick()
  closeButton.value?.focus()
  if (document.activeElement !== closeButton.value) {
    dialogContainer.value?.focus()
  }
}

const performCheck = async () => {
  loading.value = true
  updateError.value = ''
  try {
    const res = await checkUpdate()
    updateInfo.value = res.data || null
    if (!res.data) {
      updateError.value = getResponseMessage(res) || '更新检查未返回有效结果，请稍后重试'
    }
  } catch (e) {
    updateInfo.value = null
    updateError.value = getErrorMessage(e, '无法连接更新服务，请确认本机服务已启动后重试')
  } finally {
    loading.value = false
  }
  await loadSelfUpdateStatus()
  // 新包已替换完成但重启尚未执行（如下载期间关闭过弹窗或刷新过页面）时，
  // 打开弹窗即自动续跑重启与探活流程，不需要用户再手动点一次。
  if (selfUpdate.value?.phase === PHASE_FAILED && selfUpdate.value.message) {
    updateError.value = selfUpdate.value.message
  }
  if (selfUpdate.value?.phase === PHASE_SWAPPED && isManualRestartStatus(selfUpdate.value)) {
    finishManualRestart(selfUpdate.value)
    return
  }
  if (selfUpdate.value?.phase === PHASE_SWAPPED && !updating.value && !restarting.value) {
    updating.value = true
    await triggerRestart()
  }
}

const open = async () => {
  if (!visible.value) {
    previouslyFocusedElement = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
  }
  checkMobile()
  updateError.value = ''
  visible.value = true
  await focusDialog()
  await performCheck()
}

const retry = () => {
  void performCheck()
}

const close = async () => {
  const elementToRestore = previouslyFocusedElement
  previouslyFocusedElement = null
  visible.value = false
  await nextTick()
  if (elementToRestore?.isConnected && !elementToRestore.closest('[inert]')) {
    elementToRestore.focus()
  }
}

const handleDialogKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    void close()
    return
  }

  if (event.key !== 'Tab') return
  event.stopPropagation()

  const focusableElements = getDialogFocusableElements()
  if (!focusableElements.length) {
    event.preventDefault()
    dialogContainer.value?.focus()
    return
  }

  const firstElement = focusableElements[0]
  const lastElement = focusableElements[focusableElements.length - 1]
  const activeElement = document.activeElement
  const focusIsOutsideDialog = !activeElement || !dialogContainer.value?.contains(activeElement)

  if (event.shiftKey && (activeElement === firstElement || focusIsOutsideDialog)) {
    event.preventDefault()
    lastElement?.focus()
  } else if (!event.shiftKey && (activeElement === lastElement || focusIsOutsideDialog)) {
    event.preventDefault()
    firstElement?.focus()
  }
}

const openDownload = () => {
  if (updateInfo.value?.downloadUrl) {
    window.open(updateInfo.value.downloadUrl, '_blank', 'noopener,noreferrer')
  }
}

defineExpose({ open })
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal-overlay" @click.self="close">
        <div
          ref="dialogContainer"
          class="modal-container"
          :class="{ 'is-mobile': isMobile }"
          role="dialog"
          aria-modal="true"
          aria-labelledby="update-dialog-title"
          tabindex="-1"
          @keydown="handleDialogKeydown"
        >
          <!-- Header -->
          <div class="modal-header">
            <div class="modal-title-wrap">
              <div class="modal-icon">
                <IconSparkle />
              </div>
              <h2 id="update-dialog-title" class="modal-title">版本更新</h2>
            </div>
            <button ref="closeButton" class="modal-close" type="button" aria-label="关闭版本更新弹窗" title="关闭" @click="close">
              <IconClose aria-hidden="true" />
            </button>
          </div>

          <!-- Loading -->
          <div v-if="loading" class="modal-loading" role="status" aria-live="polite">
            <div class="loading-spinner" aria-hidden="true"></div>
            <span>正在检查更新...</span>
          </div>

          <!-- Content -->
          <div v-else-if="updateInfo" class="modal-body">
            <!-- Version Info - 横向紧凑布局 -->
            <div class="version-row">
              <div class="version-item">
                <span class="version-label">当前版本</span>
                <span class="version-value">v{{ displayCurrentVersion }}</span>
              </div>
              <div v-if="updateDisplayState !== 'warning'" class="version-item">
                <span class="version-label">最新版本</span>
                <span class="version-value" :class="{ 'is-new': isUpdateAvailable }">v{{ updateInfo.latestVersion }}</span>
              </div>
              <div v-if="formattedDate" class="version-item">
                <span class="version-label">发布时间</span>
                <span class="version-value">{{ formattedDate }}</span>
              </div>
              <div v-if="updateChannelLabel" class="version-item">
                <span class="version-label">更新通道</span>
                <span class="version-value">{{ updateChannelLabel }}</span>
              </div>
            </div>

            <!-- Status Badge -->
            <div
              class="status-badge"
              :class="{
                'is-updated': updateDisplayState === 'latest',
                'is-warning': updateDisplayState === 'warning'
              }"
              role="status"
            >
              <IconCheck v-if="updateDisplayState === 'latest'" />
              <span>{{ statusLabel }}</span>
            </div>

            <div v-if="sourceMessage" class="source-message" role="status">
              {{ sourceMessage }}
            </div>

            <!-- Changelog -->
            <div v-if="changelogText" class="changelog">
              <div class="changelog-title">更新内容</div>
              <div class="changelog-content">{{ changelogText }}</div>
            </div>

            <!-- 在线更新进度 -->
            <div v-if="updating || restarting" class="update-progress" role="status" aria-live="polite">
              <div class="update-progress-label">
                {{ restarting ? '服务正在重启，页面将自动刷新...' : updatePhaseLabel }}
              </div>
              <div v-if="!restarting && selfUpdate?.phase === PHASE_DOWNLOADING" class="update-progress-bar">
                <div class="update-progress-fill" :style="{ width: (selfUpdate.percent || 0) + '%' }"></div>
              </div>
              <div v-if="restarting" class="loading-spinner is-small"></div>
            </div>
          </div>

          <!-- Error -->
          <div v-else class="modal-error" role="alert">
            <span>{{ updateError || '检查更新失败，请稍后重试' }}</span>
            <button class="btn btn-secondary" type="button" @click="retry">重新检查</button>
          </div>

          <!-- Footer -->
          <div v-if="!loading && updateInfo" class="modal-footer">
            <button v-if="updateDisplayState === 'warning'" class="btn btn-secondary" type="button" @click="retry">重新检查</button>
            <button class="btn btn-secondary" type="button" :disabled="updating" @click="close">关闭</button>
            <button
              v-if="canUpdateOnline"
              class="btn btn-primary"
              type="button"
              @click="startOnlineUpdate"
            >
              立即在线更新
            </button>
            <button v-if="canOpenUpdate && !canUpdateOnline && !updating" class="btn btn-primary" type="button" @click="openDownload">
              查看更新
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
  width: 100%;
  max-width: 540px;
  aspect-ratio: 4 / 3;
  max-height: 88vh;
  box-shadow:
    0 32px 100px rgba(0, 0, 0, 0.14),
    0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.source-message {
  margin-top: -6px;
  padding: 9px 12px;
  color: var(--text-2);
  font-size: var(--text-sm);
  line-height: 1.45;
  background: color-mix(in srgb, var(--warning-500) 10%, var(--bg-muted));
  border: 1px solid color-mix(in srgb, var(--warning-500) 24%, transparent);
  border-radius: var(--radius-sm);
}

.modal-container.is-mobile {
  max-width: 400px;
  aspect-ratio: 9 / 16;
  border-radius: var(--radius-sm);
}

/* Header - 紧凑 */
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-icon {
  width: 28px;
  height: 28px;
  background: var(--brand-600);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-icon svg {
  width: 15px;
  height: 15px;
  color: #fff;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
  letter-spacing: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}

.modal-close svg {
  width: 12px;
  height: 12px;
}

/* Loading */
.modal-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 60px 24px;
  color: var(--text-3);
  font-size: 14px;
  flex: 1;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 2px solid var(--bg-muted);
  border-top-color: var(--brand-600);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Body - 主要内容区域 */
.modal-body {
  padding: 0 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex: 1;
  overflow-y: auto;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  background: var(--bg-muted);
  border-radius: var(--radius-sm);
}

.version-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-item:not(:last-child)::after {
  content: '';
  width: 1px;
  height: 20px;
  background: rgba(0, 0, 0, 0.08);
  margin-left: 8px;
}

.version-label {
  font-size: 12px;
  color: var(--text-3);
  font-weight: 500;
}

.version-value {
  font-size: 14px;
  color: var(--text-1);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.version-value.is-new {
  color: var(--text-brand);
}

/* Status Badge */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 7px 14px;
  background: color-mix(in srgb, var(--brand-600) 10%, transparent);
  border-radius: var(--radius-pill);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-brand);
}

.status-badge svg {
  width: 14px;
  height: 14px;
}

.status-badge.is-updated {
  background: color-mix(in srgb, var(--success-600) 10%, transparent);
  color: var(--success-600);
}

.status-badge.is-warning {
  color: var(--warning-600);
  background: color-mix(in srgb, var(--warning-500) 12%, transparent);
}

/* Changelog */
.changelog {
  margin-top: 2px;
}

/* 在线更新进度 */
.update-progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--brand-600) 8%, var(--bg-muted));
  border: 1px solid color-mix(in srgb, var(--brand-600) 20%, transparent);
  border-radius: var(--radius-sm);
}

.update-progress-label {
  font-size: 13px;
  color: var(--text-1);
  font-weight: 500;
}

.update-progress-bar {
  height: 6px;
  border-radius: 999px;
  background: var(--border-1);
  overflow: hidden;
}

.update-progress-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--brand-600);
  transition: width 0.6s ease;
}

.loading-spinner.is-small {
  width: 16px;
  height: 16px;
  border-width: 2px;
}

.changelog-title {
  font-size: 13px;
  color: var(--text-3);
  font-weight: 500;
  margin-bottom: 10px;
}

.changelog-content {
  font-size: 14px;
  color: var(--text-1);
  line-height: 1.6;
  white-space: pre-wrap;
  background: var(--bg-muted);
  padding: 16px 18px;
  border-radius: var(--radius-sm);
  max-height: 160px;
  overflow-y: auto;
}

.changelog-content::-webkit-scrollbar {
  width: 5px;
}

.changelog-content::-webkit-scrollbar-track {
  background: transparent;
}

.changelog-content::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.12);
  border-radius: 3px;
}

/* Error */
.modal-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 24px;
  color: var(--text-3);
  font-size: 14px;
  flex: 1;
}

/* Footer - 紧凑 */
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: var(--bg-hover);
  color: var(--text-1);
}

.btn-secondary:hover {
  background: var(--bg-active);
}

.btn-primary {
  background: var(--brand-600);
  color: #fff;
}

.btn-primary:hover {
  background: var(--brand-700);
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}

/* Mobile */
@media (max-width: 480px) {
  .modal-container {
    max-width: 92vw;
    border-radius: var(--radius-sm);
  }
  .modal-container.is-mobile {
    aspect-ratio: 9 / 16;
  }
  .modal-body {
    padding: 0 20px 20px;
    gap: 14px;
  }
  .version-row {
    flex-wrap: wrap;
    gap: 10px;
    padding: 12px 14px;
  }
  .version-item:not(:last-child)::after {
    display: none;
  }
  .changelog-content {
    max-height: 140px;
  }
}

/* Shared design tokens keep the update dialog legible in both themes. */
.modal-overlay {
  background: color-mix(in srgb, var(--bg-inverse) 54%, transparent);
}

.modal-container {
  position: relative;
  color: var(--text-1);
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  aspect-ratio: auto;
  min-height: 300px;
  box-shadow: var(--shadow-lg), 0 0 0 1px color-mix(in srgb, var(--brand-500) 10%, transparent);
}

.modal-header {
  border-bottom: 1px solid var(--border-1);
}

.modal-icon {
  background: var(--gradient-brand);
  box-shadow: 0 8px 20px color-mix(in srgb, var(--brand-600) 28%, transparent);
}

.modal-title,
.version-value,
.changelog-content,
.btn-secondary {
  color: var(--text-1);
}

.modal-close { color: var(--text-3); }
.modal-close:hover { color: var(--text-1); background: var(--bg-hover); }
.modal-loading,
.modal-error,
.version-label,
.changelog-title { color: var(--text-2); }

.version-row,
.changelog-content { background: var(--bg-muted); }
.version-item:not(:last-child)::after { background: var(--border-1); }
.version-value.is-new { color: var(--brand-500); }
.status-badge { color: var(--brand-600); background: var(--info-50); }
.status-badge.is-updated { color: var(--success-600); background: var(--success-50); }
.status-badge.is-warning { color: var(--warning-600); background: var(--warning-50); }
.modal-footer { border-top: 1px solid var(--border-1); }
.btn-secondary { background: var(--bg-hover); }
.btn-secondary:hover { background: color-mix(in srgb, var(--text-2) 14%, var(--bg-hover)); }
.btn-primary { color: #fff; background: var(--brand-600); }
.btn-primary:hover { background: var(--brand-700); }
.loading-spinner { border-color: var(--border-2); border-top-color: var(--brand-600); }

@media (max-width: 480px) {
  .modal-container { min-height: 0; }
}
</style>
