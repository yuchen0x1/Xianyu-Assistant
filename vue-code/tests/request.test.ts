import axios, { AxiosError, type AxiosAdapter } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/utils/toast', () => ({
  toast: {
    error: vi.fn(),
  },
}))

import { clearAuthToken, getAuthToken, request, setAuthToken } from '@/utils/request'
import { toast } from '@/utils/toast'

function successfulAdapter<T>(data: T): AxiosAdapter {
  return vi.fn(async (config) => ({
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  }))
}

describe('request', () => {
  beforeEach(() => {
    clearAuthToken()
    vi.mocked(toast.error).mockClear()
    window.history.replaceState({}, '', '/dashboard')
  })

  it('adds the stored bearer token and unwraps a successful API response', async () => {
    setAuthToken('secret-token', 'tester')
    const adapter = successfulAdapter({ code: 0, data: { id: 7 } })

    const result = await request<{ id: number }>({ url: '/goods/7', adapter })

    expect(adapter).toHaveBeenCalledOnce()
    const config = vi.mocked(adapter).mock.calls[0]?.[0]
    expect(config?.headers.get('Authorization')).toBe('Bearer secret-token')
    expect(result.data).toEqual({ id: 7 })
  })

  it('shows the backend message once when a business response fails', async () => {
    const adapter = successfulAdapter({ code: 400, msg: '商品不存在' })

    const promise = request({ url: '/goods/missing', adapter })

    await expect(promise).rejects.toMatchObject({
      message: '商品不存在',
      messageShown: true,
    })
    expect(toast.error).toHaveBeenCalledOnce()
    expect(toast.error).toHaveBeenCalledWith('商品不存在')
  })

  it('sanitizes transport errors before exposing them to callers', async () => {
    setAuthToken('secret-token', 'tester')
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config)
    }

    const error = await request({ url: '/goods', adapter, retry: false }).catch((reason: unknown) => reason)

    expect(axios.isAxiosError(error)).toBe(false)
    expect(error).toMatchObject({
      message: '无法连接本机服务，请确认服务已启动',
      messageShown: true,
    })
    expect(error).not.toHaveProperty('config')
    expect(toast.error).toHaveBeenCalledWith('无法连接本机服务，请确认服务已启动')
  })

  it('retries a GET once on a transient network failure and then succeeds', async () => {
    let calls = 0
    const adapter: AxiosAdapter = async (config) => {
      calls += 1
      if (calls === 1) {
        throw new AxiosError('Network Error', 'ERR_NETWORK', config)
      }
      return { data: { code: 0, data: 'ok' }, status: 200, statusText: 'OK', headers: {}, config }
    }

    const result = await request({ url: '/goods', adapter })

    expect(calls).toBe(2)
    expect(result.data).toEqual('ok')
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('does not retry POST requests by default', async () => {
    let calls = 0
    const adapter: AxiosAdapter = async (config) => {
      calls += 1
      throw new AxiosError('Network Error', 'ERR_NETWORK', config)
    }

    const promise = request({ url: '/items/refresh', method: 'post', adapter })

    await expect(promise).rejects.toMatchObject({ messageShown: true })
    expect(calls).toBe(1)
  })

  it('does not retry client errors like 404', async () => {
    let calls = 0
    const adapter: AxiosAdapter = async (config) => {
      calls += 1
      throw new AxiosError('Request failed with status code 404', 'ERR_BAD_REQUEST', config, undefined, {
        status: 404,
      } as never)
    }

    const promise = request({ url: '/goods/missing', adapter })

    await expect(promise).rejects.toBeDefined()
    expect(calls).toBe(1)
  })

  it('refreshes the session once and replays the request on 401', async () => {
    setAuthToken('stale-token', 'tester')
    const postSpy = vi.spyOn(axios, 'post').mockResolvedValue({
      data: { code: 0, data: { token: 'fresh-token', username: 'tester' } },
    })
    let calls = 0
    const adapter: AxiosAdapter = async (config) => {
      calls += 1
      if (calls === 1) {
        throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, { status: 401 } as never)
      }
      return { data: { code: 0, data: 'replayed' }, status: 200, statusText: 'OK', headers: {}, config }
    }

    try {
      const result = await request({ url: '/orders', adapter })

      expect(calls).toBe(2)
      expect(result.data).toEqual('replayed')
      expect(postSpy).toHaveBeenCalledOnce()
      expect(postSpy.mock.calls[0]?.[0]).toBe('/api/login/refresh')
    } finally {
      postSpy.mockRestore()
    }
  })

  it('clears the session when silent refresh also fails', async () => {
    setAuthToken('stale-token', 'tester')
    const postSpy = vi.spyOn(axios, 'post').mockRejectedValue(new Error('refresh failed'))
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, { status: 401 } as never)
    }

    try {
      const promise = request({ url: '/orders', adapter })
      await expect(promise).rejects.toMatchObject({ message: 'Unauthorized' })
      // 续期失败：本地凭证被清除并跳转登录
      expect(getAuthToken()).toBeNull()
    } finally {
      postSpy.mockRestore()
    }
  })
})
