<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { showConfirm } from '@/utils/confirm';
import { toast } from '@/utils/toast';

import { getConnectionStatus, startConnection, stopConnection } from '@/api/websocket';
import { showSuccess, showInfo } from '@/utils';
import ManualUpdateCookieDialog from './ManualUpdateCookieDialog.vue';
import ManualUpdateTokenDialog from './ManualUpdateTokenDialog.vue';
import QRUpdateDialog from './QRUpdateDialog.vue';
import IconCheck from '@/components/icons/IconCheck.vue';
import IconClose from '@/components/icons/IconClose.vue';
import IconEdit from '@/components/icons/IconEdit.vue';
import IconHelp from '@/components/icons/IconHelp.vue';
import IconAlert from '@/components/icons/IconAlert.vue';
import IconCookie from '@/components/icons/IconCookie.vue';
import IconKey from '@/components/icons/IconKey.vue';
import IconInfo from '@/components/icons/IconInfo.vue';
import IconClock from '@/components/icons/IconClock.vue';
import IconStop from '@/components/icons/IconStop.vue';
import IconPlay from '@/components/icons/IconPlay.vue';
import IconQrCode from '@/components/icons/IconQrCode.vue';

interface ConnectionStatus {
  xianyuAccountId: number;
  connected: boolean;
  status: string;
  cookieStatus?: number;
  cookieConfigured?: boolean;
  cookieLength?: number;
  websocketTokenConfigured?: boolean;
  websocketTokenLength?: number;
  tokenExpireTime?: number;
}

interface Props {
  modelValue: boolean;
  accountId: number | null;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'refresh'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const connectionStatus = ref<ConnectionStatus | null>(null);
const statusLoading = ref(false);
const logs = ref<Array<{ time: string; message: string; isError?: boolean }>>([]);
let statusInterval: number | null = null;

// 手动更新Cookie对话框
const showManualUpdateCookieDialog = ref(false);
// 手动更新Token对话框
const showManualUpdateTokenDialog = ref(false);
// 扫码更新对话框
const showQRUpdateDialog = ref(false);

// 响应式检测
const isMobile = ref(false);
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768;
};

// 加载连接状态
const loadConnectionStatus = async (silent = false) => {
  if (!props.accountId) return;
  
  if (!silent) {
    statusLoading.value = true;
  }
  try {
    const response = await getConnectionStatus(props.accountId);
    if (response.code === 0 || response.code === 200) {
      connectionStatus.value = response.data as ConnectionStatus;
      if (!silent) {
        addLog('状态已更新');
      }
    } else {
      throw new Error(response.msg || '获取连接状态失败');
    }
  } catch (error) {
    if (!silent) {
      console.error('加载连接状态失败:', error);
      addLog('加载状态失败: ' + (error as Error).message, true);
    }
  } finally {
    statusLoading.value = false;
  }
};

// 启动连接
const handleStartConnection = async () => {
  if (!props.accountId) return;
  
  statusLoading.value = true;
  addLog('正在启动连接...');
  try {
    const response = await startConnection(props.accountId);
    if (response.code === 0 || response.code === 200) {
      showSuccess('连接启动成功');
      addLog('连接启动成功');
      await loadConnectionStatus();
    } else if (response.code === 1001 && response.data?.needCaptcha) {
      addLog('检测到需要官方验证，请在闲鱼 App 完成验证后扫码更新 Cookie/Token', true);
      showQRUpdateDialog.value = true;
    } else {
      throw new Error(response.msg || '启动连接失败');
    }
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      console.error('启动连接失败:', error);
      addLog('启动连接失败: ' + (error as Error).message, true);
    }
  } finally {
    statusLoading.value = false;
  }
};

