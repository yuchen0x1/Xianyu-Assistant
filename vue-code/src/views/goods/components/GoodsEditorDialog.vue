<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { createGoodsDraft, updateGoodsInfo } from '@/api/goods'
import type { GoodsItem } from '@/api/goods'
import MultiImageUploader from '@/components/MultiImageUploader.vue'
import { showError, showInfo, showSuccess } from '@/utils'

interface Props {
  modelValue: boolean
  accountId: number | null
  goods?: GoodsItem | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)
const isEdit = computed(() => Boolean(props.goods?.xyGoodId))
const isDraft = computed(() => props.goods?.xyGoodId?.startsWith('draft-') ?? false)
const form = reactive({
  title: '',
  detailInfo: '',
  soldPrice: '',
  infoPic: ''
})

const resetForm = () => {
  form.title = props.goods?.title || ''
  form.detailInfo = props.goods?.detailInfo || ''
  form.soldPrice = props.goods?.soldPrice || ''
  try {
    const pics = props.goods?.infoPic ? JSON.parse(props.goods.infoPic) : []
    form.infoPic = Array.isArray(pics) ? pics.map((item: { url?: string }) => item?.url).filter(Boolean).join(',') : ''
  } catch {
    form.infoPic = ''
  }
}

watch(() => props.modelValue, value => {
  if (value) resetForm()
})

const close = () => {
  if (!saving.value) emit('update:modelValue', false)
}

const openOfficialPage = () => {
  const url = isDraft.value
    ? 'https://www.goofish.com/publish'
    : `https://www.goofish.com/item?id=${encodeURIComponent(props.goods!.xyGoodId)}`
  const popup = window.open(url, '_blank', 'noopener,noreferrer')
  if (!popup) showInfo('浏览器拦截了新窗口，请允许弹窗后重试')
}

const validate = () => {
  if (!props.accountId) return '请先选择账号'
  if (!form.title.trim()) return '请输入商品标题'
  if (form.title.trim().length > 120) return '商品标题不能超过120个字符'
  if (!/^\d+(\.\d{1,2})?$/.test(form.soldPrice.trim()) || Number(form.soldPrice) <= 0) {
    return '请输入大于0且最多两位小数的价格'
  }
  if (form.detailInfo.length > 10000) return '商品描述不能超过10000个字符'
  return ''
}

