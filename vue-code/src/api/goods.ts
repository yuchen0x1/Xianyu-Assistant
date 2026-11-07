import { request } from '@/utils/request';

// 商品信息
export interface GoodsItem {
  id: number;
  xyGoodId: string;
  xianyuAccountId: number;
  title: string;
  coverPic: string;
  infoPic: string;
  detailInfo: string;
  detailUrl: string;
  soldPrice: string;
  skuCount: number;
  status: number;
  createdTime: string;
  updatedTime: string;
}

// 带配置的商品信息
export interface GoodsItemWithConfig {
  item: GoodsItem;
  xianyuAutoDeliveryOn: number;
  xianyuAutoReplyOn: number;
  xianyuAutoReplyContextOn: number;
  xianyuKeywordReplyOn: number;
  humanInterventionOn: number;
  humanInterventionMinutes: number;
  replyManualConfirmOn: number;
  bargainOn?: number;
  bargainFloorPrice?: string | null;
  bargainStepPrice?: string | null;
  bargainMaxRounds?: number;
  autoDeliveryType?: number;
  autoDeliveryContent?: string;
}

// 商品列表响应
export interface GoodsListResponse {
  itemsWithConfig: GoodsItemWithConfig[];
  totalCount: number;
  totalPage: number;
  pageNum: number;
  pageSize: number;
}

// 商品详情响应
export interface GoodsDetailResponse {
  itemWithConfig: GoodsItemWithConfig;
}

export interface GoodsInfoMutationResponse {
  id: number;
  xyGoodId: string;
  title: string;
  detailInfo: string;
  soldPrice: string;
  infoPic: string;
  status: number;
}

// 刷新商品响应
export interface RefreshItemsResponse {
  success: boolean;
  totalCount: number;
  successCount: number;
  updatedItemIds: string[];
  message: string;
  syncId?: string;
}

export interface SyncProgressResponse {
  syncId: string;
  accountId: number;
  totalCount: number;
  completedCount: number;
  successCount: number;
  failedCount: number;
  isCompleted: boolean;
  isRunning: boolean;
  currentItemId: string;
  message: string;
  startTime: number;
  estimatedRemainingTime: number;
}

// 获取商品列表
export function getGoodsList(data: {
  xianyuAccountId: number;
  onlyOnSale?: boolean;
  status?: number;
  pageNum?: number;
  pageSize?: number;
}) {
  return request<GoodsListResponse>({
    url: '/items/list',
    method: 'POST',
    data
  });
}

// 刷新商品数据
export function refreshGoods(xianyuAccountId: number) {
  return request<RefreshItemsResponse>({
    url: '/items/refresh',
    method: 'POST',
    data: { xianyuAccountId }
  });
}

// 获取商品详情
export function getGoodsDetail(xyGoodId: string) {
  return request<GoodsDetailResponse>({
    url: '/items/detail',
    method: 'POST',
    data: { xyGoodId }
  });
}

// 更新自动发货状态
export function updateAutoDeliveryStatus(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
  xianyuAutoDeliveryOn: number;
}) {
  return request({
    url: '/items/updateAutoDeliveryStatus',
    method: 'POST',
    data
  });
}

export function updateAutoConfirmShipment(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
  autoConfirmShipment: number;
}) {
  return request({
    url: '/items/updateAutoConfirmShipment',
    method: 'POST',
    data
  });
}

// 更新自动回复状态
export function updateAutoReplyStatus(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
  xianyuAutoReplyOn: number;
  xianyuAutoReplyContextOn?: number;
  xianyuKeywordReplyOn?: number;
  humanInterventionOn?: number;
  humanInterventionMinutes?: number;
  replyManualConfirmOn?: number;
  bargainOn?: number;
  bargainFloorPrice?: string | null;
  bargainStepPrice?: string | null;
  bargainMaxRounds?: number;
}) {
  return request({
    url: '/items/updateAutoReplyStatus',
    method: 'POST',
    data
  });
}

export interface BatchUpdateGoodsConfigResult {
  total: number
  successCount: number
  failCount: number
  failures: { xyGoodsId: string; reason: string }[]
}

// 批量更新商品自动化配置（自动发货/自动回复开关）
export function batchUpdateGoodsConfig(data: {
  xianyuAccountId: number;
  xyGoodsIds: string[];
  xianyuAutoDeliveryOn?: number;
  xianyuAutoReplyOn?: number;
  xianyuAutoReplyContextOn?: number;
}) {
  return request<BatchUpdateGoodsConfigResult>({
    url: '/items/batchUpdateGoodsConfig',
    method: 'POST',
    data
  });
}

// 删除商品
export function deleteItem(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
}) {
  return request({
    url: '/items/delete',
    method: 'POST',
    data
  });
}

// 更新助手本地商品资料。远程闲鱼商品需在官方页面确认。
export function updateGoodsInfo(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
  title: string;
  detailInfo: string;
  soldPrice: string;
  infoPic?: string;
}) {
  return request<GoodsInfoMutationResponse>({
    url: '/items/updateInfo',
    method: 'POST',
    data
  });
}

export interface BatchUpdatePriceResult {
  total: number
  success: number
  failed: number
  details: {
    xyGoodsId: string
    success: boolean
    soldPrice: string
    message: string
  }[]
}

