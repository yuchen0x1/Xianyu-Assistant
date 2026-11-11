<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { getAccountList } from '@/api/account'
import {
  recommendCategory,
  publishItem,
  offShelf,
  getPublishHistory,
  type CategoryPredict,
  type PublishHistory
} from '@/api/publish'
import { toast } from '@/utils/toast'
import { accountDisplayName } from '@/utils'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'
import MultiImageUploader from '@/components/MultiImageUploader.vue'
import IconRocket from '@/components/icons/IconRocket.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconTrash from '@/components/icons/IconTrash.vue'
import {
  saveTemplate,
  listTemplates,
  deleteTemplate,
  importTemplateCsv,
  batchPublishTemplates,
  type GoodsTemplate,
  type TemplatePublishResult
} from '@/api/template'
import type { Account } from '@/types'

// ============ 账号 ============
const accounts = ref<Account[]>([])
const selectedAccountId = ref<number | null>(null)
const loadingAccounts = ref(false)
const selectedAccountLabel = computed(() => {
  const acc = accounts.value.find(a => a.id === selectedAccountId.value)
  return acc ? accountDisplayName(acc) : (selectedAccountId.value || '未选择')
})

const loadAccounts = async () => {
  loadingAccounts.value = true
  try {
    const res = await getAccountList()
    if (res.code === 200 && res.data?.accounts) {
      accounts.value = res.data.accounts
      if (accounts.value.length > 0 && selectedAccountId.value === null) {
        selectedAccountId.value = accounts.value[0].id
      }
    }
  } catch (e) {
    toast.error((e as Error).message || '加载账号失败')
  } finally {
    loadingAccounts.value = false
  }
}

// ============ 发布表单 ============
const title = ref('')
const description = ref('')
const price = ref<number | null>(null)
const quantity = ref(1)
const postFee = ref(0)
const imagesStr = ref('')
const categoryId = ref('')
const categoryName = ref('')
const categories = ref<CategoryPredict[]>([])
const recommendLoading = ref(false)
const publishing = ref(false)

const images = computed(() =>
  imagesStr.value
    .split(',')
    .map((s: string) => s.trim())
    .filter((s: string) => s)
)

const handleRecommendCategory = async () => {
  if (!selectedAccountId.value) {
    toast.warning('请先选择账号')
    return
  }
  if (!title.value.trim()) {
    toast.warning('请先填写商品标题')
    return
  }
  recommendLoading.value = true
  try {
    const res = await recommendCategory({
      accountId: selectedAccountId.value,
      title: title.value,
      description: description.value,
      images: images.value
    })
    if (res.code === 200) {
      categories.value = res.data || []
      if (categories.value.length === 0) {
        toast.info('未识别到类目，请手动填写类目ID')
      } else {
        // 默认选中第一个
        selectCategory(categories.value[0])
        toast.success(`识别到 ${categories.value.length} 个候选类目`)
      }
    } else {
      toast.error(res.msg || '类目识别失败')
    }
  } catch (e) {
    toast.error((e as Error).message || '类目识别失败')
  } finally {
    recommendLoading.value = false
  }
}

const selectCategory = (c: CategoryPredict) => {
  categoryId.value = c.categoryId
  categoryName.value = c.categoryName || c.categoryId
}

const handlePublish = async () => {
  if (!selectedAccountId.value) {
    toast.warning('请先选择账号')
    return
  }
  if (!title.value.trim()) {
    toast.warning('请填写商品标题')
    return
  }
  if (price.value === null || price.value <= 0) {
    toast.warning('请填写商品价格')
    return
  }
  if (images.value.length === 0) {
    toast.warning('请至少上传一张商品图片')
    return
  }
  if (!categoryId.value) {
    toast.warning('请先点击「AI 识别类目」或手动填写类目ID')
    return
  }

  publishing.value = true
  try {
    const res = await publishItem({
      accountId: selectedAccountId.value,
      title: title.value.trim(),
      description: description.value,
      price: price.value,
      quantity: quantity.value || 1,
      postFee: postFee.value || 0,
      images: images.value,
      categoryId: categoryId.value
    })
    if (res.code === 200 && res.data?.success) {
      toast.success(res.data.message || '发布成功')
      // 清空表单，保留账号
      title.value = ''
      description.value = ''
      price.value = null
      quantity.value = 1
      postFee.value = 0
      imagesStr.value = ''
      categoryId.value = ''
      categoryName.value = ''
      categories.value = []
      loadHistory()
    } else {
      toast.error(res.data?.message || res.msg || '发布失败')
    }
  } catch (e) {
    toast.error((e as Error).message || '发布失败')
  } finally {
    publishing.value = false
  }
}

