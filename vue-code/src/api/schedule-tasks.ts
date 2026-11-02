import { request } from '@/utils/request'

export interface ScheduleTask {
  id: number
  taskName: string
  taskType: 'SYNC_GOODS' | 'PUBLISH' | 'OFF_SHELF' | string
  cronExpr: string
  accountId?: number
  configJson?: string
  enabled: number
  paused: number
  failCount: number
  lastRunTime?: string
  lastRunStatus?: string
  lastError?: string
  createdTime?: string
  updatedTime?: string
}

export interface ScheduleTaskReq {
  taskName: string
  taskType: string
  cronExpr: string
  accountId?: number | null
  configJson?: string
  enabled: boolean
}

export function getScheduleTasks() {
  return request<ScheduleTask[]>({ url: '/schedule-tasks/list', method: 'GET' })
}

export function createScheduleTask(data: ScheduleTaskReq) {
  return request<ScheduleTask>({ url: '/schedule-tasks', method: 'POST', data })
}

export function updateScheduleTask(id: number, data: ScheduleTaskReq) {
  return request<ScheduleTask>({ url: `/schedule-tasks/${id}`, method: 'PUT', data })
}

export function toggleScheduleTask(id: number, enabled: boolean) {
  return request<ScheduleTask>({ url: `/schedule-tasks/${id}/toggle`, method: 'POST', params: { enabled } })
}

export function triggerScheduleTask(id: number) {
  return request<ScheduleTask>({ url: `/schedule-tasks/${id}/trigger`, method: 'POST' })
}

export function deleteScheduleTask(id: number) {
  return request<boolean>({ url: `/schedule-tasks/${id}`, method: 'DELETE' })
}