// 停止连接
const handleStopConnection = async () => {
  if (!props.accountId) return;
  
  try {
    await showConfirm(
      '断开连接后将无法接收消息和执行自动化流程，确定要断开连接吗？',
      '确认断开连接'
    );
  } catch {
    return;
  }
  
  statusLoading.value = true;
  addLog('正在断开连接...');
  try {
    const response = await stopConnection(props.accountId);
    if (response.code === 0 || response.code === 200) {
      showSuccess('连接已断开');
      addLog('连接已断开');
      await loadConnectionStatus();
    } else {
      throw new Error(response.msg || '断开连接失败');
    }
  } catch (error) {
    console.error('断开连接失败:', error);
    addLog('断开连接失败: ' + (error as Error).message, true);
  } finally {
    statusLoading.value = false;
  }
};

// 刷新状态
const handleRefresh = () => {
  loadConnectionStatus();
  showInfo('状态已刷新');
};

// 添加日志
const addLog = (message: string, isError = false) => {
  const now = new Date();
  const time = now.toLocaleTimeString();
  logs.value.push({ time, message, isError });
  
  if (logs.value.length > 50) {
    logs.value.shift();
  }
};

// 获取Cookie状态文本
const getCookieStatusText = (status?: number) => {
  if (status === undefined || status === null) return '未知';
  const statusMap: Record<number, string> = {
    1: '有效',
    2: '过期',
    3: '失效'
  };
  return statusMap[status] || '未知';
};

// 获取Cookie状态标签类型
const getCookieStatusType = (status?: number) => {
  if (status === undefined || status === null) return 'info';
  const typeMap: Record<number, string> = {
    1: 'success',
    2: 'warning',
    3: 'danger'
  };
  return typeMap[status] || 'info';
};

// 格式化时间戳
const formatTimestamp = (timestamp?: number) => {
  if (!timestamp) return '未设置';
  const date = new Date(timestamp);
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
};

// 判断Token是否过期
const isTokenExpired = (timestamp?: number) => {
  if (!timestamp) return false;
  return Date.now() > timestamp;
};

// 获取Token状态文本
const getTokenStatusText = (timestamp?: number) => {
  if (!timestamp) return '未设置';
  return isTokenExpired(timestamp) ? '已过期' : '有效';
};

// 获取Token状态类型
const getTokenStatusType = (timestamp?: number) => {
  if (!timestamp) return 'info';
  return isTokenExpired(timestamp) ? 'danger' : 'success';
};

// 显示Cookie获取帮助
const showCookieHelp = () => {
  toast.info('请按照以下步骤获取Cookie：1.打开浏览器，访问闲鱼网站并登录 2.按F12打开开发者工具 3.切换到"网络"(Network)标签 4.刷新页面 5.在请求列表中找到任意请求 6.在请求头中找到Cookie字段 7.复制完整的Cookie值');
};

// 显示Token获取帮助
const showTokenHelp = () => {
  toast.info('请按照以下步骤获取WebSocket Token：1.打开浏览器，访问闲鱼IM页面并登录 2.按F12打开开发者工具 3.切换到"网络"(Network)标签 4.在页面中进行任意操作（如点击聊天） 5.在请求列表中找到WebSocket连接请求 6.查看请求参数或响应中的Token信息 7.复制完整的Token值');
};

// Cookie手动更新成功回调
const handleManualUpdateCookieSuccess = async () => {
  addLog('Cookie已手动更新');
  await loadConnectionStatus();
};

// Token手动更新成功回调
const handleManualUpdateTokenSuccess = async () => {
  addLog('Token已手动更新');
  await loadConnectionStatus();
};

// 扫码更新成功回调
const handleQRUpdateSuccess = async () => {
  addLog('Cookie和Token已通过扫码更新');
  await loadConnectionStatus();
};

// 关闭对话框
const handleClose = () => {
  emit('update:modelValue', false);
  if (statusInterval) {
    clearInterval(statusInterval);
    statusInterval = null;
  }
};

// 监听对话框打开
watch(() => props.modelValue, (val) => {
  if (val) {
    loadConnectionStatus();
    
    // 启动定时刷新
    statusInterval = window.setInterval(() => {
      loadConnectionStatus(true);
    }, 5000);
  } else {
    if (statusInterval) {
      clearInterval(statusInterval);
      statusInterval = null;
    }
  }
});