// ============ 下架 ============
const offShelfItemId = ref('')
const offShelfing = ref(false)

const handleOffShelf = async () => {
  if (!selectedAccountId.value) {
    toast.warning('请先选择账号')
    return
  }
  const itemId = offShelfItemId.value.trim()
  if (!itemId) {
    toast.warning('请输入要下架的商品ID')
    return
  }
  offShelfing.value = true
  try {
    const res = await offShelf({ accountId: selectedAccountId.value, itemId })
    if (res.code === 200 && res.data) {
      toast.success('下架成功')
      offShelfItemId.value = ''
      loadHistory()
    } else {
      toast.error(res.msg || '下架失败，请查看历史确认原因')
    }
  } catch (e) {
    toast.error((e as Error).message || '下架失败')
  } finally {
    offShelfing.value = false
  }
}

// ============ 历史 ============
const historyList = ref<PublishHistory[]>([])
const historyLoading = ref(false)

const loadHistory = async () => {
  historyLoading.value = true
  try {
    const res = await getPublishHistory(selectedAccountId.value ?? undefined, 50)
    if (res.code === 200) {
      historyList.value = res.data || []
    }
  } catch {
    // 历史加载失败不打扰用户
  } finally {
    historyLoading.value = false
  }
}

const useHistoryItemId = (itemId?: string) => {
  if (itemId) {
    offShelfItemId.value = itemId
  }
}

