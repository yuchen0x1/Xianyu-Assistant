<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getWebhookList, createWebhook, updateWebhook, deleteWebhook, toggleWebhook, testWebhook, type WebhookConfig } from '@/api/webhooks'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import { formatTime } from '@/utils'
import IconLink from '@/components/icons/IconLink.vue'

defineOptions({ name: 'WebhooksView' })

const loading = ref(true)
const hooks = ref<WebhookConfig[]>([])
const showForm = ref(false)
const editing = ref<Partial<WebhookConfig> | null>(null)
const showTestResult = ref(false)
const testResultOk = ref(false)

const eventOptions = [
  { value: 'delivery.completed', label: '发货完成' },
  { value: 'delivery.failed', label: '发货失败' },
  { value: 'order.created', label: '订单创建' },
  { value: 'connection.lost', label: '连接断开' },
  { value: 'connection.restored', label: '连接恢复' },
  { value: 'alert.triggered', label: '告警触发' },
  { value: 'kami.low_stock', label: '卡密库存不足' }
]

// 解析 events JSON 字符串为选中状态
const selectedEvents = ref<string[]>([])

const parseEvents = (value?: string): string[] => {
  if (!value || value === '[]') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed)
      ? parsed.filter((event): event is string => typeof event === 'string' && event.trim().length > 0)
      : []
  } catch {
    return []
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getWebhookList()
    if (res.code === 200) hooks.value = res.data || []
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editing.value = { name: '', url: '', events: '[]', secret: '', retryCount: 3, timeoutSeconds: 10, enabled: 1 }
  selectedEvents.value = []
  showForm.value = true
}

const openEdit = (hook: WebhookConfig) => {
  editing.value = { ...hook, secret: '' }
  selectedEvents.value = parseEvents(hook.events)
  showForm.value = true
}

const saveHook = async () => {
  if (!editing.value) return
  editing.value.events = JSON.stringify(selectedEvents.value)
  try {
    if (!editing.value.id) {
      await createWebhook(editing.value)
      toast.success('创建成功')
    } else {
      await updateWebhook(editing.value)
      toast.success('更新成功')
    }
    showForm.value = false
    editing.value = null
    await loadData()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '保存失败')
  }
}

const handleDelete = async (hook: WebhookConfig) => {
  await showConfirm('确定删除 Webhook「' + hook.name + '」吗？')
  try {
    await deleteWebhook(hook.id)
    toast.success('已删除')
    await loadData()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '删除失败')
  }
}

const handleToggle = async (hook: WebhookConfig) => {
  try {
    await toggleWebhook(hook.id, hook.enabled !== 1)
    hook.enabled = hook.enabled === 1 ? 0 : 1
    toast.success(hook.enabled === 1 ? '已启用' : '已禁用')
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '操作失败')
  }
}

const handleTest = async (hook: WebhookConfig) => {
  try {
    const res = await testWebhook(hook.url, '', hook.id)
    testResultOk.value = res.data === true
    showTestResult.value = true
  } catch {
    testResultOk.value = false
    showTestResult.value = true
  }
}

const toggleEvent = (event: string) => {
  const idx = selectedEvents.value.indexOf(event)
  if (idx >= 0) selectedEvents.value.splice(idx, 1)
  else selectedEvents.value.push(event)
}

onMounted(loadData)
</script>

