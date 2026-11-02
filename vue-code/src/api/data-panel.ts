import { request } from '@/utils/request';

export interface DataPanelStats {
  orderCount: number;
  deliverySuccessCount: number;
  deliveryFailCount: number;
  aiReplyCount: number;
  hasData: boolean;
}

export interface DataPanelTrend {
  dates: string[];
  deliverySuccess: number[];
  deliveryFail: number[];
  aiReplies: number[];
}

export interface SalesRevenueData {
  labels: string[];
  values: number[];
}

export interface GoodsRankItem {
  goodsId: string;
  goodsTitle: string;
  count: number;
  amount: number;
}

export interface GoodsRankData {
  orderRank: GoodsRankItem[];
  inquiryRank: GoodsRankItem[];
}

export function getDataPanelStats(date?: string) {
  return request<DataPanelStats>({
    url: '/data-panel/stats',
    method: 'POST',
    data: { date: date || undefined }
  });
}

export function getDataPanelTrend() {
  return request<DataPanelTrend>({
    url: '/data-panel/trend',
    method: 'POST',
    data: {}
  });
}

export function getRealtimeRevenue() {
  return request<number>({
    url: '/data-panel/realtimeRevenue',
    method: 'GET'
  });
}

export function getSalesRevenue(dimension: string, startDate: string, endDate: string) {
  return request<SalesRevenueData>({
    url: '/data-panel/salesRevenue',
    method: 'POST',
    data: { dimension, startDate, endDate }
  });
}

export function getGoodsRank(limit = 5) {
  return request<GoodsRankData>({
    url: '/data-panel/goodsRank',
    method: 'POST',
    data: { limit }
  });
}

export interface AccountCompareItem {
  accountId: number;
  accountName: string;
  orderCount: number;
  successCount: number;
  failCount: number;
  replyCount: number;
  amount: number;
}

export interface AccountCompareData {
  accounts: AccountCompareItem[];
  totalOrders: number;
  totalAmount: number;
  totalReplies: number;
}

export function getAccountCompare(date?: string) {
  return request<AccountCompareData>({
    url: '/data-panel/accountCompare',
    method: 'POST',
    data: { date: date || undefined }
  });
}

export interface KeywordRankItem {
  keyword: string;
  hitCount: number;
  successCount: number;
  failCount: number;
  pendingCount: number;
  buyerCount: number;
}

export interface KeywordRankData {
  items: KeywordRankItem[];
  totalHits: number;
}

export function getKeywordRank(date?: string, limit = 10) {
  return request<KeywordRankData>({
    url: '/data-panel/keywordRank',
    method: 'POST',
    data: { date: date || undefined, limit }
  });
}

export interface OperationTypeStat {
  type: string;
  totalCount: number;
  successCount: number;
  failCount: number;
  partialCount: number;
  avgDurationMs: number;
  successRate: number;
}

export interface OperationStatsData {
  types: OperationTypeStat[];
  totalOperations: number;
  totalSuccess: number;
  totalFail: number;
  totalPartial: number;
  successRate: number;
}

export function getOperationStats(date?: string, limit = 10) {
  return request<OperationStatsData>({
    url: '/data-panel/operationStats',
    method: 'POST',
    data: { date: date || undefined, limit }
  });
}

export interface FailureRankItem {
  reason: string;
  failCount: number;
  relatedTypes: string;
}

export interface FailureRankData {
  items: FailureRankItem[];
  totalFailures: number;
}

export function getFailureRank(date?: string, limit = 10) {
  return request<FailureRankData>({
    url: '/data-panel/failureRank',
    method: 'POST',
    data: { date: date || undefined, limit }
  });
}
