import { onMounted, onUnmounted, watch, type Ref } from 'vue'

/**
 * 页面可见性感知的轮询定时器。
 *
 * 后台标签页 / 最小化窗口时暂停 interval，回到前台立即补一次刷新。
 * 纯 interval 的实现在隐藏页面里照样以固定频率发请求，既浪费服务端资源，
 * 也加速笔记本电池消耗；多个页面各自实现暂停逻辑又会重复造轮子。
 */
export function useVisibilityPolling(opts: {
  enabled: Ref<boolean>
  intervalMs: Ref<number>
  tick: () => void
  /** 隐藏超过该毫秒数后回到前台才补刷，避免频繁切换重复请求 */
  resumeRefreshThresholdMs?: number
}) {
  const { enabled, intervalMs, tick } = opts
  const threshold = opts.resumeRefreshThresholdMs ?? 5000

  let timer: ReturnType<typeof setInterval> | null = null
  let hiddenAt: number | null = null

  const stop = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  const start = () => {
    stop()
    if (!enabled.value) return
    if (typeof document !== 'undefined' && document.hidden) return
    timer = setInterval(() => {
      if (!enabled.value) {
        stop()
        return
      }
      tick()
    }, Math.max(1000, intervalMs.value))
  }

  const handleVisibility = () => {
    if (document.hidden) {
      hiddenAt = Date.now()
      stop()
      return
    }
    const away = hiddenAt === null ? threshold : Date.now() - hiddenAt
    hiddenAt = null
    if (enabled.value) {
      if (away >= threshold) tick()
      start()
    }
  }

  // 配置变化时自动重启定时器，避免调用方忘记 restart 导致旧间隔持续生效
  watch([enabled, intervalMs], () => start())

  onMounted(() => {
    document.addEventListener('visibilitychange', handleVisibility)
    start()
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', handleVisibility)
    stop()
  })

  return {
    /** 配置（enabled / intervalMs）变化后重新启动定时器 */
    restart: start,
    stop
  }
}
