<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAccountList } from '@/api/account'
import { accountDisplayName } from '@/utils'
import {
  createScheduleTask, deleteScheduleTask, getScheduleTasks, toggleScheduleTask,
  triggerScheduleTask, updateScheduleTask, type ScheduleTask, type ScheduleTaskReq
} from '@/api/schedule-tasks'
import type { Account } from '@/types'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import { formatTime } from '@/utils'

const tasks = ref<ScheduleTask[]>([])
const accounts = ref<Account[]>([])
const loading = ref(true)
const dialogOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = ref({
  taskName: '', taskType: 'PUBLISH', cronExpr: '0 0 9 * * *', accountId: null as number | null,
  title: '', description: '', price: '', quantity: '1', postFee: '0', categoryId: '', images: '', itemIds: '', enabled: true
})

const typeLabel: Record<string, string> = { SYNC_GOODS: '同步商品', PUBLISH: '定时发布', OFF_SHELF: '定时下架', POLISH_GOODS: '自动擦亮' }
const currentType = computed(() => form.value.taskType)

async function load() {
  loading.value = true
  try {
    const [taskRes, accountRes] = await Promise.all([getScheduleTasks(), getAccountList()])
    if (taskRes.code === 200) tasks.value = taskRes.data || []
    if (accountRes.code === 200) {
      accounts.value = accountRes.data?.accounts || []
      if (!form.value.accountId && accounts.value.length) form.value.accountId = accounts.value[0].id
    }
  } catch (e) { toast.error(e instanceof Error ? e.message : '加载计划任务失败') }
  finally { loading.value = false }
}

function resetForm(task?: ScheduleTask) {
  editingId.value = task?.id || null
  let cfg: Record<string, string | string[] | null> = {}
  try { cfg = task?.configJson ? JSON.parse(task.configJson) : {} } catch { cfg = {} }
  form.value = {
    taskName: task?.taskName || '', taskType: task?.taskType || 'PUBLISH', cronExpr: task?.cronExpr || '0 0 9 * * *',
    accountId: task?.accountId || (accounts.value[0]?.id ?? null), title: (cfg.title as string | undefined) || '', description: (cfg.description as string | undefined) || '',
    price: cfg.price == null ? '' : String(cfg.price), quantity: cfg.quantity == null ? '1' : String(cfg.quantity),
    postFee: cfg.postFee == null ? '0' : String(cfg.postFee), categoryId: (cfg.categoryId as string | undefined) || '',
    images: Array.isArray(cfg.images) ? cfg.images.join(',') : '', itemIds: Array.isArray(cfg.itemIds) ? cfg.itemIds.join(',') : '',
    enabled: task ? task.enabled === 1 : true
  }
  dialogOpen.value = true
}

function buildConfig() {
  if (currentType.value === 'OFF_SHELF' || currentType.value === 'POLISH_GOODS') return JSON.stringify({ itemIds: form.value.itemIds.split(/[,\n]/).map(v => v.trim()).filter(Boolean) })
  if (currentType.value === 'PUBLISH') return JSON.stringify({
    title: form.value.title.trim(), description: form.value.description.trim(), price: Number(form.value.price),
    quantity: Math.max(1, Number(form.value.quantity) || 1), postFee: Number(form.value.postFee) || 0,
    categoryId: form.value.categoryId.trim() || undefined,
    images: form.value.images.split(/[,\n]/).map(v => v.trim()).filter(Boolean)
  })
  return '{}'
}

