import axios, { type AxiosError, type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { toast } from './toast'
import type { ApiResponse } from '@/types'

// Token存储key
const TOKEN_KEY = 'xianyu_auth_token'
const USERNAME_KEY = 'xianyu_auth_username'
let redirectingToLogin = false

type MessageShownError = Error & { messageShown?: boolean }

function hasMessageBeenShown(error: unknown): boolean {
  return typeof error === 'object'
    && error !== null
    && 'messageShown' in error
    && error.messageShown === true
}

function getRequestErrorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    const responseMessage = error.response?.data?.msg || error.response?.data?.message
    if (typeof responseMessage === 'string' && responseMessage.trim()) return responseMessage
    if (error.response?.status && error.response.status >= 500) {
      return '本机服务暂时不可用，请稍后重试'
    }
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' || /timeout/i.test(error.message)) {
      return '本机服务响应超时，请稍后重试'
    }
    if (error.message === 'Network Error') {
      return '无法连接本机服务，请确认服务已启动'
    }
    return error.message || '网络请求失败'
  }
  return error instanceof Error ? error.message : '网络请求失败'
}

function isLoginPage(): boolean {
  return /(?:^|\/)login\/?$/.test(window.location.pathname)
}

/** 获取Token */
export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 设置Token */
export function setAuthToken(token: string, username: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USERNAME_KEY, username)
}

/** 清除Token */
export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
}

function handleUnauthorized(message: string) {
  clearAuthToken()
  if (!window.location.pathname.includes('/login') && !redirectingToLogin) {
    redirectingToLogin = true
    toast.error(message)
    window.location.replace('/login')
  }
}

/** 获取用户名 */
export function getAuthUsername(): string | null {
  return localStorage.getItem(USERNAME_KEY)
}

/** 是否已登录 */
export function isLoggedIn(): boolean {
  return !!getAuthToken()
}

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 添加Token到请求头
    const token = getAuthToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error: unknown) => {
    const errorMessage = getRequestErrorMessage(error)
    if (import.meta.env.DEV) {
      console.error('请求初始化失败:', errorMessage)
    }
    return Promise.reject(new Error(errorMessage))
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const res = response.data as ApiResponse<unknown>

    // 401未登录 -> 跳转登录页
    if (res.code === 401) {
      handleUnauthorized(res.msg || '登录已过期，请重新登录')
      return Promise.reject(new Error(res.msg || '未登录'))
    }

    // 特殊处理：1001是滑块验证码，需要业务代码自己处理，不在这里拦截
    if (res.code === 1001) {
      return response // 直接返回，让业务代码处理
    }

    // 如果响应码不是 0 或 200，认为是错误
    if (res.code !== 0 && res.code !== 200) {
      const errorMsg = res.msg || res.message || '请求失败'
      const messageWasShown = !isLoginPage()
      if (messageWasShown) toast.error(errorMsg)
      const error = new Error(errorMsg) as MessageShownError
      // 标记这个错误已经显示过消息，避免重复提示
      error.messageShown = messageWasShown
      return Promise.reject(error)
    }

    return response // 保持返回完整的 AxiosResponse
  },
  async (error: unknown) => {
    // 401 先尝试静默续期一次并重放原请求；失败才走提示与跳转流程
    const authRetried = await attemptUnauthorizedRetry(error)
    if (authRetried) {
      return authRetried
    }
    // 瞬时故障（网络不可达/超时/5xx网关）先按幂等策略重试；耗尽后才走提示流程
    const retried = await attemptTransientRetry(error)
    if (retried) {
      return retried
    }
    const errorMessage = getRequestErrorMessage(error)
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      handleUnauthorized(errorMessage || '登录已过期，请重新登录')
      return Promise.reject(new Error(errorMessage || '未登录'))
    }
    if (import.meta.env.DEV) {
      console.error('请求失败:', errorMessage)
    }
    const messageWasAlreadyShown = hasMessageBeenShown(error)
    const messageWasShownNow = !messageWasAlreadyShown && !isLoginPage()
    if (messageWasShownNow) {
      toast.error(errorMessage)
    }
    // Do not expose Axios request config or Authorization headers to page-level catch handlers.
    const sanitizedError = new Error(errorMessage) as MessageShownError
    sanitizedError.messageShown = messageWasAlreadyShown || messageWasShownNow
    return Promise.reject(sanitizedError)
  }
)

// 封装请求方法
// Legacy endpoints without response generics still depend on open-shaped data.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function request<T = any>(config: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return service.request<ApiResponse<T>>(config).then(response => response.data)
}

export default service

