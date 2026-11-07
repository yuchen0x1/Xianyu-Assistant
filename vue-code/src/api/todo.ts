import { request } from '@/utils/request'

export interface TodoSummary {
  pendingShipmentCount: number
  pendingReplyCount: number
  pendingReviewCount: number
  pendingEvaluationCount: number
  total: number
}

export interface TodoItem {
  accountId: number
  accountName: string
  type: 'pendingShipment' | 'pendingReply' | 'pendingReview' | 'pendingEvaluation'
  id: string
  title: string
  description: string | null
  goodsId: string | null
  time: string | null
  extra: string | null
}

export interface TodoOverview {
  summary: TodoSummary
  pendingShipment: TodoItem[]
  pendingReply: TodoItem[]
  pendingReview: TodoItem[]
  pendingEvaluation: TodoItem[]
}

export function getTodoOverview(limit = 20) {
  return request<TodoOverview>({
    url: '/todo/overview',
    method: 'GET',
    params: { limit }
  })
}