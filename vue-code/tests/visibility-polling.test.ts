import { createApp, h, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useVisibilityPolling } from '@/composables/useVisibilityPolling'

/**
 * 轮询可见性测试。
 *
 * 核心契约：
 * 1. 页面可见时按间隔回调；
 * 2. 页面隐藏时停止发请求（后台标签页不应继续打服务端）；
 * 3. 回到前台时补刷一次并恢复定时器；
 * 4. 组件卸载后不再回调。
 */
describe('useVisibilityPolling', () => {
  let hidden = false
  let app: ReturnType<typeof createApp> | null = null
  let host: HTMLElement

  const setHidden = (value: boolean) => {
    hidden = value
    document.dispatchEvent(new Event('visibilitychange'))
  }

  beforeEach(() => {
    hidden = false
    Object.defineProperty(document, 'hidden', {
      configurable: true,
      get: () => hidden
    })
    host = document.createElement('div')
    document.body.appendChild(host)
    vi.useFakeTimers()
  })

  afterEach(() => {
    app?.unmount()
    app = null
    host.remove()
    vi.useRealTimers()
  })

  function mountPolling(options: { enabledInitially?: boolean; interval?: number } = {}) {
    const tick = vi.fn()
    const enabled = ref(options.enabledInitially ?? true)
    const intervalMs = ref(options.interval ?? 1000)

    app = createApp({
      setup() {
        useVisibilityPolling({ enabled, intervalMs, tick })
        return () => h('div')
      }
    })
    app.mount(host)
    return { tick, enabled, intervalMs }
  }

  it('ticks on the configured interval while visible', async () => {
    const { tick } = mountPolling({ interval: 1000 })
    await nextTick()

    expect(tick).not.toHaveBeenCalled()
    vi.advanceTimersByTime(3000)
    expect(tick).toHaveBeenCalledTimes(3)
  })

  it('stops ticking while the page is hidden', async () => {
    const { tick } = mountPolling({ interval: 1000 })
    await nextTick()

    vi.advanceTimersByTime(1000)
    expect(tick).toHaveBeenCalledTimes(1)

    setHidden(true)
    vi.advanceTimersByTime(5000)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('refreshes once and resumes after a long time in the background', async () => {
    const { tick } = mountPolling({ interval: 1000 })
    await nextTick()

    setHidden(true)
    // 超过默认 5s 阈值：回到前台应补刷一次
    vi.advanceTimersByTime(6000)
    setHidden(false)

    expect(tick).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(2000)
    expect(tick).toHaveBeenCalledTimes(3)
  })

  it('skips the catch-up refresh for a quick tab switch', async () => {
    const { tick } = mountPolling({ interval: 1000 })
    await nextTick()

    setHidden(true)
    // 1s 内切回属于快速切换，不补刷，避免抖动造成重复请求
    vi.advanceTimersByTime(1000)
    setHidden(false)

    expect(tick).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1000)
    expect(tick).toHaveBeenCalledTimes(1)
  })

  it('does not tick at all when disabled', async () => {
    const { tick } = mountPolling({ enabledInitially: false, interval: 1000 })
    await nextTick()

    vi.advanceTimersByTime(5000)
    expect(tick).not.toHaveBeenCalled()
  })

  it('restarts with the new interval when config changes', async () => {
    const { tick, intervalMs } = mountPolling({ interval: 1000 })
    await nextTick()
    vi.advanceTimersByTime(1000)
    expect(tick).toHaveBeenCalledTimes(1)

    intervalMs.value = 5000
    await nextTick()

    // 旧间隔失效：再过 3 秒不应触发
    vi.advanceTimersByTime(3000)
    expect(tick).toHaveBeenCalledTimes(1)

    // 新间隔生效
    vi.advanceTimersByTime(2000)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('stops ticking after unmount', async () => {
    const { tick } = mountPolling({ interval: 1000 })
    await nextTick()

    vi.advanceTimersByTime(1000)
    expect(tick).toHaveBeenCalledTimes(1)

    app?.unmount()
    app = null

    vi.advanceTimersByTime(5000)
    expect(tick).toHaveBeenCalledTimes(1)
  })
})