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

// 发布历史
export function getPublishHistory(accountId?: number, limit = 50) {
  return request<PublishHistory[]>({
    url: '/publish/history',
    method: 'GET',
    params: { accountId, limit }
  });
}
