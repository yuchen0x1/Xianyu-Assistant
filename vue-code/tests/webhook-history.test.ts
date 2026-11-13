import { createApp, h, nextTick, ref, type App } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebhookDeliveryHistory from '@/views/webhooks/WebhookDeliveryHistory.vue'

const api = vi.hoisted(() => ({ getWebhookDeliveries: vi.fn() }))
vi.mock('@/api/webhooks', () => api)
vi.mock('@/utils', () => ({ formatTime: (value: string) => value }))
let app: App | undefined
let host: HTMLDivElement
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const record = (id: number, status: string) => ({ id, webhookId: 1, event: `event-${id}`, status, attempts: 2, httpStatus: 500, lastError: status === 'FAILED' ? 'HTTP 500' : null, createdTime: '2026-11-13', nextAttemptAt: null, payloadSummary: 'orderId' })
function mount(id = ref(1)) {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp({ render: () => h(WebhookDeliveryHistory, { webhookId: id.value }) })
  app.mount(host)
  return id
}
afterEach(() => { app?.unmount(); host?.remove(); vi.resetAllMocks() })

describe('Webhook 投递历史', () => {
  it('loads real API contract and filters by status', async () => {
    api.getWebhookDeliveries.mockResolvedValue({ code: 200, data: [record(1, 'SUCCESS'), record(2, 'FAILED')] })
    mount()
    await flush()
    expect(api.getWebhookDeliveries).toHaveBeenCalledWith(1, 100)
    expect(host.querySelectorAll('li')).toHaveLength(2)
    const select = host.querySelector('select')!
    select.value = 'FAILED'
    select.dispatchEvent(new Event('change'))
    await nextTick()
    expect(host.querySelectorAll('li')).toHaveLength(1)
    expect(host.textContent).toContain('自动重试已停止')
    expect(host.textContent).toContain('累计尝试 2 次')
  })

  it('displays failure and allows refresh recovery', async () => {
    api.getWebhookDeliveries.mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({ code: 200, data: [] })
    mount()
    await flush()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('网络中断')
    host.querySelector('button')!.click()
    await flush()
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.textContent).toContain('暂无投递记录')
  })

  it('ignores an old endpoint response after switching', async () => {
    let resolveOld!: (value: unknown) => void
    api.getWebhookDeliveries.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce({ code: 200, data: [record(22, 'SUCCESS')] })
    const id = mount()
    id.value = 2
    await flush()
    resolveOld({ code: 200, data: [record(11, 'FAILED')] })
    await flush()
    expect(host.textContent).toContain('event-22')
    expect(host.textContent).not.toContain('event-11')
  })
})
