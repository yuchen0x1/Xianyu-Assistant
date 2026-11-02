import { request } from '@/utils/request';
import type { ApiResponse } from '@/types';

// 价格趋势数据点
export interface PriceTrendPoint {
  date: string;
  price: number;
}

// 获取商品近 N 天价格趋势（按日期升序返回）
export function getPriceTrend(xyGoodId: string, days?: number): Promise<ApiResponse<PriceTrendPoint[]>> {
  return request<PriceTrendPoint[]>({
    url: '/price-history/trend',
    method: 'POST',
    data: { xyGoodId, days: days ?? 30 }
  });
}
