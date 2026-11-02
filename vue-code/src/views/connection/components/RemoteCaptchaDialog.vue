<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { closeCaptchaSession, getCaptchaSession, openCaptchaBrowser, sendCaptchaMouse, type CaptchaLaunchResult } from '@/api/websocket'
import { showError } from '@/utils'

const props = defineProps<{ modelValue: boolean; accountId: number }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'completed'): void }>()

const loading = ref(false)
const sessionId = ref('')
const sessionActive = ref(false)
const screenshot = ref('')
const message = ref('正在打开服务器验证页面…')
const viewportWidth = ref(1280)
const viewportHeight = ref(800)
const dragging = ref(false)
const cooldownUntil = ref(0)
const cooldownSeconds = ref(0)
let pollTimer: number | null = null
let cooldownTimer: number | null = null
let pointerQueue: Promise<void> = Promise.resolve()

const markCooldown = (value: string) => {
  const seconds = value.match(/等待约\s*(\d+)\s*秒/)?.[1]
  if (seconds) {
    cooldownUntil.value = Date.now() + Number(seconds) * 1000
  } else if (/冷却|平台验证页已失效|T73mam|Lxvm|aanvm/i.test(value)) {
    cooldownUntil.value = Date.now() + 5 * 60 * 1000
  }
  if (cooldownUntil.value > Date.now()) {
    cooldownSeconds.value = Math.ceil((cooldownUntil.value - Date.now()) / 1000)
    if (cooldownTimer === null) {
      cooldownTimer = window.setInterval(() => {
        cooldownSeconds.value = Math.max(0, Math.ceil((cooldownUntil.value - Date.now()) / 1000))
        if (cooldownSeconds.value === 0 && cooldownTimer !== null) {
          window.clearInterval(cooldownTimer)
          cooldownTimer = null
        }
      }, 1000)
    }
  }
}

const isCooldown = () => cooldownSeconds.value > 0

const applyMessage = (value: string) => {
  message.value = value
  markCooldown(value)
}

const stopPolling = () => {
  if (pollTimer !== null) window.clearInterval(pollTimer)
  pollTimer = null
}

const poll = async () => {
  if (!sessionId.value) return
  try {
    const response = await getCaptchaSession(props.accountId, sessionId.value)
    if (response.code !== 0 && response.code !== 200) return
    const data = response.data
    if (data && data.active === false) {
      sessionActive.value = false
      screenshot.value = ''
    } else if (data?.active) {
      sessionActive.value = true
    }
    // Do not replace the image while the pointer is down. Replacing its source
    // mid-drag can cancel pointer capture in Chromium and lose the slider move.
    if (data?.active && data?.screenshot && !dragging.value) {
      screenshot.value = `data:image/jpeg;base64,${data.screenshot}`
    }
    if (!data?.screenshot && !dragging.value && data?.message?.includes('正在获取新的验证链接')) {
      screenshot.value = ''
    }
    if (data?.message) applyMessage(data.message)
    if (data?.viewportWidth) viewportWidth.value = data.viewportWidth
    if (data?.viewportHeight) viewportHeight.value = data.viewportHeight
    if (data?.completed) {
      stopPolling()
      emit('completed')
      emit('update:modelValue', false)
    }
    if (data && !data.active) {
      stopPolling()
      if (!data.completed) {
        screenshot.value = ''
        message.value = data.message || '验证会话已失效，请重新打开'
      }
    }
  } catch {
    // transient polling failure is harmless
  }
}

const openSession = async () => {
  if (isCooldown()) return
  loading.value = true
  sessionActive.value = false
  screenshot.value = ''
  applyMessage('正在打开服务器验证页面…')
  try {
    const response = await openCaptchaBrowser(props.accountId)
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '无法打开验证页面')
    const data = response.data as CaptchaLaunchResult
    sessionId.value = data?.sessionId || ''
    sessionActive.value = Boolean(sessionId.value)
    applyMessage(data?.message || '正在打开服务器验证页面…')
    viewportWidth.value = data?.viewportWidth || 1280
    viewportHeight.value = data?.viewportHeight || 800
    if (data?.accepted && data?.screenshot && sessionActive.value) {
      screenshot.value = `data:image/jpeg;base64,${data.screenshot}`
    }
    if (!data?.accepted) {
      sessionId.value = ''
      sessionActive.value = false
      return
    }
    if (!sessionId.value) throw new Error('服务器未创建验证会话，请重试')
    stopPolling()
    pollTimer = window.setInterval(() => void poll(), 1000)
    await poll()
  } catch (error) {
    const errorMessage = (error as Error)?.message || '打开验证页面失败'
    applyMessage(errorMessage)
    showError(errorMessage)
  } finally {
    loading.value = false
  }
}

const pointerPosition = (event: PointerEvent) => {
  const image = event.currentTarget as HTMLImageElement
  const rect = image.getBoundingClientRect()
  return {
    x: Math.max(0, Math.min(viewportWidth.value, (event.clientX - rect.left) * viewportWidth.value / rect.width)),
    y: Math.max(0, Math.min(viewportHeight.value, (event.clientY - rect.top) * viewportHeight.value / rect.height))
  }
}