const formatTime = (t?: string) => {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

const formatPrice = (p?: number) => {
  if (p === undefined || p === null) return '-'
  return `¥${p}`
}

const opTypeText = (op: string) => (op === 'PUBLISH' ? '发布' : '下架')

onMounted(async () => {
  await loadAccounts()
  loadHistory()
  loadTemplates()
})

// ============ 商品模板（F3：模板CRUD + CSV导入 + 批量上架） ============
const templates = ref<GoodsTemplate[]>([])
const templateTotal = ref(0)
const templatePage = ref(1)
const templatePageSize = ref(10)
const templatesLoading = ref(false)
const templateDialogVisible = ref(false)
const templateForm = ref<GoodsTemplate>({ title: '', description: '', pricePlaceholder: '', imageUrls: '', categoryId: '', remark: '' })
const templateSaving = ref(false)
const csvDialogVisible = ref(false)
const csvContent = ref('')
const csvImporting = ref(false)
const selectedTemplateIds = ref<number[]>([])
const batchPublishing = ref(false)
const batchResults = ref<TemplatePublishResult[] | null>(null)

const loadTemplates = async () => {
  templatesLoading.value = true
  try {
    const res = await listTemplates({ pageNum: templatePage.value, pageSize: templatePageSize.value })
    if (res.code === 200) {
      templates.value = res.data?.list || []
      templateTotal.value = res.data?.totalCount || 0
    }
  } catch (e) {
    toast.error((e as Error).message || '加载模板失败')
  } finally {
    templatesLoading.value = false
  }
}

const openTemplateDialog = (t?: GoodsTemplate) => {
  templateForm.value = t
    ? { ...t }
    : { title: '', description: '', pricePlaceholder: '', imageUrls: '', categoryId: '', remark: '' }
  templateDialogVisible.value = true
}

const handleTemplateSave = async () => {
  if (!templateForm.value.title?.trim()) {
    toast.warning('请填写模板标题')
    return
  }
  templateSaving.value = true
  try {
    const res = await saveTemplate(templateForm.value)
    if (res.code === 200) {
      toast.success(templateForm.value.id ? '模板已更新' : '模板已创建')
      templateDialogVisible.value = false
      loadTemplates()
    } else {
      toast.error(res.msg || '保存失败')
    }
  } catch (e) {
    toast.error((e as Error).message || '保存失败')
  } finally {
    templateSaving.value = false
  }
}

const handleTemplateDelete = async (t: GoodsTemplate) => {
  if (!t.id) return
  if (!window.confirm(`确认删除模板「${t.title}」？`)) return
  try {
    const res = await deleteTemplate(t.id)
    if (res.code === 200) {
      toast.success('已删除')
      selectedTemplateIds.value = selectedTemplateIds.value.filter(id => id !== t.id)
      loadTemplates()
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch (e) {
    toast.error((e as Error).message || '删除失败')
  }
}

const toggleTemplateSelect = (t: GoodsTemplate) => {
  if (!t.id) return
  if (selectedTemplateIds.value.includes(t.id)) {
    selectedTemplateIds.value = selectedTemplateIds.value.filter(id => id !== t.id)
  } else {
    selectedTemplateIds.value.push(t.id)
  }
}

const handleImportCsv = async () => {
  if (!csvContent.value.trim()) {
    toast.warning('请粘贴CSV内容')
    return
  }
  csvImporting.value = true
  try {
    const res = await importTemplateCsv(csvContent.value)
    if (res.code === 200) {
      const data = res.data
      toast.success(`导入完成：成功 ${data?.successCount ?? 0} 条，失败 ${data?.failCount ?? 0} 条`)
      if (data?.failures?.length) {
        toast.error(`失败明细：${data.failures.join('；')}`)
      }
      csvDialogVisible.value = false
      csvContent.value = ''
      loadTemplates()
    } else {
      toast.error(res.msg || '导入失败')
    }
  } catch (e) {
    toast.error((e as Error).message || '导入失败')
  } finally {
    csvImporting.value = false
  }
}

const handleBatchPublish = async () => {
  if (!selectedAccountId.value) {
    toast.warning('请先选择账号')
    return
  }
  if (selectedTemplateIds.value.length === 0) {
    toast.warning('请至少勾选一个模板')
    return
  }
  batchPublishing.value = true
  batchResults.value = null
  try {
    const res = await batchPublishTemplates({
      accountId: selectedAccountId.value,
      templateIds: selectedTemplateIds.value
    })
    if (res.code === 200) {
      batchResults.value = res.data || []
      const ok = (res.data || []).filter(r => r.success).length
      toast.success(`批量上架完成：成功 ${ok} 条，失败 ${(res.data || []).length - ok} 条`)
      loadHistory()
    } else {
      toast.error(res.msg || '批量上架失败')
    }
  } catch (e) {
    toast.error((e as Error).message || '批量上架失败')
  } finally {
    batchPublishing.value = false
  }
}

const fillPublishFromTemplate = (t: GoodsTemplate) => {
  title.value = t.title || ''
  description.value = t.description || ''
  const priceText = (t.pricePlaceholder || '').replace(/[¥￥元]/g, '').trim()
  const parsed = parseFloat(priceText)
  price.value = Number.isFinite(parsed) && parsed > 0 ? parsed : null
  imagesStr.value = (t.imageUrls || '').split(/[,;；]/).map(s => s.trim()).filter(s => s).join(',')
  categoryId.value = t.categoryId || ''
  categoryName.value = t.categoryId || ''
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<template>
  <UiPageShell title="商品发布" subtitle="发布 / 下架闲鱼商品（写操作每分钟限 1 次，防止风控）">
    <template #actions>
      <UiButton variant="secondary" @click="loadHistory">
        <template #default>
          <span class="publish__btn-icon"><IconRefresh /></span>
          刷新历史
        </template>
      </UiButton>
    </template>

    <div class="publish">
      <!-- 账号选择 -->
      <section class="publish__card publish__card--account">
        <div class="publish__field">
          <label class="publish__label">选择账号</label>
          <select v-model="selectedAccountId" class="publish__select" :disabled="loadingAccounts">
            <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
              {{ accountDisplayName(acc) }}
            </option>
          </select>
        </div>
      </section>

      <div class="publish__grid">
        <!-- 发布表单 -->
        <section class="publish__card">
          <div class="publish__card-title">
            <span class="publish__card-icon"><IconRocket /></span>
            发布商品
          </div>

          <div class="publish__field">
            <label class="publish__label">商品标题 *</label>
            <input v-model="title" class="publish__input" placeholder="请输入商品标题（建议简洁清晰）" maxlength="60" />
          </div>

          <div class="publish__row">
            <div class="publish__field publish__field--half">
              <label class="publish__label">价格（元）*</label>
              <input v-model.number="price" type="number" min="0" step="0.01" class="publish__input" placeholder="0.00" />
            </div>
            <div class="publish__field publish__field--half">
              <label class="publish__label">数量</label>
              <input v-model.number="quantity" type="number" min="1" step="1" class="publish__input" placeholder="1" />
            </div>
          </div>

          <div class="publish__row">
            <div class="publish__field publish__field--half">
              <label class="publish__label">运费（元）</label>
              <input v-model.number="postFee" type="number" min="0" step="0.01" class="publish__input" placeholder="0" />
            </div>
            <div class="publish__field publish__field--half">
              <label class="publish__label">类目</label>
              <div class="publish__category">
                <input v-model="categoryId" class="publish__input" placeholder="AI识别后自动填入" />
                <UiButton variant="secondary" :loading="recommendLoading" @click="handleRecommendCategory">
                  AI 识别类目
                </UiButton>
              </div>
            </div>
          </div>

          <div v-if="categories.length > 0" class="publish__cats">
            <button
              v-for="c in categories"
              :key="c.categoryId"
              class="publish__cat"
              :class="{ 'publish__cat--active': categoryId === c.categoryId }"
              @click="selectCategory(c)"
            >
              {{ c.categoryName || c.categoryId }}
            </button>
          </div>

          <div class="publish__field">
            <label class="publish__label">商品描述</label>
            <textarea v-model="description" class="publish__textarea" rows="4" placeholder="商品描述（可选）"></textarea>
          </div>

          <div class="publish__field">
            <label class="publish__label">商品图片（首图为主图，最多 9 张）</label>
            <MultiImageUploader
              v-if="selectedAccountId"
              :account-id="selectedAccountId"
              v-model="imagesStr"
              :max="9"
            />
            <p v-else class="publish__hint">请先选择账号后再上传图片</p>
          </div>

          <div class="publish__actions">
            <UiButton variant="primary" :loading="publishing" @click="handlePublish">发布商品</UiButton>
            <p class="publish__hint">发布为写操作，同一账号每分钟限 1 次</p>
          </div>
        </section>

        <!-- 下架 -->
        <section class="publish__card">
          <div class="publish__card-title">
            <span class="publish__card-icon publish__card-icon--danger"><IconTrash /></span>
            下架商品
          </div>
          <div class="publish__field">
            <label class="publish__label">商品ID</label>
            <input v-model="offShelfItemId" class="publish__input" placeholder="输入要下架的商品ID" />
          </div>
          <div class="publish__actions">
            <UiButton variant="danger" :loading="offShelfing" @click="handleOffShelf">下架商品</UiButton>
            <p class="publish__hint">也可从下方历史记录点击「回填」</p>
          </div>
        </section>
      </div>


      <!-- 商品模板（F3） -->
      <section class="publish__card">
        <div class="publish__card-title">商品模板</div>
        <div class="publish__card-subtitle">维护常用发布模板，支持 CSV 批量导入与批量上架（模板价格可为数字或占位符）</div>
        <div class="publish__template-toolbar">
          <div class="publish__template-actions">
            <UiButton variant="secondary" @click="openTemplateDialog()">
              <template #default>+ 新建模板</template>
            </UiButton>
            <UiButton variant="secondary" @click="csvDialogVisible = true">
              <template #default>导入CSV</template>
            </UiButton>
            <UiButton variant="primary" :loading="batchPublishing" @click="handleBatchPublish">
              <template #default>批量上架（{{ selectedTemplateIds.length }}）</template>
            </UiButton>
          </div>
          <div class="publish__template-hint">批量上架使用当前选择的账号：{{ selectedAccountLabel }}</div>
        </div>

        <div v-if="templatesLoading" class="publish__empty">加载中...</div>
        <div v-else-if="templates.length === 0" class="publish__empty">暂无模板，点击「新建模板」或「导入CSV」创建</div>
        <div v-else class="publish__table-wrap">
          <table class="publish__table">
            <thead>
              <tr>
                <th style="width: 36px;"></th>
                <th>标题</th>
                <th>价格</th>
                <th>类目</th>
                <th>图片</th>
                <th>备注</th>
                <th style="width: 190px;"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in templates" :key="t.id">
                <td>
                  <input
                    type="checkbox"
                    :checked="t.id ? selectedTemplateIds.includes(t.id) : false"
                    @change="toggleTemplateSelect(t)"
                  />
                </td>
                <td class="publish__cell-title">{{ t.title }}</td>
                <td>{{ t.pricePlaceholder || '-' }}</td>
                <td class="publish__cell-muted">{{ t.categoryId || '-' }}</td>
                <td class="publish__cell-muted">{{ t.imageUrls ? t.imageUrls.split(/[,;；]/).filter(s => s.trim()).length + ' 张' : '-' }}</td>
                <td class="publish__cell-muted">{{ t.remark || '-' }}</td>
                <td>
                  <div class="publish__template-row-actions">
                    <button class="publish__refill" @click="openTemplateDialog(t)">编辑</button>
                    <button class="publish__refill" @click="fillPublishFromTemplate(t)">填入表单</button>
                    <button class="publish__refill publish__refill--danger" @click="handleTemplateDelete(t)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="batchResults" class="publish__batch-result">
          <div class="publish__batch-result-title">批量上架结果</div>
          <div v-for="r in batchResults" :key="r.templateId" class="publish__batch-result-item">
            <span class="publish__status" :class="r.success ? 'publish__status--ok' : 'publish__status--fail'">
              {{ r.success ? '成功' : '失败' }}
            </span>
            <span>模板#{{ r.templateId }}</span>
            <span class="publish__cell-muted">{{ r.itemId || r.message || '-' }}</span>
          </div>
        </div>

        <div v-if="templateTotal > templatePageSize" class="publish__pagination">
          <button class="publish__refill" :disabled="templatePage <= 1" @click="templatePage--; loadTemplates()">上一页</button>
          <span>{{ templatePage }} / {{ Math.ceil(templateTotal / templatePageSize) }}</span>
          <button class="publish__refill" :disabled="templatePage >= Math.ceil(templateTotal / templatePageSize)" @click="templatePage++; loadTemplates()">下一页</button>
        </div>
      </section>

      <!-- 模板编辑对话框 -->
      <div v-if="templateDialogVisible" class="publish__modal-overlay" @click.self="templateDialogVisible = false">
        <div class="publish__modal">
          <div class="publish__modal-title">{{ templateForm.id ? '编辑模板' : '新建模板' }}</div>
          <div class="publish__modal-body">
            <label class="publish__modal-label">标题 *</label>
            <input v-model="templateForm.title" class="publish__modal-input" placeholder="商品标题" />
            <label class="publish__modal-label">描述</label>
            <textarea v-model="templateForm.description" class="publish__modal-textarea" rows="3" placeholder="商品描述"></textarea>
            <label class="publish__modal-label">价格（数字或占位符，如 9.9 / {价格}）</label>
            <input v-model="templateForm.pricePlaceholder" class="publish__modal-input" placeholder="例如 9.9 或 {价格}" />
            <label class="publish__modal-label">图片URL（逗号/分号分隔）</label>
            <textarea v-model="templateForm.imageUrls" class="publish__modal-textarea" rows="2" placeholder="https://img.xxx/a.jpg;https://img.xxx/b.jpg"></textarea>
            <label class="publish__modal-label">类目ID（可选）</label>
            <input v-model="templateForm.categoryId" class="publish__modal-input" placeholder="类目ID" />
            <label class="publish__modal-label">备注</label>
            <input v-model="templateForm.remark" class="publish__modal-input" placeholder="备注" />
          </div>
          <div class="publish__modal-actions">
            <UiButton variant="secondary" @click="templateDialogVisible = false">取消</UiButton>
            <UiButton variant="primary" :loading="templateSaving" @click="handleTemplateSave">保存</UiButton>
          </div>
        </div>
      </div>

      <!-- CSV 导入对话框 -->
      <div v-if="csvDialogVisible" class="publish__modal-overlay" @click.self="csvDialogVisible = false">
        <div class="publish__modal">
          <div class="publish__modal-title">CSV 批量导入模板</div>
          <div class="publish__modal-body">
            <p class="publish__modal-hint">列顺序：标题,描述,价格,图片URL,类目ID,备注。首行可为表头，每行一个模板。</p>
            <textarea v-model="csvContent" class="publish__modal-textarea" rows="8" placeholder="标题,描述,价格,图片URL,类目ID,备注&#10;示例商品,这是描述,9.9,https://img.xxx/a.jpg,cat-1,"></textarea>
          </div>
          <div class="publish__modal-actions">
            <UiButton variant="secondary" @click="csvDialogVisible = false">取消</UiButton>
            <UiButton variant="primary" :loading="csvImporting" @click="handleImportCsv">导入</UiButton>
          </div>
        </div>
      </div>

      <!-- 历史记录 -->
      <section class="publish__card">
        <div class="publish__card-title">发布 / 下架历史</div>
        <div v-if="historyLoading" class="publish__empty">加载中...</div>
        <div v-else-if="historyList.length === 0" class="publish__empty">
          暂无历史记录
        </div>
        <div v-else class="publish__table-wrap">
          <table class="publish__table">
            <thead>
              <tr>
                <th>时间</th>
                <th>操作</th>
                <th>标题 / 商品ID</th>
                <th>价格</th>
                <th>状态</th>
                <th>结果</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="h in historyList" :key="h.id">
                <td class="publish__cell-muted">{{ formatTime(h.createdTime) }}</td>
                <td>{{ opTypeText(h.operationType) }}</td>
                <td class="publish__cell-title">
                  {{ h.title || '-' }}
                  <span v-if="h.itemId" class="publish__cell-id">{{ h.itemId }}</span>
                </td>
                <td>{{ formatPrice(h.price) }}</td>
                <td>
                  <span class="publish__status" :class="h.status === 'SUCCESS' ? 'publish__status--ok' : 'publish__status--fail'">
                    {{ h.status === 'SUCCESS' ? '成功' : '失败' }}
                  </span>
                </td>
                <td class="publish__cell-muted">{{ h.errorMessage || '-' }}</td>
                <td>
                  <button
                    v-if="h.itemId"
                    class="publish__refill"
                    @click="useHistoryItemId(h.itemId)"
                  >
                    回填
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </UiPageShell>
</template>

<style scoped>
.publish {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.publish__grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 16px;
}

@media (max-width: 900px) {
  .publish__grid {
    grid-template-columns: 1fr;
  }
}

.publish__card {
  position: relative;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
  }

.publish__card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  pointer-events: none;
  background: var(--glass-highlight);
}

.publish__card--account {
  padding: 12px 16px;
}

.publish__card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1, var(--text-1));
}

.publish__card-icon {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--brand-50);
  border: 1px solid color-mix(in srgb, var(--brand-600) 18%, transparent);
  color: var(--brand-600);
}

