// 通用响应类型
export interface ApiResponse<T = unknown> {
  code: number
  msg: string
  message?: string  // 兼容字段
  data?: T
}

// 分页请求
export interface PageRequest {
  pageNum?: number
  pageSize?: number
}

// 分页响应
export interface PageResult<T> {
  records: T[]
  total: number
  pageNum: number
  pageSize: number
}

// 账号类型
export interface Account {
  id: number
  accountNote: string
  unb: string
  nickname?: string | null
  avatarUrl?: string | null
  creditScore?: number | null
  soldCount?: number | null
  followers?: number | null
  following?: number | null
  status: number
  createdTime: string
  updatedTime: string
}

// 商品类型
export interface Goods {
  id: number
  xyGoodId: string
  xianyuAccountId: number
  title: string
  coverPic: string
  infoPic: string
  detailInfo: string
  detailUrl: string
  soldPrice: string
  status: number
  createdTime: string
  updatedTime: string
  // 配置信息
  xianyuAutoDeliveryOn?: number
  xianyuAutoReplyOn?: number
}

// 商品列表请求
export interface GoodsListRequest {
  xianyuAccountId?: number
  page?: number
  pageSize?: number
}

// WebSocket 状态
export interface WebSocketStatus {
  connected: boolean
  accountId?: number
  message?: string
}

// 消息类型
export interface Message {
  id: number
  xianyuAccountId: number
  lwp: string
  pnmId: string
  sId: string
  contentType: number
  msgContent: string
  senderUserName: string
  senderUserId: string
  senderAppV: string
  senderOsType: string
  reminderUrl: string
  xyGoodsId: string
  completeMsg: string
  messageTime: number
  createTime: string
}

// 二维码登录
export interface QRLoginSession {
  sessionId?: string
  qrCodeUrl?: string
  status?: 'pending' | 'scanned' | 'confirmed' | 'expired' | 'cancelled' | 'verification_required' | 'account_mismatch' | 'error' | 'not_found'
  message?: string
  verificationUrl?: string
}

// 自动发货配置
export interface AutoDeliveryConfig {
  id?: number
  xianyuAccountId: number
  xyGoodsId: string
  type: number
  autoDeliveryContent: string
}

// 自动回复配置
export interface AutoReplyConfig {
  id?: number
  xianyuAccountId: number
  xyGoodsId: string
  keyword: string
  replyContent: string
  matchType: number
}

// 操作记录
export interface OperationRecord {
  id: number
  xianyuAccountId: number
  xyGoodsId: string
  buyerMessage?: string
  replyContent?: string
  deliveryContent?: string
  state: number
  createTime: string
}

// 系统设置
export interface SysSetting {
  settingKey: string
  settingValue: string
  settingDesc: string
}

// 连接状态
export interface ConnectionStatus {
  accountId: number
  accountNote: string
  connected: boolean
  wsConnected: boolean
  cookieValid: boolean
  lastHeartbeat: string
  errorMessage?: string
}

// 数据面板统计
export interface DataPanelStats {
  orderCount: number
  deliverySuccessCount: number
  deliveryFailCount: number
  aiReplyCount: number
  hasData: boolean
}

// 数据面板趋势
export interface DataPanelTrend {
  dates: string[]
  deliverySuccess: number[]
  deliveryFail: number[]
  aiReplies: number[]
}

// 销售额数据
export interface SalesRevenueData {
  labels: string[]
  values: number[]
}

// 通知配置
export interface NotifyConfig {
  enabled: boolean
  webhookUrl?: string
  notifyTypes: NotifyType[]
}

// 通知类型
export type NotifyType = 'ws_disconnect' | 'cookie_expire' | 'kami_alert' | 'kami_stockout' | 'delivery_fail' | 'captcha_required'

// 通知渠道
export type NotifyChannel = 'email' | 'wecom' | 'dingtalk' | 'serverchan' | 'pushplus'

// 通知渠道配置
export interface NotifyChannelConfig {
  channel: NotifyChannel
  label: string
  enabled: boolean
  config: Record<string, string>
}
