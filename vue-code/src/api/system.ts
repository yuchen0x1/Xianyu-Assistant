import { request } from '@/utils/request'

/** 获取当前用户信息 */
export function getCurrentUser() {
  return request<{ username: string; lastLoginTime: string }>({
    url: '/system/currentUser',
    method: 'post'
  })
}

/** 修改密码 */
export function changePassword(data: { oldPassword: string; newPassword: string; confirmPassword: string }) {
  return request<null>({
    url: '/system/changePassword',
    method: 'post',
    data
  })
}

/** 获取当前版本号 */
export function getVersion() {
  return request<string>({
    url: '/system/version',
    method: 'get'
  })
}

/** 获取不含敏感运行细节的本机服务状态 */
export function getSystemHealth() {
  return request<{ status: 'UP'; version: string; timestamp: number }>({
    url: '/system/health',
    method: 'get'
  })
}

/** 检查更新 */
export function checkUpdate() {
  return request<{
    currentVersion: string
    latestVersion: string
    hasUpdate: boolean
    updateContent: string
    publishedAt: string
    downloadUrl: string
    /** 服务端更新源状态（旧后端可能不返回）。 */
    checkStatus?: 'disabled' | 'available' | 'unavailable' | string
    /** 更新通道：默认 GitHub，兼容旧后端的 gitee。 */
    channel?: 'github' | 'gitee' | string
    message?: string
  }>({
    url: '/system/checkUpdate',
    method: 'get'
  })
}

/** 在线自更新状态 */
export interface SelfUpdateStatus {
  available: boolean
  unavailableReason?: string
  /** IDLE / DOWNLOADING / VERIFYING / SWAPPED / RESTARTING / FAILED */
  phase: string
  percent: number
  downloadedBytes: number
  totalBytes: number
  targetVersion?: string
  message?: string
  canRestart: boolean
}

/** 查询在线自更新状态 */
export function getSelfUpdateStatus() {
  return request<SelfUpdateStatus>({
    url: '/system/selfUpdate/status',
    method: 'get'
  })
}

/** 开始在线更新到目标版本（下载 → 校验 → 替换） */
export function applySelfUpdate(targetVersion: string) {
  return request<string>({
    url: '/system/selfUpdate/apply',
    method: 'post',
    data: { targetVersion }
  })
}

/** 新包就绪后执行重启 */
export function restartSelfUpdate() {
  return request<string>({
    url: '/system/selfUpdate/restart',
    method: 'post'
  })
}