<template>
  <UiPageShell title="Webhook 配置">
    <template #actions>
      <UiButton @click="openCreate" variant="primary">新建 Webhook</UiButton>
    </template>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="hooks.length === 0" class="empty">暂无 Webhook 配置</div>

    <div v-else class="hook-list">
      <div v-for="hook in hooks" :key="hook.id" class="hook-card">
        <div class="hook-header">
          <div class="hook-name"><IconLink /><span>{{ hook.name }}</span></div>
          <label class="toggle-switch">
            <input type="checkbox" :checked="hook.enabled === 1" @change="handleToggle(hook)" />
            <span class="toggle-slider"></span>
          </label>
        </div>
        <div class="hook-url">{{ hook.url }}</div>
        <span v-if="hook.hasSecret" class="badge-secret">已配置密钥</span>
        <div class="hook-events">
          <span v-for="ev in parseEvents(hook.events)" :key="ev" class="event-tag">{{ ev }}</span>
          <span v-if="!hook.events || hook.events === '[]'" class="event-tag all">全部事件</span>
        </div>
        <div class="hook-meta">
          <span>重试 {{ hook.retryCount }} 次</span>
          <span>超时 {{ hook.timeoutSeconds }}s</span>
          <span v-if="hook.lastTriggerAt">上次: {{ formatTime(hook.lastTriggerAt) }}</span>
          <span v-if="hook.lastTriggerStatus" :class="hook.lastTriggerStatus === 'SUCCESS' ? 'status-ok' : 'status-fail'">{{ hook.lastTriggerStatus }}</span>
        </div>
        <div v-if="hook.lastError" class="hook-error">{{ hook.lastError }}</div>
        <div class="hook-actions">
          <UiButton size="small" @click="handleTest(hook)">测试</UiButton>
          <UiButton size="small" @click="openEdit(hook)">编辑</UiButton>
          <UiButton size="small" variant="danger" @click="handleDelete(hook)">删除</UiButton>
        </div>
      </div>
    </div>

    <!-- 表单弹窗 -->
    <Teleport to="body">
      <div v-if="showForm" class="dialog-overlay" @click.self="showForm = false">
        <div class="dialog">
          <h3>{{ editing?.id ? '编辑 Webhook' : '新建 Webhook' }}</h3>
          <div class="form-field"><label>名称</label><input v-model="editing!.name" placeholder="如：通知服务器" /></div>
          <div class="form-field"><label>回调 URL</label><input v-model="editing!.url" placeholder="https://example.com/webhook" /></div>
          <div class="form-field"><label>Secret 签名密钥（可选）</label><input v-model="editing!.secret" placeholder="留空则保持原密钥" /></div>
          <div class="form-row">
            <div class="form-field half"><label>重试次数</label><input type="number" v-model.number="editing!.retryCount" /></div>
            <div class="form-field half"><label>超时（秒）</label><input type="number" v-model.number="editing!.timeoutSeconds" /></div>
          </div>
          <div class="form-field"><label>监听事件</label>
            <div class="event-selector">
              <label v-for="ev in eventOptions" :key="ev.value" class="event-checkbox">
                <input type="checkbox" :checked="selectedEvents.includes(ev.value)" @change="toggleEvent(ev.value)" />
                <span>{{ ev.label }}</span>
                <code>{{ ev.value }}</code>
              </label>
            </div>
          </div>
          <div class="dialog-actions"><UiButton @click="showForm = false">取消</UiButton><UiButton @click="saveHook" variant="primary">保存</UiButton></div>
        </div>
      </div>
    </Teleport>

    <!-- 测试结果弹窗 -->
    <Teleport to="body">
      <div v-if="showTestResult" class="dialog-overlay" @click.self="showTestResult = false">
        <div class="dialog dialog-small">
          <h3>测试结果</h3>
          <div :class="testResultOk ? 'test-success' : 'test-fail'">
            {{ testResultOk ? 'Webhook 测试发送成功' : 'Webhook 测试发送失败，请检查 URL 是否正确' }}
          </div>
          <div class="dialog-actions"><UiButton @click="showTestResult = false">关闭</UiButton></div>
        </div>
      </div>
    </Teleport>
  </UiPageShell>
</template>

<style scoped>
.loading,
.empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-base);
}

.hook-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.hook-card {
  position: relative;
  padding: var(--space-4);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  overflow: hidden;
}

.hook-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  pointer-events: none;
  background: var(--glass-highlight);
}

.hook-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.hook-name {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
  color: var(--text-1);
  font-weight: var(--weight-semibold);
  font-size: var(--text-base);
}

.hook-name :deep(svg) {
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  color: var(--brand-600);
}

