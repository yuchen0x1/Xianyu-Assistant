import { request } from '@/utils/request'

export type AlertStatus = 'ACTIVE' | 'ACKED' | 'SILENCED' | 'RESOLVED'

export interface AlertEvent {
  id: number
  alertKey: string
  title: string
  message: string
  level: string
  accountId: number | null
  status: AlertStatus
  firstSeenAt: string
  lastSeenAt: string
  occurrences: number
  lastSentAt: string
  nextSendAt: string
  sendAttempts: number
  createdTime: string
  updatedTime: string
}

export interface MonitoringOverview {
  activeAlerts: number
  deliveryFailures: number
  pendingDeadLetters: number
  notificationChannels: {
    configured: boolean
  }
}

/** 查询告警列表 */
export function getAlertList(status?: string, accountId?: number, limit = 100) {
  const params: Record<string, string | number> = { limit }
  if (status) params.status = status
  if (accountId) params.accountId = accountId
  return request<AlertEvent[]>({ url: '/monitoring/alerts', method: 'GET', params })
}

/** 单条告警详情 */
export function getAlert(key: string) {
  return request<AlertEvent>({ url: '/monitoring/alerts/key', method: 'GET', params: { key } })
}

/** 告警操作：ack / silence / unsilence / resolve */
export function operateAlert(key: string, action: 'ack' | 'silence' | 'unsilence' | 'resolve') {
  return request<void>({ url: '/monitoring/alerts', method: 'POST', data: { key, action } })
}

/** 监控概览；后端可能返回 null，调用方需兼容。 */
export function getMonitoringOverview() {
  return request<MonitoringOverview | null>({ url: '/monitoring/overview', method: 'GET' })
}