async function save() {
  if (!form.value.taskName.trim() || !form.value.cronExpr.trim()) return toast.warning('请填写任务名称和 Cron 表达式')
  if (!form.value.accountId) return toast.warning('请选择账号')
  if (currentType.value === 'PUBLISH' && (!form.value.title.trim() || !form.value.price)) return toast.warning('定时发布需要标题和价格')
  if (currentType.value === 'OFF_SHELF' && !form.value.itemIds.trim()) return toast.warning('请填写至少一个商品 ID')
  const req: ScheduleTaskReq = { taskName: form.value.taskName.trim(), taskType: form.value.taskType, cronExpr: form.value.cronExpr.trim(), accountId: form.value.accountId, configJson: buildConfig(), enabled: form.value.enabled }
  saving.value = true
  try {
    if (editingId.value) await updateScheduleTask(editingId.value, req)
    else await createScheduleTask(req)
    toast.success(editingId.value ? '计划任务已更新' : '计划任务已创建'); dialogOpen.value = false; await load()
  } catch (e) { toast.error(e instanceof Error ? e.message : '保存失败') }
  finally { saving.value = false }
}

async function toggle(task: ScheduleTask) { try { await toggleScheduleTask(task.id, task.enabled !== 1); toast.success(task.enabled === 1 ? '已停用' : '已启用'); await load() } catch (e) { toast.error(e instanceof Error ? e.message : '操作失败') } }
async function trigger(task: ScheduleTask) { await showConfirm('立即执行该计划任务？'); try { await triggerScheduleTask(task.id); toast.success('已触发执行'); await load() } catch (e) { toast.error(e instanceof Error ? e.message : '执行失败') } }
async function remove(task: ScheduleTask) { await showConfirm(`确定删除“${task.taskName}”？`); try { await deleteScheduleTask(task.id); toast.success('已删除'); await load() } catch (e) { toast.error(e instanceof Error ? e.message : '删除失败') } }

onMounted(load)
</script>

<template>
  <UiPageShell title="计划任务" subtitle="用 Cron 自动同步、发布或下架商品">
    <template #actions><UiButton @click="resetForm()">新建计划</UiButton></template>
    <div v-if="loading" class="state">加载中...</div>
    <div v-else-if="tasks.length === 0" class="state">暂无计划任务，先创建一个吧</div>
    <div v-else class="task-list">
      <div v-for="task in tasks" :key="task.id" class="task-row">
        <div class="task-main"><strong>{{ task.taskName }}</strong><span class="type">{{ typeLabel[task.taskType] || task.taskType }}</span><code>{{ task.cronExpr }}</code><span v-if="task.accountId">账号 #{{ task.accountId }}</span></div>
        <div class="task-status" :class="{ paused: task.paused === 1, off: task.enabled !== 1 }">{{ task.paused === 1 ? '失败暂停' : task.enabled === 1 ? '运行中' : '已停用' }}</div>
        <div class="task-meta"><span v-if="task.lastRunTime">上次 {{ formatTime(task.lastRunTime) }} · {{ task.lastRunStatus === 'SUCCESS' ? '成功' : '失败' }}</span><span v-if="task.lastError" class="error">{{ task.lastError }}</span></div>
        <div class="task-actions"><UiButton size="small" variant="secondary" @click="resetForm(task)">编辑</UiButton><UiButton size="small" variant="secondary" @click="toggle(task)">{{ task.enabled === 1 ? '停用' : '启用' }}</UiButton><UiButton size="small" @click="trigger(task)">立即执行</UiButton><UiButton size="small" variant="danger" @click="remove(task)">删除</UiButton></div>
      </div>
    </div>

    <Teleport to="body"><div v-if="dialogOpen" class="overlay" @click.self="dialogOpen = false"><div class="dialog"><h2>{{ editingId ? '编辑计划任务' : '新建计划任务' }}</h2><div class="form-grid">
	      <label>任务名称<input v-model="form.taskName" placeholder="例如：每天定时擦亮" /></label>
	      <label>任务类型<select v-model="form.taskType"><option value="PUBLISH">定时发布</option><option value="OFF_SHELF">定时下架</option><option value="SYNC_GOODS">同步商品</option><option value="POLISH_GOODS">自动擦亮</option></select></label>
	      <label>Cron 表达式<input v-model="form.cronExpr" placeholder="秒 分 时 日 月 周" /><small>示例：0 0 9 * * *（每天 09:00）</small></label>
	      <label>账号<select v-model.number="form.accountId"><option :value="null">请选择账号</option><option v-for="a in accounts" :key="a.id" :value="a.id">{{ accountDisplayName(a) }}</option></select></label>
	      <template v-if="currentType === 'PUBLISH'"><label>商品标题<input v-model="form.title" /></label><label>价格<input v-model="form.price" type="number" min="0" step="0.01" /></label><label>数量<input v-model="form.quantity" type="number" min="1" /></label><label>运费<input v-model="form.postFee" type="number" min="0" step="0.01" /></label><label>类目 ID<input v-model="form.categoryId" /></label><label>图片 URL（逗号分隔）<input v-model="form.images" /></label><label class="wide">商品描述<textarea v-model="form.description" rows="3" /></label></template>
	      <label v-else-if="currentType === 'OFF_SHELF'" class="wide">商品 ID（逗号或换行分隔）<textarea v-model="form.itemIds" rows="3" placeholder="itemId-1,itemId-2" /></label>
	      <label v-else-if="currentType === 'POLISH_GOODS'" class="wide">指定商品 ID（可选，逗号或换行分隔，留空则擦亮账号下所有在售商品）<textarea v-model="form.itemIds" rows="3" placeholder="留空默认擦亮账号下全部在售宝贝" /></label>
	      <p v-else class="wide hint">同步商品会按账号刷新最近商品，无需额外配置。</p>
      <label class="check wide"><input v-model="form.enabled" type="checkbox" /> 创建后立即启用</label>
    </div><div class="dialog-actions"><UiButton variant="secondary" @click="dialogOpen = false">取消</UiButton><UiButton :loading="saving" @click="save">保存</UiButton></div></div></div></Teleport>
  </UiPageShell>
