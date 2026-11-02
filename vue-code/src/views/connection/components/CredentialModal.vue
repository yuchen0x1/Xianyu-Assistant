<script setup lang="ts">
import IconCookie from '@/components/icons/IconCookie.vue'
import IconKey from '@/components/icons/IconKey.vue'
import IconQrCode from '@/components/icons/IconQrCode.vue'
import IconClose from '@/components/icons/IconClose.vue'

interface ConnectionStatus {
  xianyuAccountId?: number
  connected?: boolean
  status?: string
  cookieStatus?: number
  cookieConfigured?: boolean
  cookieLength?: number
  h5TokenConfigured?: boolean
  h5TokenLength?: number
  websocketTokenConfigured?: boolean
  websocketTokenLength?: number
  tokenExpireTime?: number
}

interface Props {
  modelValue: boolean
  connectionStatus: ConnectionStatus | null
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'qr-update'): void
  (e: 'manual-update'): void
}

defineProps<Props>()
const emit = defineEmits<Emits>()

const getCookieStatusColor = (status?: number) => {
  if (status === 1) return 'var(--success-500)'
  if (status === 2) return 'var(--warning-500)'
  if (status === 3) return 'var(--danger-500)'
  return 'var(--text-2)'
}

const getCookieStatusText = (status?: number) => {
  if (status === 1) return '有效'
  if (status === 2) return '过期'
  if (status === 3) return '失效'
  return '未知'
}

const getTokenStatusText = (timestamp?: number) => {
  if (!timestamp) return '未设置'
  return Date.now() > timestamp ? '已过期' : '有效'
}

const getTokenStatusColor = (timestamp?: number) => {
  if (!timestamp) return 'var(--text-2)'
  return Date.now() > timestamp ? 'var(--danger-500)' : 'var(--success-500)'
}

const getMH5TkStatusText = (configured?: boolean) => {
  if (!configured) return '未设置'
  return '有效'
}

const getMH5TkStatusColor = (configured?: boolean) => {
  if (!configured) return 'var(--text-2)'
  return 'var(--success-500)'
}

const formatTimestamp = (timestamp?: number) => {
  if (!timestamp) return '未设置'
  const date = new Date(timestamp)
  return date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).replace(/\//g, '-')
}

const handleClose = () => {
  emit('update:modelValue', false)
}

const handleQRUpdate = () => {
  emit('qr-update')
}

const handleManualUpdate = () => {
  emit('manual-update')
}
</script>

<template>
  <Transition name="modal-fade">
    <div v-if="modelValue" class="modal-overlay" @click="handleClose">
      <div class="modal-container" @click.stop>
        <!-- Header -->
        <div class="modal-header">
          <h2 class="modal-title">凭证详情</h2>
          <button class="modal-close" @click="handleClose">
            <IconClose />
          </button>
        </div>

        <!-- Content -->
        <div class="modal-content">
          <!-- Action Buttons -->
          <div class="action-buttons">
            <button class="btn btn--primary" @click="handleQRUpdate">
              <IconQrCode />
              <span>扫码更新</span>
            </button>
            <button class="btn btn--secondary" @click="handleManualUpdate">
              <IconCookie />
              <span>手动更新Cookie</span>
            </button>
          </div>

          <!-- Credential Items -->
          <div class="credential-list">
            <!-- Cookie -->
            <div class="credential-item">
              <div class="credential-item__header">
                <div class="credential-item__left">
                  <div class="credential-item__icon credential-item__icon--cookie">
                    <IconCookie />
                  </div>
                  <span class="credential-item__name">Cookie 凭证</span>
                </div>
                <span class="credential-item__status" :style="{ color: getCookieStatusColor(connectionStatus?.cookieStatus) }">
                  {{ getCookieStatusText(connectionStatus?.cookieStatus) }}
                </span>
              </div>
              <div v-if="connectionStatus?.cookieConfigured" class="credential-item__value">
                凭证已安全保存
                <span class="credential-item__meta">{{ connectionStatus.cookieLength || 0 }} 字符</span>
              </div>
              <div v-else class="credential-item__value credential-item__value--empty">未设置</div>
            </div>

            <!-- WebSocket Token -->
            <div class="credential-item">
              <div class="credential-item__header">
                <div class="credential-item__left">
                  <div class="credential-item__icon credential-item__icon--token">
                    <IconKey />
                  </div>
                  <span class="credential-item__name">WebSocket Token</span>
                </div>
                <span class="credential-item__status" :style="{ color: getTokenStatusColor(connectionStatus?.tokenExpireTime) }">
                  {{ getTokenStatusText(connectionStatus?.tokenExpireTime) }}
                </span>
              </div>
              <div v-if="connectionStatus?.websocketTokenConfigured" class="credential-item__value">
                凭证已安全保存
                <span class="credential-item__meta">{{ connectionStatus.websocketTokenLength || 0 }} 字符</span>
              </div>
              <div v-else class="credential-item__value credential-item__value--empty">未设置</div>
              <div v-if="connectionStatus?.tokenExpireTime" class="credential-item__expire">
                过期时间: {{ formatTimestamp(connectionStatus.tokenExpireTime) }}
              </div>
            </div>

            <!-- H5 Token -->
            <div class="credential-item">
              <div class="credential-item__header">
                <div class="credential-item__left">
                  <div class="credential-item__icon credential-item__icon--h5">
                    <IconKey />
                  </div>
                  <span class="credential-item__name">H5 Token (_m_h5_tk)</span>
                </div>
                <span class="credential-item__status" :style="{ color: getMH5TkStatusColor(connectionStatus?.h5TokenConfigured) }">
                  {{ getMH5TkStatusText(connectionStatus?.h5TokenConfigured) }}
                </span>
              </div>
              <div v-if="connectionStatus?.h5TokenConfigured" class="credential-item__value">
                凭证已安全保存
                <span class="credential-item__meta">{{ connectionStatus.h5TokenLength || 0 }} 字符</span>
              </div>
              <div v-else class="credential-item__value credential-item__value--empty">未设置</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: color-mix(in srgb, var(--bg-app) 58%, transparent);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.modal-container {
  width: min(680px, 92vw);
  background: var(--glass-bg-strong);
  border: 1px solid var(--glass-border-strong);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--glass-shadow-float);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  animation: slideUp 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);
}

