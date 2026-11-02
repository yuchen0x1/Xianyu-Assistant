import service from '@/utils/request'

export interface OrderExportParams {
  accountId?: number | null
  status?: number | null
  keyword?: string
}

export interface GoodsExportParams {
  accountId?: number | null
  status?: number | null
}

/** 导出订单 Excel（按当前筛选条件，全部字段） */
export function exportOrders(params: OrderExportParams) {
  return service.get('/export/orders.xlsx', { params, responseType: 'blob', timeout: 120000 })
}

/** 导出库存商品 Excel（按当前筛选条件，全部字段） */
export function exportGoods(params: GoodsExportParams) {
  return service.get('/export/goods.xlsx', { params, responseType: 'blob', timeout: 120000 })
}

/** 导出卡密清单 Excel */
export function exportKami(configId: number) {
  return service.get('/export/kami.xlsx', { params: { configId }, responseType: 'blob', timeout: 120000 })
}