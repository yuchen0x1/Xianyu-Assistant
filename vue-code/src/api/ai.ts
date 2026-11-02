import { getAuthToken } from '@/utils/request'

// AI 对话请求
export interface ChatWithAIReq {
  msg: string
  goodsId: string
}

// 上传资料到 RAG 请求
export interface PutNewDataReq {
  content: string
  goodsId: string
}

// 查询 RAG 资料响应
export interface RAGDataItem {
  documentId: string
  goodsID: string
  content: string
  createTime: string
}

/** 构建带Token的headers */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json'
  }
  const token = getAuthToken()
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

// AI 对话 (SSE 流式)
// 后端 AIChatController 的 @RequestMapping 是 "/ai"（无 /api 前缀），
// 与其他控制器 @RequestMapping("/api/xxx") 不同，
// 所以不能用 request()（baseURL=/api），需用 fetch 直接请求
export function chatWithAI(data: ChatWithAIReq, signal?: AbortSignal): Promise<Response> {
  return fetch('/ai/chat', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data),
    signal
  })
}

// AI 对话测试（与自动回复流程一致）。账号/商品上下文可省略，
// 省略时后端会以当前系统提示词和模型配置直接测试连接。
export interface ChatTestWithAIReq {
  accountId?: number | null
  goodsId?: string | null
  msg: string
}

export function chatTestWithAI(data: ChatTestWithAIReq, signal?: AbortSignal): Promise<Response> {
  return fetch('/ai/chatTest', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data),
    signal
  })
}

export interface PolishDeliveryContentReq {
  content: string
}

export interface PolishDeliveryContentData {
  content: string
}

export function polishDeliveryContent(
  data: PolishDeliveryContentReq,
  signal?: AbortSignal
): Promise<Response> {
  return fetch('/ai/polishDeliveryContent', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data),
    signal
  })
}

/**
 * 消费 AI 的 SSE 流并返回完整文本。
 * 后端当前直接返回 Flux<String>，Spring 会将每个 token 序列化为 data: 行；
 * 同时兼容 OpenAI 风格的 JSON chunk，便于切换供应商时保持测试页可用。
 */