@keyframes slideUp {
  from {
    transform: translateY(20px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--glass-bg-soft);
  flex-shrink: 0;
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
  letter-spacing: 0;
}

.modal-close {
  background: none;
  border: none;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-2);
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s;
  -webkit-tap-highlight-color: transparent;
  border: 1px solid transparent;
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
  border-color: var(--glass-border);
}

.modal-close svg {
  width: 20px;
  height: 20px;
}

.modal-content {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: none;
  padding: 24px;
}

.modal-content::-webkit-scrollbar {
  display: none;
}

.action-buttons {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 16px;
  font-size: 15px;
  font-weight: 600;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  -webkit-tap-highlight-color: transparent;
  letter-spacing: 0;
  flex: 1;
}

.btn svg {
  width: 18px;
  height: 18px;
}

.btn--primary {
  background: linear-gradient(105deg, var(--brand-600), var(--info-500));
  color: white;
  box-shadow: 0 4px 12px rgba(0, 122, 255, 0.3);
}

.btn--primary:hover {
  filter: brightness(1.05);
  box-shadow: 0 6px 16px rgba(0, 122, 255, 0.4);
}

.btn--primary:active {
  transform: scale(0.96);
}

.btn--secondary {
  background: var(--glass-bg-soft);
  color: var(--text-1);
  border-color: var(--glass-border);
  box-shadow: none;
}

.btn--secondary:hover {
  background: var(--glass-bg);
  border-color: var(--glass-border-strong);
}

.btn--secondary:active {
  transform: scale(0.96);
}

.credential-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.credential-item {
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  padding: 16px;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.credential-item:hover {
  background: var(--glass-bg);
  border-color: var(--glass-border-strong);
}

.credential-item__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--glass-border);
}

.credential-item__left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.credential-item__icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.credential-item__icon svg {
  width: 16px;
  height: 16px;
}

.credential-item__icon--cookie {
  background: rgba(255, 149, 0, 0.15);
  color: var(--warning-500);
}

.credential-item__icon--token {
  background: rgba(52, 199, 89, 0.15);
  color: var(--success-500);
}

.credential-item__icon--h5 {
  background: rgba(0, 122, 255, 0.15);
  color: var(--brand-600);
}

.credential-item__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  letter-spacing: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.credential-item__status {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 8px;
  background: var(--bg-hover);
  flex-shrink: 0;
}

.credential-item__value {
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-2);
  word-break: break-all;
  line-height: 1.6;
  padding: 10px;
  background: color-mix(in srgb, var(--bg-muted) 64%, transparent);
  border-radius: var(--radius-sm);
  border: 1px solid var(--glass-border);
}

.credential-item__value--empty {
  color: var(--text-2);
  font-style: italic;
  background: var(--bg-muted);
}

.credential-item__meta {
  display: inline-block;
  margin-left: 8px;
  color: var(--text-2);
  font-size: 11px;
  font-weight: 500;
}

.credential-item__expire {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--glass-border);
  font-size: 12px;
  color: var(--text-2);
}

/* 手机端适配 */
@media screen and (max-width: 767.98px) {
  .modal-container {
    width: 90%;
    max-height: 90vh;
    border-radius: var(--radius-sm);
  }

  .modal-header {
    padding: 16px;
  }

  .modal-title {
    font-size: 16px;
  }

  .modal-content {
    padding: 16px;
  }

  .action-buttons {
    flex-direction: column;
    gap: 10px;
    margin-bottom: 16px;
  }

  .btn {
    padding: 12px 14px;
    font-size: 14px;
  }

  .credential-item {
    padding: 12px;
  }

  .credential-item__header {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
    margin-bottom: 10px;
    padding-bottom: 10px;
  }

  .credential-item__left {
    width: 100%;
  }

  .credential-item__status {
    align-self: flex-start;
    font-size: 11px;
    padding: 3px 8px;
  }

  .credential-item__icon {
    width: 28px;
    height: 28px;
  }

  .credential-item__icon svg {
    width: 14px;
    height: 14px;
  }

  .credential-item__name {
    font-size: 13px;
  }

  .credential-item__value {
    font-size: 11px;
    padding: 8px;
    line-height: 1.5;
  }

  .credential-item__meta {
    font-size: 10px;
  }

  .credential-item__expire {
    margin-top: 8px;
    padding-top: 8px;
    font-size: 11px;
  }
}

/* 平板端适配 */
@media screen and (min-width: 768px) and (max-width: 1023.98px) {
  .modal-container {
    width: 70%;
    max-height: 85vh;
  }

  .modal-content {
    padding: 20px;
  }

  .credential-item {
    padding: 14px;
  }

  .credential-item__name {
    font-size: 14px;
  }

  .credential-item__value {
    font-size: 11px;
  }
}

/* 电脑端适配 */
@media screen and (min-width: 1024px) {
  .modal-container {
    width: min(680px, 60%);
    max-height: 85vh;
  }

  .modal-content {
    padding: 32px;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .modal-overlay {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }

  .modal-container,
  .modal-header,
  .credential-item,
  .btn--secondary {
    background: var(--surface-solid);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
</style>