.publish__card-icon svg {
  width: 15px;
  height: 15px;
}

.publish__card-icon--danger {
  background: var(--danger-50);
  color: var(--danger-500);
}

.publish__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.publish__row {
  display: flex;
  gap: 12px;
}

.publish__field--half {
  flex: 1;
}

.publish__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2, var(--text-2));
}

.publish__input,
.publish__select,
.publish__textarea {
  width: 100%;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--glass-border);
  background: var(--glass-bg-soft);
  color: var(--text-1, var(--text-1));
  font-size: 13px;
  outline: none;
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  transition: border-color 0.2s;
  box-sizing: border-box;
}

.publish__input:focus,
.publish__select:focus,
.publish__textarea:focus {
  border-color: var(--brand-600);
  box-shadow: 0 0 0 3px var(--ring-brand);
}

.publish__textarea {
  resize: vertical;
  font-family: inherit;
}

.publish__category {
  display: flex;
  gap: 8px;
}

.publish__category .publish__input {
  flex: 1;
  min-width: 0;
}

.publish__cats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.publish__cat {
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--glass-border);
  background: var(--glass-bg-soft);
  font-size: 12px;
  color: var(--text-2, var(--text-2));
  cursor: pointer;
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  transition: all 0.2s;
}

.publish__cat--active {
  background: var(--brand-50);
  border-color: var(--brand-500);
  color: var(--brand-600);
  font-weight: 600;
}