onMounted(() => {
  checkScreenSize();
  window.addEventListener('resize', checkScreenSize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', checkScreenSize);
  if (statusInterval) {
    clearInterval(statusInterval);
  }
});
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container" :class="{ 'is-mobile': isMobile }">
          <div class="modal-header">
            <h2 class="modal-title">连接详情</h2>
            <button type="button" class="modal-close" aria-label="关闭对话框" @click="handleClose">
              <IconClose aria-hidden="true" />
            </button>
          </div>
          <div class="modal-body">
            <div v-if="connectionStatus" class="detail-content">
        <!-- 主标题区域 -->
        <div class="main-card-header">
          <div class="header-left">
            <div class="icon-wrapper-large" :class="connectionStatus.connected ? 'icon-success' : 'icon-danger'">
              <span class="icon-large" aria-hidden="true">
                <IconCheck v-if="connectionStatus.connected" />
                <IconClose v-else />
              </span>
            </div>
            <div class="header-info">
              <h2 class="main-title">连接状态</h2>
              <p class="main-subtitle">账号 ID: {{ connectionStatus.xianyuAccountId }} · {{ connectionStatus.status }}</p>
              <p class="main-note" :class="connectionStatus.connected ? 'note-success' : 'note-danger'">
                {{ connectionStatus.connected ? '已连接到闲鱼服务器' : '当前未连接到闲鱼服务器，无法监听消息以及执行自动化流程' }}
              </p>
            </div>
          </div>
          <div class="header-right">
            <span class="tag" :class="connectionStatus.connected ? 'tag--success' : 'tag--danger'">
              {{ connectionStatus.connected ? '● 已连接' : '● 未连接' }}
            </span>
          </div>
        </div>

        <!-- 详细信息区域 -->
        <div class="details-grid">
          <!-- Cookie 详情 -->
          <div class="detail-section cookie-section">
            <div class="section-header">
              <div class="section-icon" aria-hidden="true"><IconCookie /></div>
              <div class="section-title-group">
                <h3 class="section-title">Cookie 凭证</h3>
                <p class="section-note">用于识别账号，如果过期无法使用任何功能</p>
              </div>
              <span class="tag" :class="getCookieStatusType(connectionStatus.cookieStatus) === 'success' ? 'tag--success' : getCookieStatusType(connectionStatus.cookieStatus) === 'warning' ? 'tag--warning' : getCookieStatusType(connectionStatus.cookieStatus) === 'danger' ? 'tag--danger' : 'tag--info'">
                {{ getCookieStatusText(connectionStatus.cookieStatus) }}
              </span>
            </div>
            <div class="section-body">
              <div class="info-box">
                <div class="info-box-label">Cookie 内容</div>
                <div class="info-box-value cookie-value">
                  {{ connectionStatus.cookieConfigured ? '凭证已安全保存' : '未设置' }}
                </div>
                <div class="info-box-meta" v-if="connectionStatus.cookieConfigured">
                  长度: {{ connectionStatus.cookieLength || 0 }} 字符
                </div>
              </div>
              <div class="section-actions">
                <button
                  class="btn-glass btn-glass--primary"
                  @click="showManualUpdateCookieDialog = true"
                >
                  <IconEdit aria-hidden="true" /> 手动更新
                </button>
                <button
                  class="btn-glass btn-glass--default"
                  @click="showCookieHelp"
                >
                  <IconHelp aria-hidden="true" /> 如何获取？
                </button>
              </div>
            </div>
          </div>

          <!-- Token 详情 -->
          <div class="detail-section token-section">
            <div class="section-header">
              <div class="section-icon" aria-hidden="true"><IconKey /></div>
              <div class="section-title-group">
                <h3 class="section-title">WebSocket Token</h3>
                <p class="section-note">这个是收取消息的凭证，如果异常，可能是账号被锁人机验证，需要隔段时间再试一试</p>
              </div>
              <span class="tag" :class="getTokenStatusType(connectionStatus.tokenExpireTime) === 'success' ? 'tag--success' : getTokenStatusType(connectionStatus.tokenExpireTime) === 'danger' ? 'tag--danger' : 'tag--info'">
                {{ getTokenStatusText(connectionStatus.tokenExpireTime) }}
              </span>
            </div>
            <div class="section-body">
              <div class="info-box">
                <div class="info-box-label"><IconClock aria-hidden="true" /> 过期时间</div>
                <div class="info-box-value time-value">
                  {{ formatTimestamp(connectionStatus.tokenExpireTime) }}
                </div>
              </div>
              <div class="info-box">
                <div class="info-box-label">Token 内容</div>
                <div class="info-box-value token-value">
                  {{ connectionStatus.websocketTokenConfigured ? '凭证已安全保存' : '未设置' }}
                </div>
                <div class="info-box-meta" v-if="connectionStatus.websocketTokenConfigured">
                  长度: {{ connectionStatus.websocketTokenLength || 0 }} 字符
                </div>
              </div>
              <div class="section-actions">
                <button
                  class="btn-glass btn-glass--primary"
                  @click="showManualUpdateTokenDialog = true"
                >
                  <IconEdit aria-hidden="true" /> 手动更新
                </button>
                <button
                  class="btn-glass btn-glass--default"
                  @click="showTokenHelp"
                >
                  <IconHelp aria-hidden="true" /> 如何获取？
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 操作区域 -->
        <div class="main-actions">
          <div class="action-wrapper">
            <div class="action-buttons">
              <button
                v-if="connectionStatus.connected"
                class="btn-glass btn-glass--danger"
                @click="handleStopConnection"
              >
                <IconStop aria-hidden="true" /> 断开连接
              </button>
              <button
                v-else
                class="btn-glass btn-glass--success"
                @click="handleStartConnection"
              >
                <IconPlay aria-hidden="true" /> 启动连接
              </button>
              <button
                class="btn-glass btn-glass--primary"
                @click="showQRUpdateDialog = true"
              >
                <IconQrCode aria-hidden="true" /> 扫码更新
              </button>
            </div>
            <div class="action-tip">
              <IconAlert aria-hidden="true" /> 请勿频繁启用连接和断开连接，否则容易触发滑动窗口人机校验，导致账号暂时不可用
            </div>
            <div class="action-tip qr-update-tip">
              <IconInfo aria-hidden="true" /> 扫码更新：通过扫码登录完成更新Cookie与Token
            </div>
          </div>
        </div>

        <!-- 操作日志 -->
        <div class="logs-section">
          <div class="logs-header">操作日志</div>
          <div class="logs-container">
            <div
              v-for="(log, index) in logs"
              :key="index"
              class="log-entry"
              :class="{ 'log-error': log.isError }"
            >
              <span class="log-time">[{{ log.time }}]</span>
              <span class="log-message">{{ log.message }}</span>
            </div>
            <div v-if="logs.length === 0" class="log-empty">
              暂无日志记录
            </div>
          </div>
        </div>
      </div>
    </div>

      <div class="modal-footer">
        <button type="button" class="btn-glass btn-glass--default" @click="handleClose">关闭</button>
        <button type="button" class="btn-glass btn-glass--primary" @click="handleRefresh">刷新状态</button>
      </div>
    </div>

    <!-- 手动更新Cookie对话框 -->
    <ManualUpdateCookieDialog
      v-if="connectionStatus"
      v-model="showManualUpdateCookieDialog"
      :account-id="accountId || 0"
      current-cookie=""
      @success="handleManualUpdateCookieSuccess"
    />

    <!-- 手动更新Token对话框 -->
    <ManualUpdateTokenDialog
      v-if="connectionStatus"
      v-model="showManualUpdateTokenDialog"
      :account-id="accountId || 0"
      current-token=""
      @success="handleManualUpdateTokenSuccess"
    />

    <!-- 扫码更新对话框 -->
    <QRUpdateDialog
      v-model="showQRUpdateDialog"
      :account-id="accountId || 0"
      @success="handleQRUpdateSuccess"
    />

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
  max-width: 680px;
  max-height: 88vh;
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-container.is-mobile {
  max-width: 94vw;
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
  color: var(--text-3);
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

.modal-close:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.modal-body {
  flex: 1;
  padding: 0 20px 20px;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border-1);
  background: var(--bg-elevated);
  flex-shrink: 0;
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


.detail-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 主标题区域 */
.main-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px 20px;
  background: var(--bg-muted);
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-1);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
  min-width: 0;
}