// 批量更新助手本地商品售价。远程闲鱼商品需在官方页面确认。
export function batchUpdatePrice(data: {
  xianyuAccountId: number
  xyGoodsIds: string[]
  soldPrice: string
}) {
  return request<BatchUpdatePriceResult>({
    url: '/items/batchUpdatePrice',
    method: 'POST',
    data
  })
}

// 创建助手本地商品资料草稿。
export function createGoodsDraft(data: {
  xianyuAccountId: number;
  title: string;
  detailInfo: string;
  soldPrice: string;
  infoPic?: string;
}) {
  return request<GoodsInfoMutationResponse>({
    url: '/items/draft',
    method: 'POST',
    data
  });
}

export function syncSingleItem(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
}) {
  return request({
    url: '/items/syncSingle',
    method: 'POST',
    data
  });
}

// 自动回复配置响应
export interface AutoReplyConfigResponse {
  ragDelaySeconds: number;
}

// 获取自动回复配置
export function getAutoReplyConfig(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
}) {
  return request<AutoReplyConfigResponse>({
    url: '/items/getRagAutoReplyConfig',
    method: 'POST',
    data
  });
}

// 更新自动回复配置
export function updateAutoReplyConfig(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
  ragDelaySeconds: number;
}) {
  return request({
    url: '/items/updateRagAutoReplyConfig',
    method: 'POST',
    data
  });
}

// 自动回复记录
export interface AutoReplyRecord {
  id: number;
  xianyuAccountId: number;
  xianyuGoodsId: number;
  xyGoodsId: string;
  sId: string;
  pnmId: string;
  buyerUserId: string;
  buyerUserName: string;
  buyerMessage: string;
  replyContent: string;
  replyType: number;
  matchedKeyword: string;
  triggerContext: string;
  state: number;
  createTime: string;
}

// 自动回复记录列表响应
export interface AutoReplyRecordListResponse {
  list: AutoReplyRecord[];
  totalCount: number;
  pageNum: number;
  pageSize: number;
}

// 获取自动回复记录
export function getAutoReplyRecords(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return request<AutoReplyRecordListResponse>({
    url: '/items/autoReplyRecords',
    method: 'POST',
    data
  });
}

// 获取待人工确认的AI回复草稿
export function getPendingAutoReplyRecords(data: {
  xianyuAccountId: number;
  pageNum?: number;
  pageSize?: number;
}) {
  return request<AutoReplyRecordListResponse>({
    url: '/items/autoReplyPendingConfirm',
    method: 'POST',
    data
  });
}

// 确认并发送人工确认的AI回复草稿（内容可编辑）
export function confirmAutoReplyRecord(data: {
  recordId: number;
  replyContent?: string;
}) {
  return request<string>({
    url: '/items/autoReplyConfirm',
    method: 'POST',
    data
  });
}

// 忽略待人工确认的AI回复草稿
export function dismissAutoReplyRecord(data: {
  recordId: number;
}) {
  return request<string>({
    url: '/items/autoReplyDismiss',
    method: 'POST',
    data
  });
}

// 擦亮商品响应
export interface PolishItemResponse {
  total: number;
  success: number;
  failed: number;
  details: Array<{
    xyGoodsId: string;
    success: boolean;
    message: string;
  }>;
}

// 擦亮单件商品
export function polishGoods(data: {
  xianyuAccountId: number;
  xyGoodsId: string;
}) {
  return request<PolishItemResponse>({
    url: '/items/polish',
    method: 'POST',
    data
  });
}

// 批量擦亮商品（若 xyGoodsIds 为空则擦亮账号下全部在售商品）
export function batchPolishGoods(data: {
  xianyuAccountId: number;
  xyGoodsIds?: string[];
}) {
  return request<PolishItemResponse>({
    url: '/items/batchPolish',
    method: 'POST',
    data
  });
}


export function getSyncProgress(syncId: string) {
  return request<SyncProgressResponse>({
    url: `/items/syncProgress/${syncId}`,
    method: 'GET'
  });
}

export function checkSyncing(accountId: number) {
  return request<boolean>({
    url: `/items/syncing/${accountId}`,
    method: 'GET'
  });
}

// 自动回复决策日志：买家消息未被自动回复的原因记录
export interface AutoReplyDecision {
  id: number;
  xianyuAccountId: number;
  sId: string | null;
  xyGoodsId: string | null;
  buyerUserId: string | null;
  buyerMessage: string | null;
  reasonCode: 'HUMAN_TAKEOVER' | 'GOODS_NOT_FOUND' | 'NO_REPLY_STRATEGY' | 'NO_REPLY_CONTENT' | string;
  detail: string | null;
  createTime: string;
}

export interface AutoReplyDecisionListResponse {
  list: AutoReplyDecision[];
  totalCount: number;
  pageNum: number;
  pageSize: number;
}

// 获取自动回复决策日志（可按账号/原因筛选）
export function getAutoReplyDecisions(data: {
  xianyuAccountId?: number | null;
  reasonCode?: string | null;
  pageNum?: number;
  pageSize?: number;
}) {
  return request<AutoReplyDecisionListResponse>({
    url: '/items/autoReplyDecisions',
    method: 'POST',
    data
  });
}
