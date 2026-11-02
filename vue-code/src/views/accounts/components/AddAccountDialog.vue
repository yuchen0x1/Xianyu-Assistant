<script setup lang="ts">
import { ref, watch } from 'vue'
import { updateAccount } from '@/api/account'
import { showSuccess, showError } from '@/utils'
import type { Account } from '@/types'

interface Props {
  modelValue: boolean
  account?: Account | null
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const formData = ref({
  accountId: 0,
  accountNote: ''
})

watch(() => props.account, (newAccount) => {
  if (newAccount) {
    formData.value = {
      accountId: newAccount.id,
      accountNote: newAccount.accountNote || ''
    }
  } else {
    formData.value = {
      accountId: 0,
      accountNote: ''
    }
  }
}, { immediate: true })

const handleClose = () => {
  emit('update:modelValue', false)
}

const handleSubmit = async () => {
  if (!formData.value.accountNote.trim()) {
    showError('请输入账号备注')
    return
  }

  try {
    const response = await updateAccount(formData.value)
    if (response.code === 0 || response.code === 200) {
      showSuccess('保存成功')
      handleClose()
      emit('success')
    } else {
      throw new Error(response.msg || '保存失败')
    }
  } catch (error) {
    console.error('保存失败:', error)
  }
}
</script>

<template>
  <teleport to="body">
    <div v-if="modelValue" class="ui-modal-overlay" @click="handleClose">
      <div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="edit-account-title" @click.stop>
        <div class="ui-modal__header">
          <h2 id="edit-account-title" class="ui-modal__title">{{ props.account ? '编辑账号' : '添加账号' }}</h2>
        </div>

        <div class="ui-modal__body">
          <input
            v-model="formData.accountNote"
            type="text"
            class="ui-modal__input"
            placeholder="账号备注"
            autocomplete="off"
          />
        </div>

        <div class="ui-modal__footer">
          <button type="button" class="ui-modal__btn" @click="handleClose">取消</button>
          <div class="ui-modal__divider"></div>
          <button type="button" class="ui-modal__btn ui-modal__btn--primary" @click="handleSubmit">保存</button>
        </div>
      </div>
    </div>
  </teleport>
</template>
