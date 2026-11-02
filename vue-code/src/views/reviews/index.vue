<script setup lang="ts">
import { onMounted, ref } from 'vue'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'
import { toast } from '@/utils/toast'
import { accountDisplayName } from '@/utils'
import { getAccountList } from '@/api/account'
import { cancelReview, confirmReview, getReviewRecords, getReviewRules, retryReview, saveReviewRule, type ReviewRecord, type ReviewRule } from '@/api/reviews'
import type { Account } from '@/types'

const accounts = ref<Account[]>([])
const rules = ref<ReviewRule[]>([])
const records = ref<ReviewRecord[]>([])
const loading = ref(true)
const saving = ref(false)
const selectedAccountId = ref<number | null>(null)
const form = ref({ enabled: 0, delaySeconds: 86400, template: '感谢您的支持，祝您生活愉快！', requireManualConfirm: 1, maxRetries: 3 })

const statusLabel: Record<string, string> = { WAITING_CONFIRM: '待确认', PENDING: '待执行', MANUAL_REQUIRED: '需人工处理', SUBMITTED: '已提交', CANCELLED: '已取消' }

async function load() {
  loading.value = true
  try {
    const [accountRes, ruleRes, recordRes] = await Promise.all([getAccountList(), getReviewRules(), getReviewRecords({ limit: 200 })])
    accounts.value = accountRes.data?.accounts || []
    rules.value = ruleRes.data || []
    records.value = recordRes.data || []
    if (selectedAccountId.value == null) selectedAccountId.value = accounts.value[0]?.id || null
    applyRule()
  } catch (e) { toast.error(e instanceof Error ? e.message : '加载评价中心失败') }
  finally { loading.value = false }
}
function applyRule() {
  const rule = rules.value.find(r => r.accountId === selectedAccountId.value)
  if (rule) form.value = { enabled: rule.enabled, delaySeconds: rule.delaySeconds, template: rule.template, requireManualConfirm: rule.requireManualConfirm, maxRetries: rule.maxRetries }
}
async function save() {
  if (!selectedAccountId.value) return toast.warning('请先选择账号')
  if (!form.value.template.trim()) return toast.warning('评价模板不能为空')
  saving.value = true
  try { await saveReviewRule({ ...form.value, accountId: selectedAccountId.value }); toast.success('评价规则已保存'); await load() }
  catch (e) { toast.error(e instanceof Error ? e.message : '保存失败') }
  finally { saving.value = false }
}
async function action(id: number, fn: (id: number) => Promise<unknown>, message: string) { try { await fn(id); toast.success(message); await load() } catch (e) { toast.error(e instanceof Error ? e.message : '操作失败') } }
onMounted(load)
</script>

<template>
  <UiPageShell title="自动评价" description="按账号配置评价模板，延迟任务执行前支持人工确认。">
    <section class="toolbar">
      <label>账号 <select v-model="selectedAccountId" @change="applyRule"><option v-for="a in accounts" :key="a.id" :value="a.id">{{ accountDisplayName(a) }}</option></select></label>
    </section>
    <section class="card rule-card">
      <h2>评价规则</h2>
      <div class="form-grid">
        <label>状态 <select v-model.number="form.enabled"><option :value="0">停用</option><option :value="1">启用</option></select></label>
        <label>延迟（秒）<input v-model.number="form.delaySeconds" type="number" min="0" /></label>
        <label>最大重试 <input v-model.number="form.maxRetries" type="number" min="0" max="10" /></label>
        <label>执行方式 <select v-model.number="form.requireManualConfirm"><option :value="1">人工确认后执行</option><option :value="0">自动进入待处理</option></select></label>
      </div>
      <label class="template">评价模板<textarea v-model="form.template" rows="3" maxlength="500" /></label>
      <UiButton :loading="saving" @click="save">保存规则</UiButton>
    </section>
    <section class="card">
      <div class="section-head"><h2>评价记录</h2><span>{{ records.length }} 条</span></div>
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!records.length" class="empty">暂无评价记录</div>
      <div v-else class="table-wrap"><table><thead><tr><th>账号</th><th>订单</th><th>评价内容</th><th>状态</th><th>计划时间</th><th>操作</th></tr></thead><tbody><tr v-for="record in records" :key="record.id"><td>{{ record.accountId }}</td><td>{{ record.orderId }}</td><td>{{ record.reviewText }}</td><td><span class="status">{{ statusLabel[record.status] || record.status }}</span></td><td>{{ record.scheduledAt || '-' }}</td><td class="actions"><UiButton v-if="record.status === 'WAITING_CONFIRM'" size="small" @click="action(record.id, confirmReview, '已确认并排入任务')">确认</UiButton><UiButton v-if="record.status === 'MANUAL_REQUIRED'" size="small" @click="action(record.id, retryReview, '已重新排队')">重试</UiButton><UiButton v-if="!['CANCELLED','SUBMITTED'].includes(record.status)" size="small" variant="ghost" @click="action(record.id, cancelReview, '已取消')">取消</UiButton></td></tr></tbody></table></div>
    </section>
  </UiPageShell>
</template>

<style scoped>
.toolbar,.section-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.card{padding:20px;margin-bottom:16px;background:var(--surface-1);border:1px solid var(--border-1);border-radius:var(--radius-md)}h2{margin:0 0 16px;font-size:18px}.form-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}label{display:flex;flex-direction:column;gap:6px;font-size:13px;color:var(--text-2)}input,select,textarea{padding:9px;border:1px solid var(--border-1);border-radius:var(--radius-sm);background:var(--surface-2);color:var(--text-1)}.template{margin:16px 0}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse}th,td{padding:12px 8px;text-align:left;border-bottom:1px solid var(--border-1);white-space:nowrap}.empty{padding:32px;text-align:center;color:var(--text-3)}.status{color:var(--brand-600)}.actions{display:flex;gap:6px}@media(max-width:768px){.form-grid{grid-template-columns:1fr 1fr}.card{padding:14px}th,td{font-size:12px}}
</style>
