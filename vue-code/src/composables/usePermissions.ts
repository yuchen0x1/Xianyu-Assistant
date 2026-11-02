import { ref } from 'vue'
import { getMyPermissions } from '@/api/rbac'
import { getAuthToken } from '@/utils/request'

export interface PermissionState {
  permissions: string[]
  roles: string[]
}

let cache: PermissionState | null = null
let cacheToken: string | null = null
let loading: Promise<PermissionState> | null = null

/** 加载当前用户权限（带缓存，token 变化时自动失效） */
export function loadPermissions(): Promise<PermissionState> {
  const token = getAuthToken()
  if (cache && cacheToken === token) return Promise.resolve(cache)
  cache = null
  if (!loading) {
    loading = getMyPermissions()
      .then((res) => {
        cache = {
          permissions: Array.isArray(res?.data?.permissions) ? res.data.permissions : [],
          roles: Array.isArray(res?.data?.roles) ? res.data.roles : []
        }
        cacheToken = token
        return cache
      })
      .finally(() => {
        loading = null
      })
  }
  return loading
}

export function clearPermissionsCache() {
  cache = null
  cacheToken = null
}

/** 供组件使用的权限状态（组件挂载后调用 refresh() 拉取） */
export function usePermissions() {
  const permissions = ref<string[]>(cache?.permissions ?? [])
  const roles = ref<string[]>(cache?.roles ?? [])
  const loaded = ref<boolean>(!!cache)

  async function refresh(): Promise<PermissionState> {
    const state = await loadPermissions()
    permissions.value = state.permissions
    roles.value = state.roles
    loaded.value = true
    return state
  }

  function isAdmin(): boolean {
    return roles.value.includes('ADMIN')
  }

  function hasPermission(code?: string): boolean {
    if (!code) return true
    if (isAdmin()) return true
    return permissions.value.includes(code)
  }

  function hasAny(codes?: string[]): boolean {
    if (!codes || codes.length === 0) return true
    if (isAdmin()) return true
    return codes.some((code) => permissions.value.includes(code))
  }

  return { permissions, roles, loaded, isAdmin, hasPermission, hasAny, refresh }
}

export default usePermissions