export async function consumeAIStream(
  response: Response,
  onChunk?: (chunk: string) => void
): Promise<string> {
  if (!response.ok) {
    let detail = ''
    try {
      detail = await response.text()
      if (detail) {
        try {
          const parsed = JSON.parse(detail) as Record<string, unknown>
          detail = String(parsed.message || parsed.msg || (parsed.error as Record<string, unknown> | undefined)?.message || detail)
        } catch {
          // 非 JSON 错误响应直接使用文本
        }
      }
    } catch {
      // 读取错误响应失败时使用 HTTP 状态
    }
    throw new Error(detail.trim() || `请求失败（${response.status}）`)
  }

  // Provider chunks are not uniform: OpenAI-compatible multimodal responses may
  // put text in content arrays, while other providers nest it under delta or
  // message. Follow only known text-bearing keys so metadata objects are still
  // discarded and unknown envelopes retain their raw JSON below.
  const extractTextCandidate = (value: unknown, depth = 0): string | undefined => {
    if (depth > 8) return undefined
    if (typeof value === 'string') return value
    if (Array.isArray(value)) {
      const parts: string[] = []
      for (const item of value) {
        const text = extractTextCandidate(item, depth + 1)
        if (text !== undefined) parts.push(text)
      }
      return parts.length > 0 ? parts.join('') : undefined
    }
    if (value === null || typeof value !== 'object') return undefined

    const record = value as Record<string, unknown>
    for (const key of ['text', 'content', 'reply', 'delta', 'message']) {
      if (!Object.prototype.hasOwnProperty.call(record, key)) continue
      const text = extractTextCandidate(record[key], depth + 1)
      if (text !== undefined) return text
    }
    return undefined
  }

  const appendChunk = (raw: string, result: { value: string }) => {
    if (!raw || raw === '[DONE]') return

    let chunk = raw
    try {
      const parsed: unknown = JSON.parse(raw)
      if (typeof parsed === 'string') {
        chunk = parsed
      } else if (parsed !== null && typeof parsed === 'object') {
        const payload = parsed as Record<string, unknown>
        const choices = Array.isArray(payload.choices) ? payload.choices : []
        const firstChoice = choices[0] as Record<string, unknown> | undefined
        const candidates: unknown[] = [
          payload.reply,
          payload.content,
          payload.text,
          payload.delta,
          firstChoice?.delta,
          firstChoice?.message
        ]
        const textCandidate = candidates
          .map((candidate) => extractTextCandidate(candidate))
          .find((candidate): candidate is string => candidate !== undefined)
        if (textCandidate !== undefined) {
          chunk = textCandidate
        } else if (['reply', 'content', 'text', 'delta', 'choices'].some((key) => Object.prototype.hasOwnProperty.call(payload, key))) {
          // Known provider envelopes can contain role/finish metadata without text.
          chunk = ''
        }
      }
    } catch {
      // 当前后端返回的 token 通常不是 JSON，保留原文本。
    }

    if (typeof chunk !== 'string' || !chunk) return
    result.value += chunk
    onChunk?.(chunk)
  }

  const result = { value: '' }
  const reader = response.body?.getReader()
  if (!reader) {
    appendChunk(await response.text(), result)
    return result.value
  }

  const decoder = new TextDecoder()
  let buffer = ''
  let eventData: string[] = []
  let sseMode = false

  const flushEvent = () => {
    if (eventData.length > 0) {
      appendChunk(eventData.join('\n'), result)
      eventData = []
    }
  }

  const consumeLine = (line: string, hasLineBreak = false) => {
    // SSE 允许 CRLF，调用方已去掉 CR；注释和 event/id 行无需处理。
    if (line === '') {
      if (!sseMode && eventData.length === 0) {
        appendChunk('\n', result)
      } else {
        flushEvent()
      }
    } else if (line.startsWith('data:')) {
      sseMode = true
      // 规范规定 data: 后最多去掉一个空格，避免破坏 token 的首尾空白。
      eventData.push(line.startsWith('data: ') ? line.slice(6) : line.slice(5))
    } else if (line.startsWith(':') || line.startsWith('event:') || line.startsWith('id:') || line.startsWith('retry:')) {
      // SSE 注释和控制字段不属于模型正文。
    } else if (eventData.length === 0) {
      // 兼容未经过 SSE 包装的纯文本响应。
      appendChunk(`${line}${hasLineBreak && !sseMode ? '\n' : ''}`, result)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''
    lines.forEach((line) => consumeLine(line, true))
    if (done) break
  }

  if (buffer) consumeLine(buffer)
  flushEvent()
  return result.value
}

// 上传资料到 RAG 知识库
export function putNewDataToRAG(data: PutNewDataReq): Promise<Response> {
  return fetch('/ai/putNewData', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 查询 RAG 知识库资料
export function queryRAGData(data: { goodsId: string }): Promise<Response> {
  return fetch('/ai/queryRAGData', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 删除 RAG 知识库资料
export function deleteRAGData(data: { documentId: string }): Promise<Response> {
  return fetch('/ai/deleteRAGData', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// AI 状态信息
export interface AIStatus {
  enabled: boolean
  available: boolean
  apiKeyConfigured: boolean
  message: string
  baseUrl: string
  model: string
}

// 获取 AI 状态
export function getAIStatus(): Promise<Response> {
  return fetch('/ai/status', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({})
  })
}

// 可用模型条目（OpenAI 兼容 /models 端点返回）
export interface AIModelItem {
  id: string
  ownedBy?: string | null
}

// 获取可用模型列表（读取已保存的 API Key / Base URL，返回后由用户选择）
export function listModels(): Promise<Response> {
  return fetch('/ai/models', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({})
  })
}

// 保存固定资料
export function saveFixedMaterial(data: { accountId: number; goodsId: string; fixedMaterial: string }): Promise<Response> {
  return fetch('/ai/saveFixedMaterial', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 获取固定资料
export function getFixedMaterial(data: { accountId: number; goodsId: string }): Promise<Response> {
  return fetch('/ai/getFixedMaterial', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 同步商品详情到固定资料
export function syncDetailToFixedMaterial(data: { accountId: number; goodsId: string }): Promise<Response> {
  return fetch('/ai/syncDetailToFixedMaterial', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}