.icon-wrapper-large {
  width: 50px;
  height: 50px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
}

.icon-success {
  background: var(--success-600);
}

.icon-danger {
  background: var(--danger-500);
}

.icon-large {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.icon-large :deep(svg) {
  width: 26px;
  height: 26px;
}

.header-info {
  flex: 1;
  min-width: 0;
}

.main-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
  margin: 0 0 6px 0;
  letter-spacing: 0;
}

.main-subtitle {
  font-size: 13px;
  color: var(--text-3);
  margin: 0 0 4px 0;
  font-weight: 500;
}

.main-note {
  font-size: 12px;
  margin: 0;
  font-weight: 500;
  padding: 5px 10px;
  border-radius: var(--radius-xs);
  display: inline-block;
  margin-top: 4px;
}

.note-danger {
  color: var(--danger-600);
  background: var(--danger-50);
  border: 1px solid var(--border-1);
}

.note-success {
  color: var(--success-600);
  background: var(--success-50);
  border: 1px solid var(--border-1);
}

.header-right {
  display: flex;
  align-items: center;
}


/* 详细信息网格 */
.details-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}

.detail-section {
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-1);
  padding: 16px;
  box-shadow: var(--shadow-xs);
  transition: all 0.3s ease;
}

.detail-section:hover {
  box-shadow: var(--shadow-sm);
}

