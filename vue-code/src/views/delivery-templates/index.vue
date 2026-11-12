<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import UiButton from '@/components/ui/UiButton.vue'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import { accountDisplayName } from '@/utils'
import { getAccountList } from '@/api/account'
import {
  deleteDeliveryTemplate,
  listDeliveryTemplates,
  saveDeliveryTemplate,
  type DeliveryTemplate,
  type DeliveryTemplateMessage
} from '@/api/delivery-template'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'

const accounts = ref<Account[]>([])
const selectedAccountId = ref<number | null>(null)
const templates = ref<DeliveryTemplate[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const activeMessageIndex = ref(0)

const form = ref<DeliveryTemplate>(createTemplate())

const variables = [
  '{{order_id}}',
  '{{item_id}}',
  '{{item_title}}',
  '{{buyer_id}}',
  '{{buyer_nick}}',
  '{{card_name}}'
]

function createMessage(content = ''): DeliveryTemplateMessage {
  return { sortOrder: 1, messageType: 'TEXT', content }
}

function createTemplate(): DeliveryTemplate {
  return {
    xianyuAccountId: selectedAccountId.value || 0,
    name: '',
    enabled: 1,
    messages: [createMessage()]
  }
}

const selectedTemplate = computed(() =>
  templates.value.find(template => template.id === selectedId.value) || null
)

function normalizeMessages(messages: DeliveryTemplateMessage[]) {
  return messages.map((message, index) => ({
    ...message,
    sortOrder: index + 1,
    messageType: 'TEXT' as const,
    content: message.content || ''
  }))
}

function selectTemplate(template: DeliveryTemplate) {
  selectedId.value = template.id || null
  activeMessageIndex.value = 0
  form.value = {
    ...template,
    xianyuAccountId: selectedAccountId.value || template.xianyuAccountId,
    messages: normalizeMessages(template.messages || [])
  }
}

function startNew() {
  selectedId.value = null
  activeMessageIndex.value = 0
  form.value = createTemplate()
}

function addMessage() {
  if (form.value.messages.length >= 20) {
    toast.warning('一个模板最多20段消息')
    return
  }
  form.value.messages.push({ sortOrder: form.value.messages.length + 1, messageType: 'TEXT', content: '' })
  activeMessageIndex.value = form.value.messages.length - 1
}

function removeMessage(index: number) {
  if (form.value.messages.length <= 1) {
    toast.warning('模板至少保留一段消息')
    return
  }
  form.value.messages.splice(index, 1)
  form.value.messages = normalizeMessages(form.value.messages)
  activeMessageIndex.value = Math.min(activeMessageIndex.value, form.value.messages.length - 1)
}

function moveMessage(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= form.value.messages.length) return
  const [message] = form.value.messages.splice(index, 1)
  if (message) form.value.messages.splice(target, 0, message)
  form.value.messages = normalizeMessages(form.value.messages)
  if (activeMessageIndex.value === index) activeMessageIndex.value = target
  else if (activeMessageIndex.value === target) activeMessageIndex.value = index
}

function insertVariable(index: number, variable: string) {
  const message = form.value.messages[index]
  if (!message) return
  message.content += variable
}

async function loadTemplates() {
  if (!selectedAccountId.value) {
    templates.value = []
    startNew()
    return
  }
  loading.value = true
  try {
    const response = await listDeliveryTemplates(selectedAccountId.value)
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '加载发货模板失败')
    templates.value = response.data || []
    const first = templates.value[0]
    if (first) selectTemplate(first)
    else startNew()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '加载发货模板失败')
  } finally {
    loading.value = false
  }
}

async function loadAccounts() {
  try {
    const response = await getAccountList()
    accounts.value = response.data?.accounts || []
    selectedAccountId.value = accounts.value[0]?.id || null
    form.value.xianyuAccountId = selectedAccountId.value || 0
    await loadTemplates()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '加载账号失败')
  }
}

async function save() {
  if (!selectedAccountId.value) return toast.warning('请先选择账号')
  if (!form.value.name.trim()) return toast.warning('请输入模板名称')
  if (form.value.messages.some(message => !message.content.trim())) return toast.warning('每段消息都不能为空')
  saving.value = true
  try {
    const response = await saveDeliveryTemplate({
      ...form.value,
      id: selectedId.value || undefined,
      xianyuAccountId: selectedAccountId.value,
      name: form.value.name.trim(),
      messages: normalizeMessages(form.value.messages).map(message => ({
        ...message,
        content: message.content.trim()
      }))
    })
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '保存发货模板失败')
    toast.success('发货模板已保存')
    await loadTemplates()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '保存发货模板失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!selectedId.value || !selectedAccountId.value) return
  if (!window.confirm('删除后无法恢复，确定删除这个发货模板吗？')) return
  try {
    const response = await deleteDeliveryTemplate(selectedId.value, selectedAccountId.value)
    if (response.code !== 0 && response.code !== 200) throw new Error(response.msg || '删除发货模板失败')
    toast.success('发货模板已删除')
    await loadTemplates()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '删除发货模板失败')
  }
}