.publish__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.publish__hint {
  margin: 0;
  font-size: 11px;
  color: var(--text-3, var(--text-3));
}

.publish__empty {
  padding: 24px;
  text-align: center;
  color: var(--text-3, var(--text-3));
  font-size: 13px;
}

.publish__table-wrap {
  overflow-x: auto;
}

.publish__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.publish__table th,
.publish__table td {
  padding: 8px 10px;
  text-align: left;
  border-bottom: 1px solid var(--glass-border);
  white-space: nowrap;
}

.publish__table th {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-3, var(--text-3));
}

.publish__cell-muted {
  color: var(--text-2, var(--text-2));
}

.publish__cell-title {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.publish__cell-id {
  display: block;
  font-size: 11px;
  color: var(--text-3, var(--text-3));
}

.publish__status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 600;
}

.publish__status--ok {
  background: var(--success-50);
  color: var(--success-600);
}

.publish__status--fail {
  background: var(--danger-50);
  color: var(--danger-500);
}

.publish__refill {
  border: none;
  background: transparent;
  color: var(--brand-600);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 6px;
}

@supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
  .publish__card,
  .publish__input,
  .publish__select,
  .publish__textarea,
  .publish__cat {
    background: var(--surface-solid);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .publish__card,
  .publish__input,
  .publish__select,
  .publish__textarea,
  .publish__cat {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface-solid);
  }
}