.hook-url {
  margin-bottom: var(--space-2);
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  word-break: break-all;
}

.hook-events {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--space-2);
}

.event-tag,
.badge-secret {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  border-radius: var(--radius-pill);
}

.event-tag {
  background: var(--brand-50);
  color: var(--brand-700);
}

.event-tag.all {
  background: var(--purple-50);
  color: var(--purple-500);
}

.hook-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  font-size: var(--text-sm);
  color: var(--text-2);
}

.hook-meta .status-ok {
  color: var(--success-600);
}

.hook-meta .status-fail {
  color: var(--danger-500);
}

.hook-error {
  margin-top: var(--space-1);
  color: var(--danger-500);
  font-size: var(--text-sm);
}

.badge-secret {
  margin-bottom: var(--space-2);
  background: var(--brand-50);
  color: var(--brand-700);
}

.hook-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(15, 23, 42, 0.38);
  backdrop-filter: blur(10px) saturate(1.2);
  -webkit-backdrop-filter: blur(10px) saturate(1.2);
}

.dialog {
  width: min(560px, 100%);
  max-height: 84vh;
  overflow-y: auto;
  padding: 20px;
  background: var(--glass-bg-strong);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow-float);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  color: var(--text-1);
}

.dialog h3 {
  margin: 0 0 16px;
  color: var(--text-1);
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  line-height: 1.3;
}

.dialog-small {
  width: min(420px, 100%);
}

.form-field {
  margin-bottom: var(--space-4);
}

.form-field label {
  display: block;
  margin-bottom: 6px;
  color: var(--text-2);
  font-weight: var(--weight-semibold);
  font-size: var(--text-md);
}

.form-field input,
.form-field select {
  width: 100%;
  min-height: 42px;
  padding: 0 12px;
  color: var(--text-1);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-base);
  font-family: inherit;
  outline: none;
  box-sizing: border-box;
}

.form-field input:focus,
.form-field select:focus {
  border-color: var(--brand-600);
  box-shadow: 0 0 0 3px var(--ring-brand);
}

.form-row {
  display: flex;
  gap: var(--space-3);
}

.form-field.half {
  flex: 1;
  min-width: 0;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-5);
}

.event-selector {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  overflow-y: auto;
  padding: var(--space-2);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
}

.event-checkbox {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px 8px;
  border-radius: var(--radius-xs);
  cursor: pointer;
}

.event-checkbox:hover {
  background: var(--glass-bg);
}

.event-checkbox input {
  accent-color: var(--brand-600);
}

.event-checkbox code {
  margin-left: auto;
  color: var(--text-3);
  font-size: var(--text-xs);
}

.toggle-switch {
  position: relative;
  width: 40px;
  height: 22px;
  display: inline-block;
  flex: 0 0 auto;
}

.toggle-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  inset: 0;
  background: var(--border-2);
  border-radius: var(--radius-pill);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.toggle-slider::before {
  content: '';
  position: absolute;
  height: 18px;
  width: 18px;
  left: 2px;
  bottom: 2px;
  background: var(--bg-elevated);
  border-radius: 50%;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.24);
  transition: transform 0.2s ease;
}

.toggle-switch input:checked + .toggle-slider {
  background: var(--success-500);
}

.toggle-switch input:checked + .toggle-slider::before {
  transform: translateX(18px);
}

.test-success,
.test-fail {
  padding: var(--space-6) 0;
  text-align: center;
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
}

.test-success {
  color: var(--success-600);
}

.test-fail {
  color: var(--danger-500);
}

@supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
  .hook-card,
  .dialog,
  .form-field input,
  .form-field select,
  .event-selector {
    background: var(--surface-solid);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .hook-card,
  .dialog,
  .form-field input,
  .form-field select,
  .event-selector {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface-solid);
  }
}

@media (max-width: 767.98px) {
  .form-row {
    flex-direction: column;
  }

  .dialog {
    width: 100%;
  }
}
</style>
