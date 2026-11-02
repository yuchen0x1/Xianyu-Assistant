<script setup lang="ts">
import { ref, watch } from 'vue'
import { manualAddAccount } from '@/api/account'
import { showSuccess, showError } from '@/utils'

interface Props {
  modelValue: boolean
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const formData = ref({
  accountNote: '',
  cookie: ''
})

watch(() => props.modelValue, (val) => {
  if (val) {
    formData.value = { accountNote: '', cookie: '' }
  }
})

const handleClose = () => {
  emit('update:modelValue', false)
}

const handleSubmit = async () => {
  if (!formData.value.accountNote.trim()) {
    showError('请输入账号备注')
    return
  }
  if (!formData.value.cookie.trim()) {
    showError('请输入Cookie')
    return
  }

  try {
    const response = await manualAddAccount({
      accountNote: formData.value.accountNote,
      cookie: formData.value.cookie
    })
    if (response.code === 0 || response.code === 200) {
      showSuccess('添加成功')
      handleClose()
      emit('success')
    } else {
      throw new Error(response.msg || '添加失败')
    }
  } catch (error) {
    console.error('手动添加失败:', error)
  }
}
</script>

<template>
  <teleport to="body">
    <div v-if="modelValue" class="ui-modal-overlay" @click="handleClose">
      <div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="manual-add-title" @click.stop>
        <div class="ui-modal__header">
          <h2 id="manual-add-title" class="ui-modal__title">手动添加账号</h2>
        </div>

        <div class="ui-modal__body ui-modal__body--stack">
          <input
            v-model="formData.accountNote"
            type="text"
            class="ui-modal__input"
            placeholder="账号备注"
            autocomplete="off"
          />
          <textarea
            v-model="formData.cookie"
            class="ui-modal__textarea"
            placeholder="Cookie"
            rows="5"
          />
        </div>

        <div class="ui-modal__footer">
          <button type="button" class="ui-modal__btn" @click="handleClose">取消</button>
          <div class="ui-modal__divider"></div>
          <button type="button" class="ui-modal__btn ui-modal__btn--primary" @click="handleSubmit">添加</button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.ui-modal__body--stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
