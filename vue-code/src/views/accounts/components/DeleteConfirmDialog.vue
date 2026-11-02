<script setup lang="ts">
import { ref } from 'vue'
import { deleteAccount } from '@/api/account'
import { showSuccess } from '@/utils'

interface Props {
  modelValue: boolean
  accountId?: number | null
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const loading = ref(false)

const handleClose = () => {
  emit('update:modelValue', false)
}

const handleConfirm = async () => {
  if (!props.accountId) return

  loading.value = true
  try {
    const response = await deleteAccount({ id: props.accountId })
    if (response.code === 0 || response.code === 200) {
      showSuccess('账号删除成功')
      handleClose()
      emit('success')
    } else {
      throw new Error(response.msg || '删除失败')
    }
  } catch (error) {
    console.error('删除失败:', error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <teleport to="body">
    <div v-if="modelValue" class="ui-modal-overlay" @click="handleClose">
      <div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="delete-account-title" @click.stop>
        <div class="ui-modal__header">
          <h2 id="delete-account-title" class="ui-modal__title">删除账号</h2>
        </div>

        <div class="ui-modal__body ui-modal__body--center">
          <p class="delete-title">确定要删除这个账号吗？</p>
          <p class="delete-desc">删除后将无法恢复以下数据：</p>
          <ul class="delete-list">
            <li>聊天消息</li>
            <li>商品信息</li>
            <li>自动发货配置</li>
            <li>自动回复配置</li>
            <li>Cookie信息</li>
          </ul>
        </div>

        <div class="ui-modal__footer">
          <button type="button" class="ui-modal__btn" @click="handleClose">取消</button>
          <div class="ui-modal__divider"></div>
          <button
            type="button"
            class="ui-modal__btn ui-modal__btn--danger"
            :disabled="loading"
            @click="handleConfirm"
          >
            {{ loading ? '删除中...' : '删除' }}
          </button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.delete-title {
  margin: 0 0 8px;
  color: var(--text-1);
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  line-height: var(--leading-tight);
}

.delete-desc {
  margin: 0 0 10px;
  color: var(--text-2);
  font-size: var(--text-md);
  line-height: 1.45;
}

.delete-list {
  margin: 0 auto;
  padding: 0;
  width: fit-content;
  list-style: none;
  text-align: left;
  color: var(--text-2);
  font-size: var(--text-md);
}

.delete-list li {
  position: relative;
  margin: 4px 0;
  padding-left: 14px;
  line-height: 1.45;
}

.delete-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0.55em;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--danger-500);
}
</style>
