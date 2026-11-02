import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  request: vi.fn(),
}))

vi.mock('@/utils/request', () => ({
  default: {
    get: requestMocks.get,
  },
  request: requestMocks.request,
  getAuthToken: vi.fn(() => null),
}))

import { chatTestWithAI } from '@/api/ai'
import { downloadLog } from '@/api/backup'
import { exportGoods, exportKami, exportOrders } from '@/api/export'
import { getApiDeliveryFallbackStatus, updateApiDeliveryFallback } from '@/api/websocket'
import { getAutoReplyDecisions } from '@/api/goods'

describe('API endpoint paths', () => {
  beforeEach(() => {
    requestMocks.get.mockReset().mockResolvedValue({})
    requestMocks.request.mockReset().mockResolvedValue({})
    vi.stubGlobal('fetch', vi.fn(async () => new Response('ok', { status: 200 })))
  })

  it('uses a single /api prefix for export endpoints', async () => {
    await exportOrders({})
    await exportGoods({})
    await exportKami(42)

    const urls = requestMocks.get.mock.calls.map(([url, config]) => axios.getUri({
      baseURL: '/api',
      url,
      ...config,
    }))
    expect(urls).toEqual([
      '/api/export/orders.xlsx',
      '/api/export/goods.xlsx',
      '/api/export/kami.xlsx?configId=42',
    ])
  })

  it('uses a single /api prefix for websocket API fallback endpoints', async () => {
    await getApiDeliveryFallbackStatus()
    await updateApiDeliveryFallback({ enabled: true, accountIds: '1' })

    const urls = requestMocks.request.mock.calls.map(([config]) => axios.getUri({
      baseURL: '/api',
      ...config,
    }))
    expect(urls).toEqual([
      '/api/websocket/apiDeliveryFallbackStatus',
      '/api/websocket/updateApiDeliveryFallback',
    ])
  })

  it('keeps /api for backup direct fetch and uses /ai for AI direct fetch', async () => {
    await downloadLog('2026-08-31')
    await chatTestWithAI({ msg: 'hello' })

    expect(vi.mocked(fetch).mock.calls.map(([url]) => url)).toEqual([
      '/api/backup/log-download?date=2026-08-31',
      '/ai/chatTest',
    ])
  })

  it('posts auto-reply decision log filters to /api/items/autoReplyDecisions', async () => {
    await getAutoReplyDecisions({ xianyuAccountId: 7, reasonCode: 'HUMAN_TAKEOVER', pageNum: 2, pageSize: 20 })

    const calls = requestMocks.request.mock.calls.map(([config]) => config)
    expect(calls).toHaveLength(1)
    expect(axios.getUri({ baseURL: '/api', ...calls[0] })).toBe('/api/items/autoReplyDecisions')
    expect(calls[0].method).toBe('POST')
    expect(calls[0].data).toEqual({ xianyuAccountId: 7, reasonCode: 'HUMAN_TAKEOVER', pageNum: 2, pageSize: 20 })
  })
})