// ---- 会话续期（Token 轮换）：后端 JWT 有效期 7 天，活跃使用期间定期 /api/login/refresh 换新 Token ----

const REFRESH_URL = '/api/login/refresh'
// 12小时轮换一次，远小于后端有效期；标签页不可见时不发起，避免无意义轮换
const REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000

type RefreshPayload = { token?: string; username?: string }

let refreshInFlight: Promise<boolean> | null = null

async function doRefreshSession(): Promise<boolean> {
  const token = getAuthToken()
  if (!token || isLoginPage()) return false
  try {
    // 独立请求（不走业务拦截器），避免刷新接口自身 401 时再次触发刷新循环
    const response = await axios.post(REFRESH_URL, null, {
      headers: { Authorization: `Bearer ${token}` },
      timeout: 10000,
    })
    const res = response.data as ApiResponse<RefreshPayload>
    const payload = res?.data
    if ((res.code === 0 || res.code === 200) && payload?.token && payload.username) {
      setAuthToken(payload.token, payload.username)
      return true
    }
    return false
  } catch {
    return false
  }
}

/** 单飞刷新：并发触发时共享同一次轮换，避免轮换竞态导致新 Token 立即被撤销 */
function refreshSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = doRefreshSession().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

if (typeof window !== 'undefined') {
  window.setInterval(() => {
    if (document.visibilityState === 'visible' && isLoggedIn()) {
      void refreshSession()
    }
  }, REFRESH_INTERVAL_MS)
}

// ---- 401 时先尝试续期一次再重放原请求；续期失败才跳转登录页 ----

type AuthRetryConfig = RetryAwareConfig & { __authRetried?: boolean }

async function attemptUnauthorizedRetry(error: unknown): Promise<AxiosResponse | null> {
  if (!axios.isAxiosError(error)) return null
  const config = error.config as AuthRetryConfig | undefined
  if (!config || error.response?.status !== 401 || config.__authRetried) return null
  // 登录/刷新/登出等认证端点自身的 401 不进入续期流程
  if (typeof config.url === 'string' && config.url.includes('/login/')) return null
  if (!getAuthToken() || isLoginPage()) return null

  const refreshed = await refreshSession()
  if (!refreshed) return null
  config.__authRetried = true
  // 以全新头重放：Authorization 由下方注入，其余默认头在请求链中自动补齐
  const headers = new axios.AxiosHeaders()
  headers.set('Authorization', `Bearer ${getAuthToken()}`)
  config.headers = headers
  // 重放走完整请求链，复用既有拦截器
  return service.request(config)
}


// ---- 网络级幂等重试（在响应错误拦截器内执行，仅瞬时故障；业务码错误/4xx/用户中止不重试） ----

const RETRIABLE_HTTP_STATUS = new Set([502, 503, 504])
const DEFAULT_RETRY_LIMIT = 2
const RETRY_BASE_DELAY_MS = 250

type RetryOption = number | false | undefined
type RetryAwareConfig = AxiosRequestConfig & { retry?: RetryOption; __retryCount?: number }

function resolveRetryLimit(method: string | undefined, retry: RetryOption): number {
  if (retry === false) return 0
  if (typeof retry === 'number') return Math.max(0, retry)
  const normalized = (method ?? 'get').toLowerCase()
  // 只有幂等方法默认重试；POST/DELETE 等由调用方显式开启
  return normalized === 'get' || normalized === 'head' ? DEFAULT_RETRY_LIMIT : 0
}

function isTransientFailure(error: AxiosError): boolean {
  if (error.code === 'ERR_CANCELED') return false
  if (error.response) return RETRIABLE_HTTP_STATUS.has(error.response.status)
  // 无响应：网络不可达或超时
  return error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' || error.message === 'Network Error'
}

/**
 * 瞬时故障时按退避延迟重发请求；重试耗尽或不可重试时返回 null，
 * 交回错误拦截器做消毒与提示（保证 toast 只出现一次）。
 */
async function attemptTransientRetry(error: unknown): Promise<AxiosResponse | null> {
  if (!axios.isAxiosError(error)) return null
  const config = error.config as RetryAwareConfig | undefined
  if (!config || !isTransientFailure(error)) return null

  const maxRetries = resolveRetryLimit(config.method, config.retry)
  const attempt = config.__retryCount ?? 0
  if (attempt >= maxRetries) return null

  config.__retryCount = attempt + 1
  await new Promise(resolve => setTimeout(resolve, RETRY_BASE_DELAY_MS * (attempt + 1)))
  // 重新走完整请求链（token 注入、响应码处理均复用既有拦截器）
  return service.request(config)
}
