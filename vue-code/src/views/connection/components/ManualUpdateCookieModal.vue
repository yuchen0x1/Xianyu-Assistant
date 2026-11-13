<script setup lang="ts">
import { ref, watch } from 'vue'
import { updateCookie } from '@/api/websocket'
import { showSuccess, showError } from '@/utils'
import IconClose from '@/components/icons/IconClose.vue'
import IconHelp from '@/components/icons/IconHelp.vue'

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
    showHelpGuide.value = false
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

const toggleHelpGuide = () => {
  showHelpGuide.value = !showHelpGuide.value
}
</script>

<template>
  <Transition name="modal-fade">
    <div v-if="modelValue" class="modal-overlay" @click="handleClose">
      <div class="modal-container" @click.stop>
        <!-- Header -->
        <div class="modal-header">
          <h2 class="modal-title">手动更新Cookie</h2>
          <button class="help-link" @click="toggleHelpGuide">
            <IconHelp />
            <span>不会获取Cookie？</span>
          </button>
          <button class="modal-close" @click="handleClose">
            <IconClose />
          </button>
        </div>

        <!-- Content -->
        <div class="modal-content">
          <!-- Cookie instructions without captured credentials -->
          <div v-if="showHelpGuide" class="help-guide">
              <ol>
                <li>在浏览器中登录自己的闲鱼账号，按 F12 打开开发者工具。</li>
                <li>选择「网络 / Network」，刷新页面并打开一个发往 goofish.com 的请求。</li>
                <li>在「请求标头 / Request Headers」中找到 Cookie，复制它的完整值。</li>
                <li>返回表单粘贴并确认更新；不要复制响应标头的 Set-Cookie。</li>
              </ol>
              <p>Cookie 是登录凭据，请仅使用自己的账号，不要截图或分享完整内容。</p>
            </div>

          <!-- Form -->
          <div v-else class="form-container">
            <div class="form-group">
              <label class="form-label">Cookie值</label>
              <textarea
                v-model="cookieText"
                class="form-textarea"
                placeholder="请输入完整的Cookie字符串"
                rows="8"
              />
            </div>

            <!-- Tips -->
            <div class="tips-container">
              <div class="tips-title">提示</div>
              <div class="tips-content">
                <p class="tips-text">请从浏览器中复制完整的Cookie字符串</p>
                <p class="tips-text tips-text--highlight">
                  <span class="tips-label">重要字段：</span>
                  <span class="tips-field">unb</span>、
                  <span class="tips-field">_m_h5_tk</span>、
                  <span class="tips-field">cookie2</span>、
                  <span class="tips-field">t</span>
                </p>
                <p class="tips-text tips-text--bold">格式示例：</p>
                <p class="tips-example"><code>unb=你的账号ID; _m_h5_tk=你的值; cookie2=你的值; t=你的值</code></p>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="modal-footer">
          <button class="btn btn--secondary" @click="handleClose">
            {{ showHelpGuide ? '返回' : '取消' }}
          </button>
          <button v-if="!showHelpGuide" class="btn btn--primary" :disabled="loading" @click="handleSubmit">
            {{ loading ? '更新中...' : '确定更新' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
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
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
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
  border-bottom: 0.5px solid var(--border-1);
  background: var(--bg-elevated);
  flex-shrink: 0;
  gap: 12px;
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
  letter-spacing: 0;
  flex: 1;
}

.help-link {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: var(--text-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 8px;
  transition: all 0.2s;
  -webkit-tap-highlight-color: transparent;
  flex-shrink: 0;
}

.help-link:hover {
  color: var(--brand-600);
  background: rgba(0, 122, 255, 0.08);
}

.help-link svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
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
  flex-shrink: 0;
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-1);
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

.form-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  letter-spacing: 0;
}

.form-textarea {
  padding: 12px;
  font-size: 12px;
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  line-height: 1.5;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  background: var(--bg-muted);
  color: var(--text-1);
  resize: vertical;
  transition: all 0.2s;
}

.form-textarea:focus {
  outline: none;
  border-color: var(--brand-600);
  background: var(--bg-elevated);
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.1);
}

.form-textarea::placeholder {
  color: var(--text-2);
}

.tips-container {
  background: rgba(0, 122, 255, 0.08);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  padding: 12px;
}

.tips-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-600);
  margin-bottom: 8px;
}

.tips-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tips-text {
  font-size: 12px;
  color: var(--text-2);
  margin: 0;
  line-height: 1.5;
}

.tips-text--highlight {
  color: var(--text-1);
}

.tips-text--bold {
  font-weight: 500;
  color: var(--text-1);
}

.tips-label {
  color: var(--warning-500);
  font-weight: 600;
}

.tips-field {
  color: var(--danger-500);
  font-weight: 500;
}

.tips-example {
  font-size: 11px;
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  color: var(--text-2);
  line-height: 1.6;
  word-break: break-all;
  margin: 0;
}

.modal-footer {
  display: flex;
  gap: 12px;
  padding: 16px 24px;
  border-top: 0.5px solid var(--border-1);
  background: var(--bg-elevated);
  flex-shrink: 0;
}

.btn {
  flex: 1;
  padding: 12px 16px;
  font-size: 15px;
  font-weight: 600;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  -webkit-tap-highlight-color: transparent;
  letter-spacing: 0;
}

.btn--primary {
  background: var(--brand-600);
  color: white;
  box-shadow: 0 4px 12px rgba(0, 122, 255, 0.3);
}

.btn--primary:hover:not(:disabled) {
  background: #0066d6;
  box-shadow: 0 6px 16px rgba(0, 122, 255, 0.4);
}

.btn--primary:active:not(:disabled) {
  transform: scale(0.96);
}

.btn--primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn--secondary {
  background: var(--bg-hover);
  color: var(--text-1);
}

.btn--secondary:hover {
  background: var(--bg-hover);
}

.btn--secondary:active {
  transform: scale(0.96);
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

  .modal-footer {
    padding: 12px 16px;
    gap: 10px;
  }

  .btn {
    padding: 10px 14px;
    font-size: 14px;
  }

  .form-textarea {
    font-size: 11px;
  }

  .tips-text {
    font-size: 11px;
  }

  .tips-example {
    font-size: 10px;
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

  .form-textarea {
    font-size: 11px;
  }
}

/* 电脑端适配 */
@media screen and (min-width: 1024px) {
  .modal-container {
    width: 60%;
    max-height: 85vh;
  }

  .modal-content {
    padding: 32px;
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
