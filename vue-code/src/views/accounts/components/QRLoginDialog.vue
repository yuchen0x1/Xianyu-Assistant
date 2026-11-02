<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { generateQRCode, getQRCodeStatus } from '@/api/qrlogin'
import { showSuccess } from '@/utils'
import type { QRLoginSession } from '@/types'
import IconClose from '@/components/icons/IconClose.vue'

interface Props {
  modelValue: boolean
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const isMobile = ref(false)
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

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

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    checkScreenSize()
    window.addEventListener('resize', checkScreenSize)
    generateQR()
  } else {
    window.removeEventListener('resize', checkScreenSize)
    stopPolling()
  }
})

const generateQR = async () => {
  stopPolling()
  verificationUrl.value = ''
  status.value = 'pending'
  statusText.value = '正在生成二维码...'
  try {
    const response = await generateQRCode()
    if (response.code === 0 || response.code === 200) {
      qrCodeUrl.value = response.data?.qrCodeUrl || ''
      sessionId.value = response.data?.sessionId || ''
      startPolling()
    } else {
      throw new Error(response.msg || '生成二维码失败')
    }
  } catch {
    console.error('生成二维码失败')
  }
}

const startPolling = () => {
  if (!sessionId.value) return
  pollTimer = window.setInterval(async () => {
    if (!sessionId.value) return
    try {
      const response = await getQRCodeStatus(sessionId.value)
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
            statusText.value = data.message || '登录成功！账号已添加'
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
            statusText.value = data.message || '扫码登录未完成，请重试'
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
  showSuccess('账号添加成功')
  emit('success')
  handleClose()
}

const handleClose = () => {
  stopPolling()
  emit('update:modelValue', false)
}

// 对话框开着时父组件可能被路由整体卸载，轮询与监听器必须随组件清理
onUnmounted(() => {
  stopPolling()
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<template>
  <teleport to="body">
    <div v-if="modelValue" class="ui-modal-overlay" @click="handleClose">
      <div
        class="ui-modal ui-modal--qr"
        role="dialog"
        aria-modal="true"
        aria-labelledby="qr-login-title"
        @click.stop
      >
        <div class="ui-modal__header ui-modal__header--qr">
          <h2 id="qr-login-title" class="ui-modal__title">扫码添加闲鱼账号</h2>
          <button type="button" class="qr-close" aria-label="关闭" @click="handleClose">
            <IconClose aria-hidden="true" />
          </button>
        </div>

        <div class="ui-modal__body ui-modal__body--center">
          <div class="qr-code-container">
            <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
            <div v-else class="qr-skeleton" aria-hidden="true"></div>
          </div>

          <p class="qr-tip">请使用闲鱼APP扫描二维码登录</p>

          <div class="qr-status">
            <div class="status-badge" :class="`status-${status}`">
              {{ statusText }}
            </div>
          </div>

          <div v-if="status === 'verification_required'" class="verification-panel">
            <p class="verification-panel__text">
              请在闲鱼官方页面完成手机验证。验证完成后，返回这里重新扫码。
            </p>
            <a
              v-if="safeVerificationUrl"
              class="verification-panel__link"
              :href="safeVerificationUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              打开闲鱼手机验证
            </a>
            <p v-else class="verification-panel__hint">
              当前未返回验证链接，请打开闲鱼 App 或官方网站完成账号验证。
            </p>
            <button type="button" class="verification-panel__retry" @click="generateQR">
              验证完成，重新扫码
            </button>
          </div>
        </div>

        <div class="ui-modal__footer">
          <button type="button" class="ui-modal__btn" @click="handleClose">取消</button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.ui-modal--qr {
  width: min(360px, calc(100vw - 32px));
}

.ui-modal__header--qr {
  position: relative;
}

.qr-close {
  position: absolute;
  top: 12px;
  right: 12px;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-pill);
  background: var(--bg-muted);
  color: var(--text-2);
  font-size: 14px;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.qr-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}

.qr-close :deep(svg) {
  width: 16px;
  height: 16px;
}

.qr-code-container {
  display: flex;
  justify-content: center;
  margin: 4px 0 12px;
}

.qr-code {
  width: 200px;
  height: 200px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-1);
  background: var(--bg-elevated);
}

.qr-skeleton {
  width: 200px;
  height: 200px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-1);
  background: linear-gradient(
    90deg,
    var(--bg-muted) 0%,
    var(--bg-hover) 50%,
    var(--bg-muted) 100%
  );
  background-size: 200% 100%;
  animation: qr-skeleton 1.4s ease infinite;
}

@keyframes qr-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

.qr-tip {
  margin: 0 0 12px;
  color: var(--text-2);
  font-size: var(--text-md);
  line-height: 1.5;
}

.qr-status {
  display: flex;
  justify-content: center;
}

.status-badge {
  min-width: 132px;
  padding: 8px 14px;
  border-radius: var(--radius-pill);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  text-align: center;
}

.status-pending,
.status-scanned {
  color: var(--brand-700);
  background: var(--brand-50);
}

.status-confirmed {
  color: var(--success-600);
  background: var(--success-50);
}

.status-expired,
.status-cancelled,
.status-verification_required,
.status-account_mismatch,
.status-error,
.status-not_found {
  color: var(--danger-600);
  background: var(--danger-50);
}

.verification-panel {
  display: grid;
  gap: 8px;
  margin-top: 14px;
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
  font-size: var(--text-sm);
  line-height: 1.5;
}

.verification-panel__link {
  color: var(--brand-700);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  text-decoration: underline;
}

.verification-panel__retry {
  width: 100%;
  min-height: 34px;
  border: 1px solid var(--brand-500);
  border-radius: var(--radius-xs);
  background: var(--brand-500);
  color: white;
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  cursor: pointer;
}

.verification-panel__retry:hover {
  background: var(--brand-600, var(--brand-500));
}

@media screen and (max-width: 480px) {
  .qr-code,
  .qr-skeleton {
    width: 160px;
    height: 160px;
  }
}
</style>
