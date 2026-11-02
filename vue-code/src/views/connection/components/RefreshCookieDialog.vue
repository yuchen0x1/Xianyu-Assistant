<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { generateQRCode, getQRCodeStatus } from '@/api/qrlogin'
import { showSuccess } from '@/utils'
import type { QRLoginSession } from '@/types'

interface Props {
  modelValue: boolean
  accountId: number
  currentUnb: string
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const qrCodeUrl = ref('')
const sessionId = ref('')
const status = ref<QRLoginSession['status']>('pending')
const statusText = ref('正在生成二维码...')
const verificationUrl = ref('')
const safeVerificationUrl = computed(() => {
  const value = verificationUrl.value.trim()
  return /^https?:\/\//i.test(value) ? value : ''
})
let pollTimer: number | null = null
let pollGeneration = 0
let pollInFlightGeneration: number | null = null

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    generateQR()
  } else {
    pollGeneration += 1
    stopPolling()
  }
}, { immediate: true })

const generateQR = async () => {
  const generation = ++pollGeneration
  stopPolling()
  verificationUrl.value = ''
  status.value = 'pending'
  statusText.value = '正在生成二维码...'
  try {
    const response = await generateQRCode(props.accountId)
    if (!props.modelValue || generation !== pollGeneration) return
    // request拦截器会自动处理错误，这里只处理成功的情况（后端成功码为 0 或 200）
    if (response.code === 0 || response.code === 200) {
      qrCodeUrl.value = response.data?.qrCodeUrl || ''
      sessionId.value = response.data?.sessionId || ''
      startPolling(generation)
    }
  } catch {
    // request拦截器已经显示了错误消息，这里不需要再显示
    console.error('生成二维码失败')
  }
}

const startPolling = (generation: number) => {
  if (!props.modelValue || generation !== pollGeneration || !sessionId.value) return
  pollTimer = window.setInterval(async () => {
    if (!props.modelValue || generation !== pollGeneration || !sessionId.value
      || pollInFlightGeneration === generation) return
    const currentSessionId = sessionId.value
    pollInFlightGeneration = generation
    try {
      const response = await getQRCodeStatus(currentSessionId)
      if (!props.modelValue || generation !== pollGeneration || currentSessionId !== sessionId.value) return
      if (response.code === 0 || response.code === 200) {
        const data = response.data
        status.value = data?.status || 'pending'
        verificationUrl.value = data?.verificationUrl || ''
        
        switch (data?.status) {
          case 'pending':
            statusText.value = '等待扫码...'
            break
          case 'scanned':
            statusText.value = '已扫码，等待确认...'
            break
          case 'confirmed':
            statusText.value = data.message || '账号凭据更新成功'
            handleLoginSuccess()
            break
          case 'expired':
            statusText.value = data.message || '二维码已过期'
            stopPolling()
            break
          case 'cancelled':
          case 'account_mismatch':
          case 'error':
          case 'not_found':
            statusText.value = data.message || '扫码刷新未完成，请重试'
            stopPolling()
            break
          case 'verification_required':
            statusText.value = data.message || '账号被风控，需要手机验证'
            stopPolling()
            break
        }
      }
    } catch {
      console.error('检查登录状态失败')
    } finally {
      if (pollInFlightGeneration === generation) {
        pollInFlightGeneration = null
      }
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const handleLoginSuccess = () => {
  stopPolling()
  showSuccess('账号凭据刷新成功')
  emit('success')
  handleClose()
}

const handleClose = () => {
  pollGeneration += 1
  stopPolling()
  emit('update:modelValue', false)
}

// 对话框开着时父组件可能被路由整体卸载，轮询必须随组件清理
onUnmounted(stopPolling)
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <h2 class="modal-title">扫码刷新Cookie</h2>
            <button class="modal-close" @click="handleClose">×</button>
          </div>
          <div class="modal-body">
            <div class="qr-code-wrap">
              <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
              <div v-else class="qr-loading">
                <div class="loading-spinner"></div>
              </div>
            </div>
            <p class="qr-tip">请使用闲鱼APP扫描二维码登录</p>
            <p class="qr-warning">请确保扫码账号与当前账号一致</p>
            <div class="qr-status">
              <span class="status-tag" :class="status === 'confirmed' ? 'is-success' : ''">{{ statusText }}</span>
            </div>
            <div v-if="status === 'verification_required'" class="verification-panel">
              <p class="verification-panel__text">请在闲鱼官方页面完成手机验证，验证完成后重新扫码。</p>
              <a v-if="safeVerificationUrl" class="verification-panel__link" :href="safeVerificationUrl" target="_blank" rel="noopener noreferrer">打开闲鱼手机验证</a>
              <p v-else class="verification-panel__hint">当前未返回验证链接，请打开闲鱼 App 或官方网站完成验证。</p>
              <button type="button" class="verification-panel__retry" @click="generateQR">验证完成，重新扫码</button>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="handleClose">取消</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
  width: 100%;
  max-width: 360px;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.14), 0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: var(--radius-xs);
  border: none;
  background: transparent;
  color: var(--text-2);
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}

.modal-body {
  padding: 0 20px 20px;
  text-align: center;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: var(--bg-hover);
  color: var(--text-1);
}

.btn-secondary:hover {
  background: var(--bg-hover);
}

.qr-code-wrap {
  margin: 16px 0;
  display: flex;
  justify-content: center;
}

.qr-code {
  max-width: 180px;
  border-radius: var(--radius-sm);
}

.qr-loading {
  width: 180px;
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f7;
  border-radius: var(--radius-sm);
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 2px solid #e5e5e5;
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.qr-tip {
  margin: 8px 0;
  color: var(--text-1);
  font-size: 14px;
}

.qr-warning {
  margin: 4px 0;
  color: var(--warning-500);
  font-size: 13px;
  font-weight: 500;
}

.qr-status {
  margin: 10px 0;
  min-height: 28px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.status-tag {
  padding: 5px 12px;
  border-radius: var(--radius-xs);
  font-size: 12px;
  font-weight: 500;
  background: var(--bg-muted);
  color: var(--text-2);
}

.status-tag.is-success {
  background: rgba(52, 199, 89, 0.1);
  color: var(--success-500);
}

.verification-panel {
  display: grid;
  gap: 8px;
  margin: 12px 0 0;
  padding: 12px;
  border: 1px solid var(--danger-200, #fecaca);
  border-radius: var(--radius-sm);
  background: var(--danger-50, #fef2f2);
  text-align: left;
}

.verification-panel__text,
.verification-panel__hint {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.5;
}

.verification-panel__link {
  color: var(--brand-700);
  font-size: 13px;
  font-weight: 600;
  text-decoration: underline;
}

.verification-panel__retry {
  min-height: 34px;
  border: 1px solid var(--brand-500);
  border-radius: var(--radius-xs);
  background: var(--brand-500);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.session-id {
  margin: 8px 0;
  font-size: 11px;
  color: var(--text-2);
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}
</style>