const sendPointer = async (event: PointerEvent, type: 'down' | 'move' | 'up') => {
  if (!sessionId.value || !sessionActive.value || (type === 'move' && !dragging.value)) return
  const point = pointerPosition(event)
  if (type === 'down') dragging.value = true
  if (type === 'up') dragging.value = false
  const session = sessionId.value
  // Preserve the browser mouse sequence even when the user moves faster than
  // the API round-trip time. CAPTCHA widgets are sensitive to event ordering.
  const request = async () => {
    try { await sendCaptchaMouse(props.accountId, session, type, point.x, point.y) } catch { /* polling will report expired sessions */ }
  }
  pointerQueue = pointerQueue.then(request, request)
  await pointerQueue
}

const handlePointerDown = (event: PointerEvent) => {
  const image = event.currentTarget as HTMLImageElement
  try { image.setPointerCapture(event.pointerId) } catch { /* capture is best effort */ }
  void sendPointer(event, 'down')
}

const handlePointerMove = (event: PointerEvent) => {
  void sendPointer(event, 'move')
}

const handlePointerUp = (event: PointerEvent) => {
  const image = event.currentTarget as HTMLImageElement
  try {
    if (image.hasPointerCapture(event.pointerId)) image.releasePointerCapture(event.pointerId)
  } catch { /* capture is best effort */ }
  void sendPointer(event, 'up')
}

const close = async () => {
  stopPolling()
  if (sessionId.value) await closeCaptchaSession(props.accountId, sessionId.value).catch(() => undefined)
  sessionId.value = ''
  sessionActive.value = false
  screenshot.value = ''
  pointerQueue = Promise.resolve()
  emit('update:modelValue', false)
}

watch(() => props.modelValue, (open) => {
  if (open) void openSession()
  else stopPolling()
})
onBeforeUnmount(() => {
  stopPolling()
  if (cooldownTimer !== null) window.clearInterval(cooldownTimer)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="remote-mask" @click.self="close">
        <section class="remote-dialog">
          <header class="remote-header">
            <div>
              <h2>同会话滑块验证</h2>
              <p>验证画面来自服务器登录会话，直接在图上拖动滑块</p>
            </div>
            <button type="button" class="remote-close" aria-label="关闭" @click="close">×</button>
          </header>
          <div class="remote-body">
            <div v-if="loading && !screenshot" class="remote-loading">{{ message }}</div>
            <div v-else-if="screenshot && sessionActive" class="remote-canvas-wrap">
              <img
                :src="screenshot"
                alt="服务器滑块验证画面"
                draggable="false"
                @pointerdown.prevent="handlePointerDown"
                @pointermove.prevent="handlePointerMove"
                @pointerup.prevent="handlePointerUp"
                @pointercancel.prevent="handlePointerUp"
              />
            </div>
            <div v-else class="remote-loading">{{ message }}</div>
            <p class="remote-status">{{ message }}</p>
          </div>
          <footer class="remote-footer">
            <button type="button" class="remote-secondary" @click="close">关闭</button>
            <button type="button" class="remote-primary" :disabled="loading || isCooldown()" @click="openSession">
              {{ isCooldown() ? `冷却后重试（${cooldownSeconds}s）` : '重新打开' }}
            </button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.remote-mask { position: fixed; inset: 0; z-index: 2200; display: flex; align-items: center; justify-content: center; padding: 18px; background: rgba(15, 23, 42, .58); }
.remote-dialog { width: min(920px, 100%); max-height: calc(100vh - 36px); display: flex; flex-direction: column; overflow: hidden; background: #fff; border-radius: 8px; box-shadow: 0 22px 70px rgba(0,0,0,.24); }
.remote-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px 20px; border-bottom: 1px solid #e5e7eb; }
.remote-header h2 { margin: 0; color: #111827; font-size: 17px; }
.remote-header p, .remote-status { margin: 6px 0 0; color: #4b5563; font-size: 13px; }
.remote-close { border: 0; background: transparent; color: #4b5563; font-size: 26px; line-height: 1; cursor: pointer; }
.remote-body { padding: 16px; overflow: auto; background: #f3f4f6; }
.remote-canvas-wrap { display: flex; justify-content: center; overflow: auto; background: #111827; border-radius: 6px; }
.remote-canvas-wrap img { display: block; width: min(100%, 1280px); height: auto; max-height: 68vh; object-fit: contain; cursor: crosshair; touch-action: none; user-select: none; }
.remote-loading { min-height: 300px; display: grid; place-items: center; color: #374151; background: #fff; border-radius: 6px; }
.remote-status { color: #1f2937; }
.remote-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 20px; border-top: 1px solid #e5e7eb; }
.remote-footer button { border: 0; border-radius: 6px; padding: 8px 16px; font-size: 13px; cursor: pointer; }
.remote-secondary { background: #eef0f3; color: #1f2937; }
.remote-primary { background: #1677ff; color: #fff; }
.remote-primary:disabled { opacity: .55; cursor: default; }
</style>