async function changeAccount() {
  form.value.xianyuAccountId = selectedAccountId.value || 0
  await loadTemplates()
}

onMounted(loadAccounts)
</script>

<template>
  <UiPageShell title="发货模板" subtitle="把一笔订单拆成有顺序的多段消息，并在发货时自动填充订单与买家变量。">
    <template #actions>
      <label class="account-picker">
        <span>账号</span>
        <select v-model="selectedAccountId" @change="changeAccount">
          <option :value="null" disabled>选择账号</option>
          <option v-for="account in accounts" :key="account.id" :value="account.id">
            {{ accountDisplayName(account) }}
          </option>
        </select>
      </label>
      <UiButton variant="secondary" @click="startNew">新建模板</UiButton>
    </template>

    <div class="template-layout">
      <aside class="template-list" aria-label="发货模板列表">
        <div class="list-heading">
          <span>模板</span>
          <span class="list-count">{{ templates.length }}</span>
        </div>
        <div v-if="loading" class="empty-state">正在加载…</div>
        <button
          v-for="template in templates"
          :key="template.id"
          class="template-item"
          :class="{ 'template-item--active': template.id === selectedId }"
          type="button"
          @click="selectTemplate(template)"
        >
          <span class="template-item__name">{{ template.name }}</span>
          <span class="template-item__meta">{{ template.messages?.length || 0 }} 段消息</span>
        </button>
        <div v-if="!loading && !templates.length" class="empty-state">还没有模板，先新建一个。</div>
      </aside>

      <section class="editor-panel">
        <div class="editor-heading">
          <div>
            <p class="eyebrow">有序消息编排</p>
            <h2>{{ selectedTemplate ? '编辑发货模板' : '新建发货模板' }}</h2>
          </div>
          <div class="editor-actions">
            <UiButton v-if="selectedId" variant="danger" @click="remove">删除</UiButton>
            <UiButton :loading="saving" @click="save">保存模板</UiButton>
          </div>
        </div>

        <div class="field-row">
          <label class="field">
            <span>模板名称</span>
            <input v-model="form.name" maxlength="100" placeholder="例如：数字商品交付" />
          </label>
          <label class="switch-field">
            <input v-model="form.enabled" type="checkbox" :true-value="1" :false-value="0" />
            <span>启用模板</span>
          </label>
        </div>

        <div class="variable-strip">
          <span class="variable-strip__label">可用变量 · 插入第 {{ activeMessageIndex + 1 }} 段</span>
          <button v-for="variable in variables" :key="variable" type="button" @click="insertVariable(activeMessageIndex, variable)">
            {{ variable }}
          </button>
          <small>未知变量会在保存时被拒绝</small>
        </div>

        <div class="message-stack">
          <article v-for="(message, index) in form.messages" :key="message.id || `new-${index}`" class="message-card">
            <div class="message-card__rail">{{ String(index + 1).padStart(2, '0') }}</div>
            <div class="message-card__body">
              <div class="message-card__heading">
                <span>第 {{ index + 1 }} 段 · 文本消息</span>
                <div class="message-card__tools">
                  <button type="button" :disabled="index === 0" @click="moveMessage(index, -1)">上移</button>
                  <button type="button" :disabled="index === form.messages.length - 1" @click="moveMessage(index, 1)">下移</button>
                  <button type="button" class="danger-link" @click="removeMessage(index)">移除</button>
                </div>
              </div>
              <textarea v-model="message.content" rows="4" maxlength="2000" placeholder="这段消息会按顺序单独发送给买家" @focus="activeMessageIndex = index" @click="activeMessageIndex = index" />
              <div class="message-card__footer">
                <div class="quick-vars">
                  <button v-for="variable in variables" :key="variable" type="button" @click="insertVariable(index, variable)">{{ variable }}</button>
                </div>
                <span>{{ message.content.length }} / 2000</span>
              </div>
            </div>
          </article>
        </div>

        <button class="add-message" type="button" @click="addMessage">＋ 添加下一段消息</button>
        <p class="hint">绑定到商品后，系统会按上面的顺序逐段发送。订单号、商品标题、买家昵称等变量由实际订单自动填入。</p>
      </section>
    </div>
  </UiPageShell>
</template>