.cookie-section {
  border-color: var(--warning-500);
}

.token-section {
  border-color: var(--success-600);
}

.section-header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-1);
}

.section-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  color: var(--text-2);
}

.section-icon :deep(svg) {
  width: 24px;
  height: 24px;
}

.section-title-group {
  flex: 1;
  min-width: 0;
}

.section-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
  margin: 0 0 6px 0;
}

.section-note {
  font-size: 12px;
  color: var(--text-3);
  margin: 0;
  line-height: 1.5;
}

.section-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-box {
  background: var(--bg-muted);
  padding: 12px;
  border-radius: var(--radius-xs);
  border: 1px solid var(--border-1);
}

.info-box-label {
  font-size: 11px;
  color: var(--text-2);
  font-weight: 600;
  margin-bottom: 8px;
}

.info-box-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.info-box-label :deep(svg) {
  width: 13px;
  height: 13px;
  color: var(--text-3);
}

.info-box-value {
  font-family: 'Courier New', Consolas, monospace;
  font-size: 10px;
  color: var(--text-1);
  line-height: 1.6;
  word-break: break-all;
  background: var(--bg-elevated);
  padding: 10px;
  border-radius: var(--radius-xs);
  border: 1px solid var(--border-1);
  max-height: 100px;
  overflow-y: auto;
}

.cookie-value,
.token-value {
  font-size: 10px;
}

.time-value {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-1);
}

.info-box-meta {
  font-size: 10px;
  color: var(--text-3);
  margin-top: 4px;
  text-align: right;
}

.section-actions {
  display: flex;
  gap: 6px;
  margin-top: 2px;
}




/* 主操作区域 */
.main-actions {
  padding: 16px 20px;
  background: var(--bg-muted);
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-1);
  display: flex;
  justify-content: center;
}

