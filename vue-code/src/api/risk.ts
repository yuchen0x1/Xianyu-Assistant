import { request } from '@/utils/request'

// 账号级自动化保护（P0-6）
export interface RiskAutomationStatus {
  paused: boolean
  pauseReason?: string
  cooldownRemainingSeconds: number
}

/** 查询账号自动化状态 */
export function getRiskAutomationStatus(accountId: number) {
  return request<RiskAutomationStatus>({
    url: `/risk/accounts/${accountId}`,
    method: 'GET'
  })
}

/** 手动暂停账号自动化（持久化，重启后仍生效） */
export function pauseRiskAutomation(accountId: number, reason?: string) {
  return request<void>({
    url: `/risk/accounts/${accountId}/pause`,
    method: 'POST',
    data: reason ? { reason } : {}
  })
}

/** 手动恢复账号自动化（同时清除冷却） */
export function resumeRiskAutomation(accountId: number) {
  return request<void>({
    url: `/risk/accounts/${accountId}/resume`,
    method: 'POST'
  })
}
