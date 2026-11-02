<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue';
import IconAlert from '@/components/icons/IconAlert.vue';
import IconClose from '@/components/icons/IconClose.vue';
import IconInfo from '@/components/icons/IconInfo.vue';

interface Props {
  modelValue: boolean;
  captchaUrl?: string;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'confirm', url: string): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const isMobile = ref(false);
const steps = computed(() => isMobile.value
  ? [
      '打开扫码更新窗口并生成官方二维码',
      '使用闲鱼 App 扫码并确认登录',
      '如需手机验证，请在闲鱼官方 App 完成',
      '验证完成后重新扫码，系统自动恢复连接',
    ]
  : [
      '打开扫码更新窗口并生成官方二维码',
      '使用闲鱼 App 扫码并确认登录',
      '如需手机验证，请在闲鱼官方 App 完成',
      '验证完成后重新扫码，系统自动恢复连接',
    ]);

const checkDevice = () => {
  isMobile.value = window.innerWidth < 768;
};

const handleClose = () => {
  emit('update:modelValue', false);
};

const handleConfirm = () => {
  emit('confirm', props.captchaUrl?.trim() || '');
  emit('update:modelValue', false);
};

onMounted(() => {
  checkDevice();
  window.addEventListener('resize', checkDevice);
});

onUnmounted(() => {
  window.removeEventListener('resize', checkDevice);
});
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container" :class="{ 'is-mobile': isMobile }">
          <div class="modal-header">
            <h2 class="modal-title">需要官方验证</h2>
            <button class="modal-close" type="button" aria-label="关闭" @click="handleClose"><IconClose /></button>
          </div>
          <div class="modal-body">
            <div class="captcha-alert">
              <span class="alert-icon"><IconAlert /></span>
              <span class="alert-text">检测到账号需要官方验证，请通过扫码更新登录态</span>
            </div>
            <div class="captcha-steps">
              <div v-for="(step, index) in steps" :key="step" class="step-item">
                <span class="step-number">{{ index + 1 }}</span>
                <span class="step-text">{{ step }}</span>
              </div>
            </div>
            <div class="captcha-tip">
              <span class="tip-icon"><IconInfo /></span>
              <span class="tip-text">服务器验证页可能被平台直接作废，本项目改用闲鱼官方扫码流程，不再依赖服务器代拖滑块</span>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="handleClose">取消</button>
            <button class="btn btn-primary" @click="handleConfirm">打开扫码更新</button>
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
  max-height: calc(100vh - 32px);
  background: #ffffff;
  border: 1px solid var(--border-1);
  border-radius: 8px;
  width: 100%;
  max-width: 420px;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.14), 0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-container.is-mobile {
  max-width: calc(100vw - 24px);
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
  color: #172033;
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: var(--radius-xs);
  border: none;
  background: transparent;
  color: #526078;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close svg { width: 15px; height: 15px; }

.modal-close:hover {
  background: var(--bg-hover);
  color: #172033;
}

.modal-body {
  padding: 0 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
  border-top: 1px solid var(--border-1);
}

.captcha-alert {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: var(--warning-50);
  border-radius: var(--radius-sm);
}

.alert-icon {
  display: flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #b45309;
}

.alert-icon svg { width: 17px; height: 17px; }

.alert-text {
  font-size: 13px;
  color: var(--warning-600);
  font-weight: 500;
  line-height: 1.4;
}

.captcha-steps {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #f5f5f7;
  border-radius: var(--radius-sm);
}

.step-number {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #0071e3;
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.step-text {
  font-size: 13px;
  color: #172033;
  font-weight: 500;
  line-height: 1.45;
}

.captcha-tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: rgba(0, 113, 227, 0.06);
  border-radius: var(--radius-sm);
}

.tip-icon {
  display: flex;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  color: #1d4ed8;
}

.tip-icon svg { width: 15px; height: 15px; }

.tip-text {
  font-size: 12px;
  color: #334155;
  line-height: 1.5;
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
  color: #172033;
}

.btn-secondary:hover {
  background: var(--bg-hover);
}

.btn-primary {
  background: #0071e3;
  color: #ffffff;
}

.btn-primary:hover {
  background: #0077ed;
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
