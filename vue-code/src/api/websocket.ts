import { request } from '@/utils/request';

// WebSocket连接状态
export interface WebSocketStatus {
  xianyuAccountId: number;
  connected: boolean;
  status: string;
  reconnecting?: boolean;
  reconnectAttempt?: number;
  nextReconnectAt?: number;
  captchaPending?: boolean;
  cookieStatus?: number;      // Cookie状态 1:有效 2:过期 3:失效
  cookieConfigured?: boolean;
  cookieLength?: number;
  h5TokenConfigured?: boolean;
  h5TokenLength?: number;
  websocketTokenConfigured?: boolean;
  websocketTokenLength?: number;
  tokenExpireTime?: number;   // Token过期时间戳（毫秒）
  autoDeliveryOn?: boolean;   // 是否有商品开启了自动发货
  autoReplyOn?: boolean;      // 是否有商品开启了自动回复
  lastWebSocketMessageAt?: number;
  webSocketMessageCount?: number;
  lastCustomerMessageAt?: number;
  lastAutoReplyStatus?: 'IDLE' | 'RECEIVED' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'SKIPPED' | 'FAILED';
  lastAutoReplyReason?: string;
  lastAutoReplyUpdatedAt?: number;
  lastSuccessfulReplyAt?: number;
}

// 获取连接状态
export function getConnectionStatus(accountId: number) {
  return request<WebSocketStatus>({
    url: '/websocket/status',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}

// 启动连接
export function startConnection(accountId: number) {
  return request({
    url: '/websocket/start',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}

// 停止连接
export function stopConnection(accountId: number) {
  return request({
    url: '/websocket/stop',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}

// 更新Cookie
export function updateCookie(data: { xianyuAccountId: number; cookieText: string }) {
  return request({
    url: '/websocket/updateCookie',
    method: 'POST',
    data
  });
}

// 发送消息请求参数
export interface SendMessageRequest {
  xianyuAccountId: number;
  cid: string;  // 会话ID (即 sid)
  toId: string;  // 接收方ID (即 senderUserId)
  text: string;  // 消息内容（注意：后端接收的参数名是 text，不是 content）
  xyGoodsId?: string;  // 闲鱼商品ID
}

// 发送消息
export function sendMessage(data: SendMessageRequest) {
  return request<{ code: number; msg?: string; data: unknown }>({
    url: '/websocket/sendMessage',
    method: 'POST',
    data
  });
}

// 清除验证等待状态
export function clearCaptchaWait(accountId: number) {
  return request({
    url: '/websocket/clearCaptchaWait',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}

// 在注入后台Cookie的可见浏览器中打开滑块验证页
export function openCaptchaBrowser(accountId: number) {
  return request<CaptchaLaunchResult>({
    url: '/websocket/openCaptchaBrowser',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}

export interface CaptchaLaunchResult {
  accepted: boolean;
  message: string;
  sessionId?: string;
  screenshot?: string;
  viewportWidth?: number;
  viewportHeight?: number;
}

export interface CaptchaSessionSnapshot {
  active: boolean;
  completed: boolean;
  message: string;
  screenshot?: string;
  viewportWidth?: number;
  viewportHeight?: number;
}

export function getCaptchaSession(accountId: number, sessionId: string) {
  return request<CaptchaSessionSnapshot>({
    url: '/websocket/captchaSession',
    method: 'POST',
    data: { xianyuAccountId: accountId, sessionId }
  });
}

export function sendCaptchaMouse(accountId: number, sessionId: string, eventType: 'down' | 'move' | 'up', x: number, y: number) {
  return request<boolean>({
    url: '/websocket/captchaMouse',
    method: 'POST',
    data: { xianyuAccountId: accountId, sessionId, eventType, x, y }
  });
}

export function closeCaptchaSession(accountId: number, sessionId: string) {
  return request<void>({
    url: '/websocket/captchaSession/close',
    method: 'POST',
    data: { xianyuAccountId: accountId, sessionId }
  });
}

// 刷新Token响应
export interface RefreshTokenResponse {
  mh5tkRefreshed: boolean;    // _m_h5_tk是否刷新成功
  wsTokenRefreshed: boolean;  // websocket_token是否刷新成功
  message: string;            // 提示信息
}

// 手动刷新Token

// API 兜底发货状态
export interface ApiDeliveryFallbackStatus {
  enabled: boolean;
  accountIds: string;
}

// 获取 API 兜底发货状态
export function getApiDeliveryFallbackStatus() {
  return request<ApiDeliveryFallbackStatus>({
    url: '/websocket/apiDeliveryFallbackStatus',
    method: 'post',
    data: {}
  });
}

// 更新 API 兜底发货配置
export function updateApiDeliveryFallback(data: { enabled: boolean; accountIds: string }) {
  return request<null>({
    url: '/websocket/updateApiDeliveryFallback',
    method: 'post',
    data
  });
}

export function refreshToken(accountId: number) {
  return request<RefreshTokenResponse>({
    url: '/websocket/refreshToken',
    method: 'POST',
    data: { xianyuAccountId: accountId }
  });
}

// 手动更新Token
export function updateToken(data: { xianyuAccountId: number; websocketToken: string }) {
  return request<string>({
    url: '/websocket/updateToken',
    method: 'POST',
    data
  });
}
