import { request } from '@/utils/request'
import type { QRLoginSession } from '@/types'

// 生成二维码
export function generateQRCode(accountId?: number) {
  return request<QRLoginSession>({
    url: '/qrlogin/generate',
    method: 'POST',
    params: accountId === undefined ? undefined : { accountId }
  })
}

// 查询二维码状态
export function getQRCodeStatus(sessionId: string) {
  return request<QRLoginSession>({
    url: `/qrlogin/status/${sessionId}`,
    method: 'POST'
  })
}

// 清理过期会话
export function cleanupQRSessions() {
  return request({
    url: '/qrlogin/cleanup',
    method: 'POST'
  })
}
