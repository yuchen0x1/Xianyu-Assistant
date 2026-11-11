import { request } from '@/utils/request';

// AI 类目识别结果
export interface CategoryPredict {
  categoryId: string;
  categoryName: string;
  score: number;
}

// 发布请求
export interface PublishItemReq {
  accountId: number;
  title: string;
  description?: string;
  price: number;
  quantity?: number;
  images?: string[];
  categoryId?: string;
  postFee?: number;
  recommendCategoryOnly?: boolean;
}

// 发布响应
export interface PublishItemResp {
  success: boolean;
  itemId?: string;
  message: string;
  categories?: CategoryPredict[];
  price?: number;
}

// 下架请求
export interface OffShelfReq {
  accountId: number;
  itemId: string;
}

// 发布/下架历史记录
export interface PublishHistory {
  id: number;
  accountId: number;
  accountNote?: string;
  title?: string;
  description?: string;
  price?: number;
  quantity?: number;
  imageCount?: number;
  categoryId?: string;
  itemId?: string;
  operationType: 'PUBLISH' | 'OFF_SHELF';
  status: 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  rawResponse?: string;
  createdTime?: string;
}

// AI 类目识别
export function recommendCategory(data: {
  accountId: number;
  title: string;
  description?: string;
  images?: string[];
}) {
  return request<CategoryPredict[]>({
    url: '/publish/recommendCategory',
    method: 'POST',
    data
  });
}

// 发布商品
export function publishItem(data: PublishItemReq) {
  return request<PublishItemResp>({
    url: '/publish/item',
    method: 'POST',
    data
  });
}

// 下架商品
export function offShelf(data: OffShelfReq) {
  return request<boolean>({
    url: '/publish/offShelf',
    method: 'POST',
    data
  });
}

// 批量下架请求
export interface BatchOffShelfReq {
  xianyuAccountId: number;
  xyGoodsIds: string[];
}

// 批量下架结果明细
export interface BatchOffShelfItemResult {
  xyGoodsId: string;
  success: boolean;
  message: string;
}

// 批量下架响应
export interface BatchOffShelfResp {
  total: number;
  success: number;
  failed: number;
  details: BatchOffShelfItemResult[];
}

// 批量下架商品
// 后端逐件调用平台下架接口、件间间隔 1 秒（上限 50 件），必须覆盖全局 30s 超时，
// 否则前端先超时但服务端仍在执行，用户重试会造成重复下架。
export function batchOffShelf(data: BatchOffShelfReq) {
  return request<BatchOffShelfResp>({
    url: '/publish/batchOffShelf',
    method: 'POST',
    data,
    timeout: 120000
  });
}

// 发布历史
export function getPublishHistory(accountId?: number, limit = 50) {
  return request<PublishHistory[]>({
    url: '/publish/history',
    method: 'GET',
    params: { accountId, limit }
  });
}