<style scoped>
.template-layout{display:grid;grid-template-columns:250px minmax(0,1fr);gap:16px;min-height:620px}.template-list,.editor-panel{background:var(--surface-1);border:1px solid var(--border-1);border-radius:var(--radius-md)}.template-list{padding:12px}.list-heading{display:flex;align-items:center;justify-content:space-between;padding:8px 8px 12px;color:var(--text-2);font-size:12px;font-weight:700}.list-count{display:grid;place-items:center;min-width:24px;height:20px;border-radius:999px;background:var(--brand-50);color:var(--brand-700);font-size:11px}.template-item{display:flex;width:100%;flex-direction:column;gap:4px;padding:12px 10px;border:1px solid transparent;border-radius:10px;background:transparent;color:var(--text-1);text-align:left;cursor:pointer}.template-item:hover{background:var(--surface-2)}.template-item--active{border-color:color-mix(in srgb,var(--brand-500) 40%,var(--border-1));background:color-mix(in srgb,var(--brand-50) 65%,var(--surface-1))}.template-item__name{font-size:14px;font-weight:700}.template-item__meta{color:var(--text-3);font-size:12px}.empty-state{padding:28px 10px;color:var(--text-3);font-size:13px;line-height:1.5}.editor-panel{padding:24px}.editor-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding-bottom:20px;border-bottom:1px solid var(--border-1)}.eyebrow{margin:0 0 6px;color:var(--brand-600);font-size:11px;font-weight:750;letter-spacing:.08em}.editor-heading h2{margin:0;color:var(--text-1);font-size:20px}.editor-actions{display:flex;gap:8px}.field-row{display:flex;align-items:flex-end;gap:18px;padding:20px 0 16px}.field{display:flex;max-width:440px;flex:1;flex-direction:column;gap:7px;color:var(--text-2);font-size:13px}.field input,.message-card textarea,.account-picker select{width:100%;padding:10px 12px;border:1px solid var(--border-1);border-radius:9px;background:var(--surface-2);color:var(--text-1)}.switch-field{display:flex;align-items:center;gap:8px;padding:10px 0;color:var(--text-2);font-size:13px}.variable-strip{display:flex;align-items:center;flex-wrap:wrap;gap:6px;padding:12px;border:1px solid color-mix(in srgb,var(--brand-500) 18%,var(--border-1));border-radius:10px;background:color-mix(in srgb,var(--brand-50) 55%,var(--surface-1))}.variable-strip__label{margin-right:4px;color:var(--text-2);font-size:12px;font-weight:700}.variable-strip button,.quick-vars button{padding:4px 7px;border:1px solid color-mix(in srgb,var(--brand-500) 35%,var(--border-1));border-radius:6px;background:var(--surface-1);color:var(--brand-700);font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:11px;cursor:pointer}.variable-strip small{color:var(--text-3);font-size:11px}.message-stack{display:flex;flex-direction:column;gap:12px;margin-top:18px}.message-card{display:grid;grid-template-columns:54px minmax(0,1fr);overflow:hidden;border:1px solid var(--border-1);border-radius:12px;background:var(--surface-2)}.message-card__rail{display:grid;place-items:start center;padding-top:18px;background:color-mix(in srgb,var(--brand-500) 12%,var(--surface-1));color:var(--brand-700);font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:13px;font-weight:800}.message-card__body{padding:14px}.message-card__heading,.message-card__footer{display:flex;align-items:center;justify-content:space-between;gap:10px}.message-card__heading{margin-bottom:10px;color:var(--text-2);font-size:12px;font-weight:700}.message-card__tools{display:flex;gap:8px}.message-card__tools button{border:0;background:none;color:var(--brand-600);font-size:12px;cursor:pointer}.message-card__tools button:disabled{color:var(--text-3);cursor:not-allowed}.message-card__tools .danger-link{color:var(--danger-600)}.message-card textarea{display:block;resize:vertical;line-height:1.6}.message-card__footer{margin-top:8px;color:var(--text-3);font-size:11px}.quick-vars{display:flex;flex-wrap:wrap;gap:4px}.add-message{width:100%;margin-top:14px;padding:11px;border:1px dashed color-mix(in srgb,var(--brand-500) 48%,var(--border-1));border-radius:10px;background:transparent;color:var(--brand-600);font-size:13px;cursor:pointer}.add-message:hover{background:var(--brand-50)}.hint{margin:14px 0 0;color:var(--text-3);font-size:12px;line-height:1.6}.account-picker{display:flex;align-items:center;gap:8px;color:var(--text-2);font-size:12px}.account-picker select{width:auto;min-width:150px;padding:8px 28px 8px 10px}@media(max-width:900px){.template-layout{grid-template-columns:1fr}.template-list{max-height:240px;overflow:auto}.editor-panel{padding:16px}}@media(max-width:600px){.editor-heading,.field-row{flex-direction:column;align-items:stretch}.editor-actions{justify-content:flex-end}.message-card{grid-template-columns:42px minmax(0,1fr)}.message-card__body{padding:11px}.account-picker{width:100%;justify-content:space-between}.account-picker select{flex:1}}
</style>
