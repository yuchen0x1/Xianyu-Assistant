import { request } from '@/utils/request';

// 消息信息
export interface ChatMessage {
  id: number;
  xianyuAccountId: number;
  lwp: string;
  pnmId: string;
  sid: string;
  contentType: number;
  msgContent: string;
  senderUserName: string;
  senderUserId: string;
  senderAppV: string;
  senderOsType: string;
  reminderUrl: string;
  xyGoodsId: string;
  completeMsg: string;
  messageTime: string | number;
  createTime: string;
  isNew?: boolean;
}

// 消息列表响应
export interface MessageListResponse {
  list: ChatMessage[];
  totalCount: number;
  totalPage: number;
  pageNum: number;
  pageSize: number;
}

// 获取消息列表
export function getMessageList(data: {
  xianyuAccountId: number;
  xyGoodsId?: string;
  pageNum?: number;
  pageSize?: number;
  filterCurrentAccount?: boolean; // 过滤当前账号消息
}) {
  return request<MessageListResponse>({
    url: '/msg/list',
    method: 'POST',
    data
  });
}

// 根据会话ID获取上下文消息
export function getContextMessages(data: {
  sid: string;
  limit?: number;
  offset?: number;
}) {
  return request<ChatMessage[]>({
    url: '/msg/context',
    method: 'POST',
    data: { sid: data.sid, limit: data.limit || 20, offset: data.offset || 0 }
  });
}

// 发送消息
export function sendMessage(data: {
  xianyuAccountId: number;
  cid: string;
  toId: string;
  text: string;
  xyGoodsId?: string;
}) {
  return request<string>({
    url: '/websocket/sendMessage',
    method: 'POST',
    data
  });
}

// 消息会话
export interface MsgSession {
  sid: string;
  buyerName: string;
  buyerUserId: string;
  xyGoodsId: string;
  lastContent: string;
  lastTime: string | number;
  messageCount: number;
  pendingReply: boolean;
}

// 会话列表响应
export interface MsgSessionResponse {
  list: MsgSession[];
  totalCount: number;
  totalPage: number;
  pageNum: number;
  pageSize: number;
}

// 获取会话列表
export function getMsgSessions(data: {
  xianyuAccountId: number;
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}) {
  return request<MsgSessionResponse>({
    url: '/msg/sessions',
    method: 'POST',
    data
  });
}
