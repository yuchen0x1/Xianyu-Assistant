import { describe, expect, it } from 'vitest'
import { deriveAutomationHealth } from '@/views/connection/connection-health'
import type { WebSocketStatus } from '@/api/websocket'

const baseStatus: WebSocketStatus = {
  xianyuAccountId: 3,
  connected: true,
  status: '已连接',
  autoReplyOn: true,
  lastAutoReplyStatus: 'IDLE',
  lastAutoReplyReason: 'NO_ACTIVITY',
}

describe('deriveAutomationHealth', () => {
  it('keeps captcha intervention visible even if stale connection data says connected', () => {
    const health = deriveAutomationHealth({ ...baseStatus, captchaPending: true })

    expect(health.state).toBe('captcha')
    expect(health.tone).toBe('warning')
    expect(health.title).toBe('等待官方验证')
  })

  it('surfaces the latest reply failure instead of showing a healthy switch', () => {
    const health = deriveAutomationHealth({
      ...baseStatus,
      lastAutoReplyStatus: 'FAILED',
      lastAutoReplyReason: 'SEND_FAILED',
    })

    expect(health.state).toBe('failed')
    expect(health.tone).toBe('danger')
    expect(health.detail).toContain('消息发送失败')
  })

  it('shows a ready state when connected with no customer activity yet', () => {
    const health = deriveAutomationHealth(baseStatus)

    expect(health.state).toBe('ready')
    expect(health.tone).toBe('success')
    expect(health.title).toBe('自动回复待命')
  })
})
