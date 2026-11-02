import { ref, onMounted, onUnmounted } from 'vue'
import { showConfirm } from '@/utils/confirm'
import { getAccountList } from '@/api/account'
import { getConnectionStatus, startConnection, stopConnection } from '@/api/websocket'
import { showSuccess, showError, showInfo, accountDisplayName, accountAvatarChar} from '@/utils'
import type { Account } from '@/types'

interface ConnectionStatus {
  xianyuAccountId: number
  connected: boolean
  status: string
  cookieStatus?: number
  cookieConfigured?: boolean
  cookieLength?: number
  h5TokenConfigured?: boolean
  h5TokenLength?: number
  websocketTokenConfigured?: boolean
  websocketTokenLength?: number
  tokenExpireTime?: number
}

interface LogEntry {
  time: string
  message: string
  isError?: boolean
}

export function useConnectionManager() {
  const loading = ref(false)
  const accounts = ref<Account[]>([])
  const selectedAccountId = ref<number | null>(null)
  const connectionStatus = ref<ConnectionStatus | null>(null)
  const statusLoading = ref(false)
  const logs = ref<LogEntry[]>([])
  let statusInterval: number | null = null

  // 所有账号的连接状态（用于列表显示）
  const allConnectionStatuses = ref<Map<number, ConnectionStatus>>(new Map())

  const dialogs = ref({
    detail: false,
    manualCookie: false,
    manualToken: false,
    qrUpdate: false
  })

  // Load account list
  const loadAccounts = async () => {
    loading.value = true
    try {
      const response = await getAccountList()
      if (response.code === 0 || response.code === 200) {
        accounts.value = response.data?.accounts || []
        loadAllConnectionStatuses()
      } else {
        throw new Error(response.msg || '获取账号列表失败')
      }
    } catch (error) {
      if (!(error as Error & { messageShown?: boolean }).messageShown) {
        showError('加载账号列表失败: ' + (error as Error).message)
      }
      accounts.value = []
    } finally {
      loading.value = false
    }
  }

  const loadAllConnectionStatuses = async () => {
    const newMap = new Map<number, ConnectionStatus>()
    for (const account of accounts.value) {
      try {
        const accountId = Number(account.id)
        const response = await getConnectionStatus(accountId)
        if (response.code === 0 || response.code === 200) {
          const status = response.data as ConnectionStatus
          newMap.set(accountId, status)
        }
      } catch {
      }
    }
    allConnectionStatuses.value = newMap
  }

  // 请求序号：快速切换账号时只接受最新一次状态响应，避免旧账号状态覆盖当前展示
  let statusRequestSeq = 0

  // Load connection status
  const loadConnectionStatus = async (accountId: number, silent = false) => {
    const seq = ++statusRequestSeq
    if (!silent) {
      statusLoading.value = true
    }
    try {
      const response = await getConnectionStatus(accountId)
      if (seq !== statusRequestSeq) {
        return
      }
      if (response.code === 0 || response.code === 200) {
        connectionStatus.value = response.data as ConnectionStatus
        // 同步更新 allConnectionStatuses
        allConnectionStatuses.value.set(accountId, connectionStatus.value)
        // 触发响应式更新
        allConnectionStatuses.value = new Map(allConnectionStatuses.value)
        if (!silent) {
          addLog('状态已更新')
        }
      } else {
        throw new Error(response.msg || '获取连接状态失败')
      }
    } catch (error) {
      if (seq !== statusRequestSeq) {
        return
      }
      if (!silent) {
        addLog('加载状态失败: ' + (error as Error).message, true)
      }
    } finally {
      if (seq === statusRequestSeq) {
        statusLoading.value = false
      }
    }
  }

  // Select account
  const selectAccount = (accountId: number) => {
    selectedAccountId.value = accountId
    loadConnectionStatus(accountId)
    startAutoRefresh()
  }

  // Start auto refresh
  // 优化：将刷新间隔从5秒延长到10秒，减少不必要的频繁查询
  const startAutoRefresh = () => {
    stopAutoRefresh()
    statusInterval = window.setInterval(() => {
      if (selectedAccountId.value) {
        loadConnectionStatus(selectedAccountId.value, true)
      }
    }, 10000) // 从5000ms延长到10000ms（10秒）
  }

  // Stop auto refresh
  const stopAutoRefresh = () => {
    if (statusInterval) {
      clearInterval(statusInterval)
      statusInterval = null
    }
  }

  // Start connection
  const handleStartConnection = async () => {
    if (!selectedAccountId.value) return
    statusLoading.value = true
    addLog('正在启动连接...')
    try {
      const response = await startConnection(selectedAccountId.value)
      if (response.code === 0 || response.code === 200) {
        showSuccess('连接启动成功')
        addLog('连接启动成功')
        await loadConnectionStatus(selectedAccountId.value)
      } else if (response.code === 1001 && response.data?.needCaptcha) {
        addLog('检测到需要官方验证，请在闲鱼 App 完成验证后扫码更新 Cookie/Token', true)
        await showConfirm(
          `检测到账号需要完成闲鱼官方验证。\n\n` +
          `操作步骤：\n\n` +
          `1. 打开闲鱼 App，完成手机/安全验证\n\n` +
          `2. 返回本页点击“扫码更新”，使用官方扫码流程同步 Cookie 和 Token\n\n` +
          `3. 更新成功后再启动连接\n\n` +
          `提示：请勿连续重试，平台风控期间连续请求可能延长限制。`,
          '需要官方验证'
        )
        showInfo('请在闲鱼 App 完成验证后，使用“扫码更新”同步凭据')
      } else {
        throw new Error(response.msg || '启动连接失败')
      }
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        addLog('启动连接失败: ' + (error as Error).message, true)
      }
    } finally {
      statusLoading.value = false
    }
  }

  // Stop connection
  const handleStopConnection = async () => {
    if (!selectedAccountId.value) return
    try {
      await showConfirm(
        '断开连接后将无法接收消息和执行自动化流程，确定要断开连接吗？',
        '确认断开连接'
      )
    } catch {
      return
    }
    statusLoading.value = true
    addLog('正在断开连接...')
    try {
      const response = await stopConnection(selectedAccountId.value)
      if (response.code === 0 || response.code === 200) {
        showSuccess('连接已断开')
        addLog('连接已断开')
        await loadConnectionStatus(selectedAccountId.value)
      } else {
        throw new Error(response.msg || '断开连接失败')
      }
    } catch (error) {
      addLog('断开连接失败: ' + (error as Error).message, true)
    } finally {
      statusLoading.value = false
    }
  }

  // Refresh
  const handleRefresh = () => {
    if (selectedAccountId.value) {
      loadConnectionStatus(selectedAccountId.value)
      showInfo('状态已刷新')
    }
  }

  // Add log
  const addLog = (message: string, isError = false) => {
    const time = new Date().toLocaleTimeString()
    logs.value.push({ time, message, isError })
    if (logs.value.length > 50) {
      logs.value.shift()
    }
  }

  // Helpers
  const getAccountName = (account: Account) => {
    return accountDisplayName(account)
  }

  const getAccountAvatar = (account: Account) => {
    return accountAvatarChar(account)
  }

  const getCookieStatusText = (status?: number) => {
    if (status === undefined || status === null) return '未知'
    const map: Record<number, string> = { 1: '有效', 2: '过期', 3: '失效' }
    return map[status] || '未知'
  }

  const getCookieStatusType = (status?: number) => {
    if (status === undefined || status === null) return 'neutral'
    const map: Record<number, string> = { 1: 'success', 2: 'warning', 3: 'danger' }
    return map[status] || 'neutral'
  }

  const formatTimestamp = (timestamp?: number) => {
    if (!timestamp) return '未设置'
    return new Date(timestamp).toLocaleString('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    })
  }

  const isTokenExpired = (timestamp?: number) => {
    if (!timestamp) return false
    return Date.now() > timestamp
  }

  const getTokenStatusText = (timestamp?: number) => {
    if (!timestamp) return '未设置'
    return isTokenExpired(timestamp) ? '已过期' : '有效'
  }

  const getTokenStatusType = (timestamp?: number) => {
    if (!timestamp) return 'neutral'
    return isTokenExpired(timestamp) ? 'danger' : 'success'
  }

  onMounted(async () => {
    await loadAccounts()
  })

  onUnmounted(() => {
    stopAutoRefresh()
  })

  return {
    loading,
    accounts,
    selectedAccountId,
    connectionStatus,
    statusLoading,
    logs,
    dialogs,
    allConnectionStatuses,
    loadAccounts,
    selectAccount,
    handleStartConnection,
    handleStopConnection,
    handleRefresh,
    getAccountName,
    getAccountAvatar,
    getCookieStatusText,
    getCookieStatusType,
    formatTimestamp,
    getTokenStatusText,
    getTokenStatusType,
    addLog,
    loadConnectionStatus
  }
}