.action-wrapper {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.action-buttons {
  display: flex;
  gap: 12px;
  width: 100%;
  justify-content: center;
  flex-wrap: wrap;
}


.action-tip {
  font-size: 11px;
  color: var(--text-3);
  text-align: center;
  line-height: 1.6;
  max-width: 90%;
  padding: 0 8px;
}

.action-tip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.action-tip :deep(svg) {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
}

.qr-update-tip {
  color: var(--text-brand);
  font-weight: 500;
}





.logs-section {
  margin-top: 20px;
  padding-bottom: 8px;
}

.logs-header {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 10px;
}

.logs-container {
  background: #2c3e50;
  color: #ecf0f1;
  border-radius: 8px;
  padding: 12px;
  font-family: 'Courier New', Consolas, monospace;
  font-size: 12px;
  max-height: 180px;
  overflow-y: auto;
}

.log-entry {
  margin-bottom: 6px;
  line-height: 1.5;
}

.log-entry:last-child {
  margin-bottom: 0;
}

.log-time {
  color: #95a5a6;
  margin-right: 6px;
  font-size: 11px;
}

.log-message {
  color: #ecf0f1;
}

.log-entry.log-error .log-message {
  color: #e74c3c;
}

.log-empty {
  text-align: center;
  color: #95a5a6;
  padding: 16px;
  font-size: 12px;
}

/* 隐藏滚动条但保留滚动功能 */
.info-box-value::-webkit-scrollbar,
.logs-container::-webkit-scrollbar {
  width: 0;
  height: 0;
}

.info-box-value,
.logs-container {
  -ms-overflow-style: none;
  scrollbar-width: none;
}

/* 响应式优化 */
@media (max-width: 768px) {
  .main-card-header {
    padding: 14px 16px;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .header-left {
    width: 100%;
  }

  .header-right {
    width: 100%;
  }

  .details-grid {
    gap: 12px;
  }

  .detail-section {
    padding: 14px;
  }

  .main-actions {
    padding: 14px 16px;
  }

  .action-buttons {
    flex-direction: row;
    gap: 10px;
  }


  .logs-container {
    max-height: 150px;
  }
}

@media (max-width: 480px) {
  .main-card-header {
    padding: 12px 14px;
  }

  .main-title {
    font-size: 16px;
  }

  .section-title {
    font-size: 14px;
  }

  .action-buttons {
    gap: 8px;
  }
}

.btn-glass { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 8px 16px; border-radius: var(--radius-pill); font-size: 13px; font-weight: 600; cursor: pointer; transition: opacity .15s, transform .12s; border: 1px solid transparent; font-family: inherit; user-select: none; white-space: nowrap; }
.btn-glass:active { opacity: .80; transform: scale(.96); }
.btn-glass--primary { background: var(--brand-600); color: #fff; box-shadow: var(--shadow-sm); }
.btn-glass--primary:hover { background: var(--brand-700); }
.btn-glass--default { background: var(--bg-muted); color: var(--text-2); border-color: var(--border-1); }
.btn-glass--default:hover { background: var(--bg-hover); color: var(--text-1); }
.btn-glass--success { background: var(--success-600); color: #fff; box-shadow: var(--shadow-sm); }
.btn-glass--success:hover { background: var(--success-500); }
.btn-glass--warning { background: var(--warning-600); color: #fff; }
.btn-glass--danger { color: var(--danger-600); background: var(--danger-50); border-color: color-mix(in srgb, var(--danger-500) 25%, transparent); }
.btn-glass--danger:hover { background: var(--danger-500); color: #fff; }
.btn-glass :deep(svg) { width: 15px; height: 15px; flex-shrink: 0; }
.tag { display: inline-flex; align-items: center; padding: 2px 10px; border-radius: var(--radius-pill); font-size: 12px; font-weight: 500; }
.tag--success { background: var(--success-50); color: var(--success-600); }
.tag--warning { background: var(--warning-50); color: var(--warning-600); }
.tag--info { background: var(--bg-hover); color: var(--text-3); }
.tag--danger { background: var(--danger-50); color: var(--danger-600); }
</style>