</template>

<style scoped>
.state { padding: 48px 20px; text-align: center; color: var(--text-3); }
.task-list { display: flex; flex-direction: column; gap: 12px; }
.task-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 16px; padding: 16px; border: 1px solid var(--glass-border); border-radius: var(--radius-md); background: var(--glass-bg); }
.task-main { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; min-width: 0; color: var(--text-2); font-size: 13px; }.task-main strong { color: var(--text-1); font-size: 15px; }.task-main code { padding: 2px 6px; border-radius: 4px; background: var(--glass-bg-soft); }.type { color: var(--brand-700); }
.task-status { align-self: start; color: var(--success-600); font-size: 13px; }.task-status.paused,.error { color: var(--danger-600); }.task-status.off { color: var(--text-3); }.task-meta { grid-column: 1 / -1; color: var(--text-3); font-size: 12px; }.task-actions { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 8px; }
.overlay { position: fixed; inset: 0; z-index: var(--z-modal); display: grid; place-items: center; padding: 16px; background: rgba(15,23,42,.38); }.dialog { width: min(720px,100%); max-height: 90vh; overflow: auto; padding: 20px; border: 1px solid var(--glass-border-strong); border-radius: var(--radius-md); background: var(--glass-bg-strong); box-shadow: var(--glass-shadow-float); }.dialog h2 { margin: 0 0 16px; font-size: 20px; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }.form-grid label { display: flex; flex-direction: column; gap: 6px; color: var(--text-2); font-size: 13px; }.form-grid input,.form-grid select,.form-grid textarea { width: 100%; box-sizing: border-box; padding: 9px 10px; border: 1px solid var(--glass-border); border-radius: var(--radius-xs); background: var(--surface-solid); color: var(--text-1); font: inherit; }.form-grid small,.hint { color: var(--text-3); font-size: 11px; }.wide { grid-column: 1 / -1; }.check { flex-direction: row !important; align-items: center; }.check input { width: auto; }.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
@media (max-width: 700px) { .task-row { grid-template-columns: 1fr; }.form-grid { grid-template-columns: 1fr; }.wide { grid-column: auto; } }
</style>
