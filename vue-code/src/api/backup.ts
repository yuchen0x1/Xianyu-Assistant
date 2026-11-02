import axios, { type AxiosResponse } from 'axios'
import { request, getAuthToken } from '@/utils/request'

export interface BackupModule {
  moduleKey: string
  moduleName: string
}

export interface BackupImportResult {
  totalCount: number
  successCount: number
  failedModules: string[]
}

export interface BackupScheduleStatus {
  enabled: boolean
  intervalMillis: number
  retentionCount: number
  directory: string
  latestBackupFile: string | null
  latestBackupAt: string | null
  nextBackupAt: string | null
  lastError: string | null
}

export function getBackupSchedule() {
  return request<BackupScheduleStatus>({
    url: '/backup/schedule',
    method: 'get'
  })
}

export function getBackupModules() {
  return request<BackupModule[]>({
    url: '/backup/modules',
    method: 'post',
    data: {}
  })
}

/**
 * 从响应 Blob 里读出服务端错误提示；不是 JSON 或没有提示就返回 null。
 */
async function readBackupError(payload: unknown): Promise<string | null> {
  if (!(payload instanceof Blob)) return null
  try {
    const parsed = JSON.parse(await payload.text()) as { msg?: string; message?: string }
    return parsed.msg || parsed.message || null
  } catch {
    return null
  }
}

/**
 * 流式导出备份，直接拿到 Blob。
 *
 * <p>不能用上面的 `request()`：响应拦截器按 JSON 解析并检查 `code` 字段，
 * 二进制响应会被当成"请求失败"。这里改用裸 axios 自己带 token。</p>
 *
 * <p>正常备份与异常响应的 Content-Type 都是 application/json，无法靠 blob.type
 * 区分。服务端给成功的备份流加了 `X-Backup-Export: ok` 标记，没有该标记就按
 * 错误体解析，避免把一段错误 JSON 当成备份文件下载。</p>
 *
 * <p>`timeout: 0` 表示不设超时：备份体积可能有几百 MB，浏览器默认超时会把
 * 传输掐断；真正的耗时保护在服务端。</p>
 */
export async function downloadBackupStream(data: {
  modules: string[]
  includeSecrets?: boolean
}): Promise<Blob> {
  const token = getAuthToken()
  let response: AxiosResponse<Blob>
  try {
    response = await axios.post<Blob>('/api/backup/export/stream', data, {
      responseType: 'blob',
      timeout: 0,
      headers: token ? { Authorization: `Bearer ${token}` } : undefined
    })
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const message = await readBackupError(error.response?.data)
      if (message) throw new Error(message)
      if (error.response?.status === 403) throw new Error('该操作仅管理员可执行')
    }
    throw new Error('备份导出失败，请稍后重试')
  }
  if (response.headers?.['x-backup-export'] !== 'ok') {
    const message = await readBackupError(response.data)
    throw new Error(message || '备份导出失败，服务端未返回完整备份')
  }
  return response.data
}

export function importBackup(data: { jsonData: string; modules: string[] }) {
  return request<BackupImportResult>({
    url: '/backup/import',
    method: 'post',
    data
  })
}

export function getLogDates() {
  return request<string[]>({
    url: '/backup/log-dates',
    method: 'get'
  })
}

export function downloadLog(date: string): Promise<Blob> {
  const token = localStorage.getItem('xianyu_auth_token')
  return fetch(`/api/backup/log-download?date=${encodeURIComponent(date)}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  }).then(res => {
    if (!res.ok) throw new Error('下载失败')
    return res.blob()
  })
}
