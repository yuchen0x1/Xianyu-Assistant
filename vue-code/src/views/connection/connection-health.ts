import type { WebSocketStatus } from '@/api/websocket'

export type AutomationHealthTone = 'success' | 'warning' | 'danger' | 'info' | 'muted'

export interface AutomationHealthView {
  state: 'captcha' | 'reconnecting' | 'offline' | 'disabled' | 'failed' | 'skipped' | 'active' | 'success' | 'ready'
  tone: AutomationHealthTone
  title: string
  detail: string
  action: 'captcha' | 'connect' | 'none'
}

export interface AutoReplyCardView {
  tone: 'success' | 'warning' | 'danger'
  label: '已开启' | '已开启，当前暂停' | '未开启'
}

const reasonText: Record<string, string> = {
  NO_ACTIVITY: '连接已就绪，尚未收到客户消息',
  CUSTOMER_MESSAGE: '已收到客户消息，正在判断回复条件',
  DELAY_PENDING: '客户消息已进入延时回复队列',
  GENERATING_REPLY: '正在生成并发送回复',
  SENT: '最近一次自动回复发送成功',
  MISSING_CONTEXT: '消息缺少商品或会话信息，未触发回复',
  EMPTY_CONTENT: '消息内容为空，未触发回复',
  HUMAN_TAKEOVER: '该会话处于人工接管状态',
  REPLY_DISABLED: '对应商品未开启自动回复',
  GOODS_NOT_FOUND: '未找到对应的本地商品信息',
  NO_REPLY_STRATEGY: '没有可用的回复策略',
  DUPLICATE_MESSAGE: '该消息已经处理过',
  RECORD_CREATE_FAILED: '回复记录创建失败',
  NO_REPLY_CONTENT: '回复策略没有生成有效内容',
  SEND_FAILED: '消息发送失败，请检查连接状态',
  LISTENER_ERROR: '客户消息处理异常',
  EXECUTION_ERROR: '自动回复执行异常',
}

export function getAutomationReasonText(reason?: string): string {
  return reasonText[reason || ''] || '等待新的运行状态'
}

export function deriveAutoReplyCard(status: WebSocketStatus): AutoReplyCardView {
  if (!status.autoReplyOn) {
    return { tone: 'danger', label: '未开启' }
  }
  if (!status.connected) {
    return { tone: 'warning', label: '已开启，当前暂停' }
  }
  return { tone: 'success', label: '已开启' }
}

export function deriveAutomationHealth(status: WebSocketStatus): AutomationHealthView {
  // 官方验证状态优先于可能滞后的 WebSocket 已连接状态展示。
  if (status.captchaPending) {
    return {
      state: 'captcha', tone: 'warning', title: '等待官方验证',
      detail: '请在闲鱼 App 完成验证后扫码更新 Cookie/Token，暂不要连续重试', action: 'captcha',
    }
  }
  if (status.connected) {
    if (!status.autoReplyOn) {
      return {
        state: 'disabled', tone: 'muted', title: '自动回复未开启',
        detail: '连接正常，但当前没有商品启用自动回复', action: 'none',
      }
    }
    if (status.lastAutoReplyStatus === 'FAILED') {
      return {
        state: 'failed', tone: 'danger', title: '最近一次回复失败',
        detail: getAutomationReasonText(status.lastAutoReplyReason), action: 'none',
      }
    }
    if (status.lastAutoReplyStatus === 'SKIPPED') {
      return {
        state: 'skipped', tone: 'warning', title: '最近一条消息未回复',
        detail: getAutomationReasonText(status.lastAutoReplyReason), action: 'none',
      }
    }
    if (status.lastAutoReplyStatus === 'QUEUED' || status.lastAutoReplyStatus === 'RUNNING' || status.lastAutoReplyStatus === 'RECEIVED') {
      return {
        state: 'active', tone: 'info', title: '正在处理客户消息',
        detail: getAutomationReasonText(status.lastAutoReplyReason), action: 'none',
      }
    }
    if (status.lastAutoReplyStatus === 'SUCCESS') {
      return {
        state: 'success', tone: 'success', title: '自动回复运行正常',
        detail: getAutomationReasonText(status.lastAutoReplyReason), action: 'none',
      }
    }
    return {
      state: 'ready', tone: 'success', title: '自动回复待命',
      detail: '连接正常，正在等待客户消息', action: 'none',
    }
  }
  if (status.reconnecting) {
    return {
      state: 'reconnecting', tone: 'warning', title: '正在自动重连',
      detail: status.reconnectAttempt ? `正在进行第 ${status.reconnectAttempt} 次恢复尝试` : '恢复任务已进入队列',
      action: 'none',
    }
  }
  return {
    state: 'offline', tone: 'danger', title: '连接已断开',
    detail: '当前无法接收客户消息，也不会触发自动回复', action: 'connect',
  }
}
