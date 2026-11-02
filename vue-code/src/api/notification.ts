import { request } from '@/utils/request'

export interface NotificationItem {
  id: number
  title: string
  content: string
  notifyType: string
  level: string
  readStatus: number
  createdTime: string
}

export interface NotificationListResult {
  list: NotificationItem[]
  unread: number
}

export function getNotificationList(limit = 20) {
  return request<NotificationListResult>({
    url: '/notification/list',
    method: 'POST',
    data: { limit }
  })
}

export function getUnreadCount() {
  return request<number>({
    url: '/notification/unreadCount',
    method: 'POST',
    data: {}
  })
}

export function markNotificationRead(id: number) {
  return request<null>({
    url: '/notification/markRead',
    method: 'POST',
    data: { id }
  })
}

export function markAllNotificationsRead() {
  return request<number>({
    url: '/notification/markAllRead',
    method: 'POST',
    data: {}
  })
}
