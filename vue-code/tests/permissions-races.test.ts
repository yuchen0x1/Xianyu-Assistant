import { beforeEach, describe, expect, it, vi } from 'vitest'

const rbacMocks = vi.hoisted(() => ({
  getMyPermissions: vi.fn(),
}))

let currentToken: string | null = null

vi.mock('@/api/rbac', () => rbacMocks)
vi.mock('@/utils/request', () => ({
  getAuthToken: vi.fn(() => currentToken),
}))

import { clearPermissionsCache, loadPermissions } from '@/composables/usePermissions'

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

describe('permission cache token races', () => {
  beforeEach(() => {
    currentToken = null
    clearPermissionsCache()
    rbacMocks.getMyPermissions.mockReset()
  })

  it('does not reuse or cache an older token response after login changes', async () => {
    const oldResponse = deferred<{ data: { permissions: string[]; roles: string[] } }>()
    const newResponse = deferred<{ data: { permissions: string[]; roles: string[] } }>()
    rbacMocks.getMyPermissions
      .mockReturnValueOnce(oldResponse.promise)
      .mockReturnValueOnce(newResponse.promise)

    currentToken = 'token-a'
    const oldRequest = loadPermissions()

    currentToken = 'token-b'
    const newRequest = loadPermissions()

    newResponse.resolve({ data: { permissions: ['orders:read'], roles: [] } })
    await expect(newRequest).resolves.toEqual({ permissions: ['orders:read'], roles: [] })

    // The slower old request completes after the new session. It may resolve
    // its own caller, but must not poison the cache for token-b.
    oldResponse.resolve({ data: { permissions: ['admin:all'], roles: ['ADMIN'] } })
    await expect(oldRequest).resolves.toEqual({ permissions: ['admin:all'], roles: ['ADMIN'] })

    await expect(loadPermissions()).resolves.toEqual({ permissions: ['orders:read'], roles: [] })
    expect(rbacMocks.getMyPermissions).toHaveBeenCalledTimes(2)
  })
})
