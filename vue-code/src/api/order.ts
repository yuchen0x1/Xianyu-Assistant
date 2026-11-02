import { request } from '@/utils/request'
import service from '@/utils/request'

export interface DeliveryRecordQueryReq {
  xianyuAccountId?: number
  xyGoodsId?: string
  keyword?: string
  /** 1-成功，0-待人工核对，-1-失败 */
  state?: 1 | 0 | -1
  pageNum?: number
  pageSize?: number
}

export interface DeliveryRecordVO {
  id: number
  xianyuAccountId?: number
  xyGoodsId: string
  goodsTitle?: string
  buyerUserName?: string
  sid?: string
  content?: string
  state: number
  failReason?: string
  confirmState: number
  orderId?: string
  skuName?: string
  orderCreateTime?: string
  paySuccessTime?: string
  consignTime?: string
  totalPrice?: string
  buyNum?: number
  createTime: string
}

export interface DeliveryRecordPageResult {
  records: DeliveryRecordVO[]
  total: number
  pageNum: number
  pageSize: number
}

export function queryDeliveryRecordList(data: DeliveryRecordQueryReq) {
  return request<DeliveryRecordPageResult>({
    url: '/items/autoDeliveryRecords',
    method: 'POST',
    data
  })
}

export function confirmShipment(data: { xianyuAccountId: number; orderId: string }) {
  return request<string>({
    url: '/order/confirmShipment',
    method: 'POST',
    data
  })
}

export interface BatchConfirmShipmentResult {
  total: number
  successCount: number
  failCount: number
  failures: { orderId: string; reason: string }[]
}

export function confirmShipmentBatch(items: { xianyuAccountId: number; orderId: string }[]) {
  return request<BatchConfirmShipmentResult>({
    url: '/order/confirmShipmentBatch',
    method: 'POST',
    data: items
  })
}

export function getOrderDetail(data: { xianyuAccountId: number; orderId: string; fromServer?: boolean }) {
  return request<string>({
    url: '/order/detail',
    method: 'POST',
    data
  })
}

export function manualDelivery(data: { xianyuAccountId: number; orderId: string; content: string }) {
  return request<string>({
    url: '/autoDelivery/manual',
    method: 'POST',
    data
  })
}

export interface PendingOrder {
  commonData?: {
    orderId?: string
    orderStatus?: string | number
    itemId?: string
    createTime?: string
    paySuccessTime?: string
    tags?: string[]
  }
  itemVO?: { itemId?: string; title?: string }
  buyerInfoVO?: { userNick?: string }
  priceVO?: { totalPrice?: string | number; buyNum?: number }
}

export function getPendingOrders(xianyuAccountId: number) {
  return request<PendingOrder[]>({
    url: '/order/pendingOrders',
    method: 'POST',
    data: { xianyuAccountId }
  })
}

export function deliverPendingOrders(xianyuAccountId: number) {
  return request<number>({
    url: '/order/deliverPendingOrders',
    method: 'POST',
    data: { xianyuAccountId }
  })
}

export function consignDummyDelivery(data: { xianyuAccountId: number; xyGoodsId: string; orderId: string }) {
  return request<string>({
    url: '/order/consignDummyDelivery',
    method: 'POST',
    data
  })
}

/**
 * 导出发货记录 CSV（按当前筛选条件）
 * 返回 Blob，由调用方触发下载
 */
export function exportDeliveryRecords(data: DeliveryRecordQueryReq) {
  return service.post('/items/autoDeliveryRecords/export', data, {
    responseType: 'blob',
    timeout: 60000
  })
}
