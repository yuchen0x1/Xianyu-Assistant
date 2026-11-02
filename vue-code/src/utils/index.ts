import { toast } from './toast'
import { showConfirm } from './confirm'
import type { Account } from '@/types'

export const showSuccess = toast.success
export const showError = toast.error
export const showWarning = toast.warning
export const showInfo = toast.info
export { showConfirm }

/** 账号展示名：优先闲鱼昵称，其次账号备注，最后 UNB。 */
export function accountDisplayName(account: Pick<Account, 'nickname' | 'accountNote' | 'unb'> | null | undefined): string {
  if (!account) return '未命名账号'
  return account.nickname || account.accountNote || account.unb || '未命名账号'
}

/** 账号头像占位字符：取展示名首字。 */
export function accountAvatarChar(account: Pick<Account, 'nickname' | 'accountNote' | 'unb'> | null | undefined): string {
  if (!account) return '闲'
  return (account.nickname || account.accountNote || account.unb || '闲').charAt(0)
}

export function formatTime(timestamp: number | string | Date): string {
  if (!timestamp) return '-'
  if (typeof timestamp === 'string') {
    if (/^\d{4}-\d{2}-\d{2}/.test(timestamp)) {
      return timestamp.replace('T', ' ').substring(0, 19)
    }
    const num = Number(timestamp)
    if (!isNaN(num)) {
      const date = new Date(num)
      if (!isNaN(date.getTime())) {
        return date.toLocaleString('zh-CN', {
          year: 'numeric', month: '2-digit', day: '2-digit',
          hour: '2-digit', minute: '2-digit', second: '2-digit'
        })
      }
    }
    return '-'
  }
  const date = new Date(timestamp)
  if (isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  })
}

export function formatPrice(price: string | number): string {
  if (!price) return '¥0.00'
  const num = typeof price === 'string' ? parseFloat(price) : price
  return `¥${num.toFixed(2)}`
}

export function getGoodsStatusText(status: number): { text: string; type: string } {
  const statusMap: Record<number, { text: string; type: string }> = {
    0: { text: '在售', type: 'success' },
    1: { text: '已下架', type: 'info' },
    2: { text: '已售出', type: 'warning' },
    '-1': { text: '远程已下架', type: 'info' },
    '-2': { text: '本地草稿', type: 'warning' }
  }
  return statusMap[status] || { text: '未知', type: 'info' }
}

export function getAccountStatusText(status: number): { text: string; type: string } {
  const statusMap: Record<number, { text: string; type: string }> = {
    1: { text: '正常', type: 'success' },
    '-1': { text: '需要手机号验证', type: 'warning' },
    '-2': { text: '需要滑块验证', type: 'warning' }
  }
  return statusMap[status] || { text: '未知', type: 'info' }
}

export function debounce<T extends (...args: never[]) => unknown>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return function (this: unknown, ...args: Parameters<T>) {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(() => {
      func.apply(this, args)
    }, wait)
  }
}

export function throttle<T extends (...args: never[]) => unknown>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return function (this: unknown, ...args: Parameters<T>) {
    if (!timeout) {
      timeout = setTimeout(() => {
        timeout = null
        func.apply(this, args)
      }, wait)
    }
  }
}
