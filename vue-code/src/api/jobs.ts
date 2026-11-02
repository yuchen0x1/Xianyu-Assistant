import { request } from '@/utils/request'

export interface JobDefinition {
  id: number
  jobType: string
  idempotencyKey: string
  priority: number
  status: string       // PENDING / RUNNING / SUCCESS / FAILED / CANCELLED / DEAD_LETTER
  paramsJson: string
  accountId: number
  maxRetries: number
  retryCount: number
  lastError: string
  nextRunTime: string
  scheduleCron: string
  createdBy: number
  createdTime: string
  updatedTime: string
}

export interface JobExecution {
  id: number
  jobId: number
  attempt: number
  status: string
  startedAt: string
  finishedAt: string
  errorMessage: string
  resultJson: string
  workerId: string
  createdTime: string
}

export interface JobDeadLetter {
  id: number
  jobId: number
  jobType: string
  idempotencyKey: string
  paramsJson: string
  accountId: number
  maxRetries: number
  finalRetryCount: number
  lastError: string
  deadAt: string
  resolved: number
  resolvedBy: number
  resolvedAt: string
  resolutionNote: string
}

/** 查询任务列表 */
export function getJobList(status?: string, jobType?: string, limit = 50) {
  const params: Record<string, string | number> = { limit }
  if (status) params.status = status
  if (jobType) params.jobType = jobType
  return request<JobDefinition[]>({ url: '/jobs/list', method: 'GET', params })
}

/** 查询任务执行历史 */
export function getJobExecutions(jobId: number, limit = 20) {
  return request<JobExecution[]>({ url: `/jobs/${jobId}/executions`, method: 'GET', params: { limit } })
}

/** 查询死信列表 */
export function getDeadLetters(limit = 50) {
  return request<JobDeadLetter[]>({ url: '/jobs/dead-letters', method: 'GET', params: { limit } })
}

/** 处理死信 */
export function resolveDeadLetter(id: number, note: string) {
  return request<void>({ url: `/jobs/dead-letters/${id}/resolve`, method: 'POST', data: { note } })
}

/** 重试死信 */
export function retryDeadLetter(id: number) {
  return request<void>({ url: `/jobs/dead-letters/${id}/retry`, method: 'POST' })
}
