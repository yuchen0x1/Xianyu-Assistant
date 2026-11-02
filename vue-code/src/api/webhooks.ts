import { request } from '@/utils/request'

export interface WebhookConfig {
  id: number
  name: string
  url: string
  events: string
  enabled: number
  secret: string
  hasSecret?: boolean
  retryCount: number
  timeoutSeconds: number
  lastTriggerAt: string
  lastTriggerStatus: string
  lastError: string
  createdTime: string
  updatedTime: string
}

export function getWebhookList() {
  return request<WebhookConfig[]>({ url: '/webhooks', method: 'GET' })
}

export function getWebhook(id: number) {
  return request<WebhookConfig>({ url: `/webhooks/${id}`, method: 'GET' })
}

export function createWebhook(data: Partial<WebhookConfig>) {
  return request<WebhookConfig>({ url: '/webhooks', method: 'POST', data })
}

export function updateWebhook(data: Partial<WebhookConfig>) {
  return request<void>({ url: '/webhooks', method: 'PUT', data })
}

export function deleteWebhook(id: number) {
  return request<void>({ url: `/webhooks/${id}`, method: 'DELETE' })
}

export function toggleWebhook(id: number, enabled: boolean) {
  return request<void>({ url: `/webhooks/${id}/toggle`, method: 'POST', data: { enabled } })
}

export function testWebhook(url: string, secret?: string, id?: number) {
  return request<boolean>({ url: '/webhooks/test', method: 'POST', data: { url, secret: secret || '', ...(id ? { id } : {}) } })
}
