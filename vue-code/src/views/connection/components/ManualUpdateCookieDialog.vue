<script setup lang="ts">
import { ref, watch } from 'vue'
import { updateCookie } from '@/api/websocket'
import { showSuccess, showError } from '@/utils'

interface Props {
  modelValue: boolean
  accountId: number
  currentCookie: string
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const cookieText = ref('')
const loading = ref(false)
const showHelpGuide = ref(false)

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    cookieText.value = props.currentCookie || ''
  }
})

const handleSubmit = async () => {
  if (!cookieText.value.trim()) {
    showError('Cookie不能为空')
    return
  }

  loading.value = true
  try {
    const response = await updateCookie({
      xianyuAccountId: props.accountId,
      cookieText: cookieText.value.trim()
    })

    if (response.code === 200) {
      showSuccess('Cookie更新成功')
      handleClose()
      emit('success')
    }
  } catch {
    console.error('Cookie更新失败')
  } finally {
    loading.value = false
  }
}

const handleClose = () => {
  emit('update:modelValue', false)
  showHelpGuide.value = false
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container modal-container--lg">
          <div class="modal-header">
            <h2 class="modal-title">手动更新Cookie</h2>
            <div class="modal-header-actions">
              <button class="help-link" @click="showHelpGuide = !showHelpGuide">不会获取Cookie？</button>
              <button class="modal-close" @click="handleClose">×</button>
            </div>
          </div>
          <div class="modal-body">
            <div v-if="showHelpGuide" class="help-guide">
              <ol>
                <li>在浏览器中登录自己的闲鱼账号，按 F12 打开开发者工具。</li>
                <li>选择「网络 / Network」，刷新页面并打开一个发往 goofish.com 的请求。</li>
                <li>在「请求标头 / Request Headers」中找到 Cookie，复制它的完整值。</li>
                <li>返回表单粘贴并确认更新；不要复制响应标头的 Set-Cookie。</li>
              </ol>
              <p>Cookie 是登录凭据，请仅使用自己的账号，不要截图或分享完整内容。</p>
            </div>
            <template v-else>
              <div class="form-row">
                <label class="form-label">Cookie值</label>
                <textarea v-model="cookieText" class="form-textarea form-textarea--lg" placeholder="请输入完整的Cookie字符串"></textarea>
              </div>
              <div class="alert-box">
                <p class="alert-title">提示</p>
                <p class="alert-text">请从浏览器中复制完整的Cookie字符串</p>
                <p class="alert-text"><span class="highlight">重要字段：</span><code>unb</code>、<code>_m_h5_tk</code>、<code>cookie2</code>、<code>t</code></p>
                <p class="alert-subtitle">格式示例：</p>
                <p class="alert-code"><code>unb=你的账号ID; _m_h5_tk=你的值; cookie2=你的值; t=你的值</code></p>
              </div>
            </template>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="handleClose">{{ showHelpGuide ? '返回' : '取消' }}</button>
            <button v-if="!showHelpGuide" class="btn btn-primary" :class="{ 'is-loading': loading }" :disabled="loading" @click="handleSubmit">确定更新</button>
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
  max-width: 420px;
  max-height: 88vh;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.14), 0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-container--lg {
  max-width: 520px;
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

.modal-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.help-link {
  background: none;
  border: none;
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  transition: color 0.15s ease;
}

.help-link:hover {
  color: var(--brand-600);
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
  flex: 1;
  padding: 0 20px 20px;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}

.form-label {
  font-size: 13px;
  color: var(--text-1);
  font-weight: 500;
}

.form-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  font-size: 12px;
  line-height: 1.5;
  resize: none;
  background: var(--bg-elevated);
  color: var(--text-1);
  font-family: 'Courier New', Consolas, monospace;
}

.form-textarea--lg {
  height: 180px;
}

.form-textarea:focus {
  outline: none;
  border-color: var(--brand-600);
}

.alert-box {
  background: rgba(0, 113, 227, 0.06);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}

.alert-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-600);
  margin: 0 0 8px;
}

.alert-text {
  font-size: 12px;
  color: var(--text-1);
  margin: 4px 0;
}

.alert-text .highlight {
  color: var(--warning-500);
  font-weight: 600;
}

.alert-text code {
  background: rgba(255, 59, 48, 0.1);
  color: var(--danger-500);
  padding: 1px 4px;
  border-radius: 3px;
  font-family: inherit;
}

.alert-subtitle {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-1);
  margin: 8px 0 4px;
}

.alert-code {
  font-size: 11px;
  color: var(--text-2);
  line-height: 1.7;
  word-break: break-all;
  margin: 0;
  font-family: 'Courier New', Consolas, monospace;
}

.alert-code code {
  background: rgba(255, 59, 48, 0.1);
  color: var(--danger-500);
  padding: 1px 3px;
  border-radius: 3px;
}

.help-guide {
  padding: 12px 4px;
  color: var(--text-2);
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.help-guide ol { margin: 0; padding-left: 24px; }
.help-guide li + li { margin-top: 12px; }
.help-guide p { margin: 16px 0 0; color: var(--text-1); }

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

.btn-primary {
  background: var(--brand-600);
  color: var(--bg-elevated);
}

.btn-primary:hover:not(:disabled) {
  background: var(--brand-500);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

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