.publish__btn-icon {
  display: inline-flex;
  align-items: center;
}

.publish__btn-icon svg {
  width: 14px;
  height: 14px;
}
.publish__card-subtitle {
  font-size: 12px;
  color: var(--text-2, var(--text-3));
  margin: 4px 0 12px;
}

.publish__template-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.publish__template-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.publish__template-hint {
  font-size: 12px;
  color: var(--text-2, var(--text-3));
}

.publish__template-row-actions {
  display: flex;
  gap: 8px;
  white-space: nowrap;
}

.publish__refill--danger {
  color: var(--danger-500);
}

.publish__pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: center;
  margin-top: 12px;
  font-size: 12px;
  color: var(--text-2, var(--text-3));
}

.publish__batch-result {
  margin-top: 12px;
  border-top: 1px solid var(--border, rgba(60, 60, 67, 0.15));
  padding-top: 10px;
}

.publish__batch-result-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.publish__batch-result-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  padding: 4px 0;
}

.publish__modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
  padding: 16px;
}

.publish__modal {
  background: var(--surface-solid, #fff);
  border-radius: 12px;
  width: min(560px, 100%);
  max-height: 88vh;
  overflow: auto;
  padding: 20px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.2);
}

.publish__modal-title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 14px;
}

.publish__modal-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.publish__modal-label {
  font-size: 12px;
  color: var(--text-2, var(--text-3));
  margin-top: 8px;
}

.publish__modal-input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--border, rgba(60, 60, 67, 0.2));
  border-radius: 8px;
  font-size: 13px;
  background: var(--surface, #fff);
  color: var(--text-1, var(--text-1));
  outline: none;
}

.publish__modal-input:focus,
.publish__modal-textarea:focus {
  border-color: var(--brand-500, var(--brand-500));
}

.publish__modal-textarea {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--border, rgba(60, 60, 67, 0.2));
  border-radius: 8px;
  font-size: 13px;
  background: var(--surface, #fff);
  color: var(--text-1, var(--text-1));
  outline: none;
  resize: vertical;
  font-family: inherit;
}

.publish__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.publish__modal-hint {
  font-size: 12px;
  color: var(--text-2, var(--text-3));
  margin-bottom: 8px;
}
</style>