const save = async () => {
  const error = validate()
  if (error) {
    showError(error)
    return
  }
  saving.value = true
  try {
    const payload = {
      xianyuAccountId: props.accountId as number,
      title: form.title.trim(),
      detailInfo: form.detailInfo.trim(),
      soldPrice: form.soldPrice.trim(),
      infoPic: form.infoPic
    }
    const response = isEdit.value
      ? await updateGoodsInfo({ ...payload, xyGoodsId: props.goods!.xyGoodId })
      : await createGoodsDraft(payload)
    if (response.code !== 0 && response.code !== 200) {
      throw new Error(response.msg || '保存失败')
    }
    showSuccess(isEdit.value ? '商品资料已保存' : '商品草稿已创建')
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    showError((error as Error)?.message || '保存商品资料失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="goods-editor-fade">
      <div v-if="modelValue" class="goods-editor-overlay" @click.self="close">
        <section class="goods-editor" role="dialog" aria-modal="true" aria-labelledby="goods-editor-title">
          <header class="goods-editor__header">
            <div>
              <h2 id="goods-editor-title">{{ isEdit ? '编辑商品资料' : '新建商品资料' }}</h2>
              <p>{{ isEdit ? `商品 ID：${goods?.xyGoodId}` : '先保存为本地草稿，再到闲鱼官方发布页完成发布' }}</p>
            </div>
            <button class="goods-editor__close" type="button" aria-label="关闭" @click="close">×</button>
          </header>

          <div class="goods-editor__body">
            <label class="goods-editor__field">
              <span>商品标题</span>
              <input v-model="form.title" maxlength="120" placeholder="例如：会员兑换码" />
            </label>
            <label class="goods-editor__field">
              <span>售价（元）</span>
              <input v-model="form.soldPrice" inputmode="decimal" placeholder="例如：9.90" />
            </label>
            <label class="goods-editor__field">
              <span>商品简介</span>
              <textarea v-model="form.detailInfo" maxlength="10000" rows="7" placeholder="填写买家看到的商品说明" />
              <small>{{ form.detailInfo.length }}/10000</small>
            </label>
            <div v-if="accountId" class="goods-editor__field">
              <span>商品图片（最多5张）</span>
              <MultiImageUploader :account-id="accountId" v-model="form.infoPic" :max="5" />
            </div>
            <div class="goods-editor__notice">
              资料会保存到助手本地数据库。闲鱼远程价格、标题和详情的编辑接口未公开，保存后请通过官方页面核对并手动确认。
            </div>
          </div>

          <footer class="goods-editor__footer">
            <button
              v-if="goods"
              class="goods-editor__button goods-editor__button--link"
              type="button"
              :disabled="saving"
              @click="openOfficialPage"
            >
              {{ isDraft ? '打开闲鱼发布页' : '打开闲鱼商品页' }}
            </button>
            <button class="goods-editor__button goods-editor__button--secondary" type="button" :disabled="saving" @click="close">取消</button>
            <button class="goods-editor__button goods-editor__button--primary" type="button" :disabled="saving" @click="save">
              {{ saving ? '保存中...' : (isEdit ? '保存资料' : '创建草稿') }}
            </button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.goods-editor-overlay { position: fixed; inset: 0; z-index: 1200; display: flex; align-items: center; justify-content: center; padding: 20px; background: rgba(15, 23, 42, .42); }
.goods-editor { width: min(560px, 100%); max-height: min(760px, calc(100vh - 40px)); overflow: auto; border: 1px solid var(--border-subtle); border-radius: var(--radius-sm); background: var(--surface-primary); color: var(--text-primary); box-shadow: 0 24px 70px rgba(15, 23, 42, .26); }
.goods-editor__header { display: flex; justify-content: space-between; gap: 16px; padding: 20px 22px 14px; border-bottom: 1px solid var(--border-subtle); }
.goods-editor__header h2 { margin: 0; font-size: 18px; }
.goods-editor__header p { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; }
.goods-editor__close { border: 0; background: transparent; color: var(--text-secondary); font-size: 24px; line-height: 1; cursor: pointer; }
.goods-editor__body { display: grid; gap: 16px; padding: 20px 22px; }
.goods-editor__field { display: grid; gap: 7px; font-size: 13px; font-weight: 650; }
.goods-editor__field input, .goods-editor__field textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--border-strong); border-radius: var(--radius-xs); background: var(--surface-secondary); color: var(--text-primary); padding: 10px 12px; font: inherit; font-weight: 400; outline: none; }
.goods-editor__field input:focus, .goods-editor__field textarea:focus { border-color: var(--ab); box-shadow: 0 0 0 3px var(--focus-ring); }
.goods-editor__field textarea { resize: vertical; min-height: 120px; line-height: 1.55; }
.goods-editor__field small { color: var(--text-tertiary); font-size: 11px; font-weight: 400; text-align: right; }
.goods-editor__notice { padding: 10px 12px; border: 1px solid var(--brand-200); border-radius: var(--radius-xs); background: var(--brand-50); color: var(--brand-800); font-size: 12px; line-height: 1.55; }
.goods-editor__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 22px 20px; border-top: 1px solid var(--border-subtle); }
.goods-editor__button { min-width: 96px; height: 36px; border-radius: var(--radius-xs); padding: 0 15px; border: 1px solid transparent; cursor: pointer; font-weight: 650; }
.goods-editor__button:disabled { opacity: .55; cursor: not-allowed; }
.goods-editor__button--secondary { border-color: var(--border-strong); background: var(--surface-secondary); color: var(--text-primary); }
.goods-editor__button--link { border-color: transparent; background: transparent; color: var(--ab); margin-right: auto; }
.goods-editor__button--link:hover { background: color-mix(in srgb, var(--ab) 10%, transparent); }
.goods-editor__button--primary { border-color: var(--ab); background: var(--ab); color: #fff; }
.goods-editor-fade-enter-active, .goods-editor-fade-leave-active { transition: opacity .16s ease; }
.goods-editor-fade-enter-from, .goods-editor-fade-leave-to { opacity: 0; }
@media (max-width: 640px) { .goods-editor-overlay { align-items: flex-end; padding: 0; } .goods-editor { max-height: 92vh; border-radius: var(--radius-sm) var(--radius-sm) 0 0; } }
</style>
