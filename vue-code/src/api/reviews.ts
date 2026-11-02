import { request } from '@/utils/request'

export type ReviewStatus = 'WAITING_CONFIRM' | 'PENDING' | 'MANUAL_REQUIRED' | 'SUBMITTED' | 'CANCELLED'
export interface ReviewRule { id?: number; accountId: number; enabled: number; delaySeconds: number; template: string; requireManualConfirm: number; maxRetries: number }
export interface ReviewRecord { id: number; accountId: number; orderId: string; buyerUserId?: string; reviewText: string; status: ReviewStatus | string; scheduledAt?: string; submittedAt?: string; retryCount: number; lastError?: string }
export function getReviewRules() { return request<ReviewRule[]>({ url: '/reviews/rules', method: 'GET' }) }
export function saveReviewRule(data: ReviewRule) { return request<ReviewRule>({ url: '/reviews/rules', method: 'PUT', data }) }
export function getReviewRecords(params?: { accountId?: number; status?: string; limit?: number }) { return request<ReviewRecord[]>({ url: '/reviews/records', method: 'GET', params }) }
export function scheduleReview(data: { accountId: number; orderId: string; buyerUserId?: string; reviewText?: string }) { return request<ReviewRecord>({ url: '/reviews/records', method: 'POST', params: data }) }
export function confirmReview(id: number) { return request<ReviewRecord>({ url: `/reviews/records/${id}/confirm`, method: 'POST' }) }
export function retryReview(id: number) { return request<ReviewRecord>({ url: `/reviews/records/${id}/retry`, method: 'POST' }) }
export function cancelReview(id: number) { return request<ReviewRecord>({ url: `/reviews/records/${id}/cancel`, method: 'POST' }) }
