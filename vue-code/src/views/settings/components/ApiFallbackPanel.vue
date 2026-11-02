<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getSetting, saveSetting } from '@/api/setting'
import { toast } from '@/utils/toast'

defineOptions({ name: 'ApiFallbackPanel' })

const API_FALLBACK_ENABLED_KEY = 'api_delivery_fallback_enabled'
const API_FALLBACK_ACCOUNT_IDS_KEY = 'api_delivery_fallback_account_ids'

const apiFallbackEnabled = ref(false)
const apiFallbackAccountIds = ref('')
const apiFallbackSaving = ref(false)
const apiFallbackLoaded = ref(false)

async function loadApiFallbackConfig() {
  try {
    const [enabledRes, accountIdsRes] = await Promise.all([
      getSetting({ settingKey: API_FALLBACK_ENABLED_KEY }),
      getSetting({ settingKey: API_FALLBACK_ACCOUNT_IDS_KEY })
    ])

    if (enabledRes.code === 200 && enabledRes.data && enabledRes.data.settingValue) {
      apiFallbackEnabled.value = enabledRes.data.settingValue === '1' || enabledRes.data.settingValue === 'true'
    }
    if (accountIdsRes.code === 200 && accountIdsRes.data) {
      apiFallbackAccountIds.value = (accountIdsRes.data.settingValue || '').trim()
    }
  } catch (e) {
    console.error('加载API兜底发货配置失败:', e)
  } finally {
    apiFallbackLoaded.value = true
  }
}

async function handleSaveApiFallbackConfig() {
  apiFallbackSaving.value = true
  try {
    const [enabledRes, accountIdsRes] = await Promise.all([
      saveSetting({
        settingKey: API_FALLBACK_ENABLED_KEY,
        settingValue: apiFallbackEnabled.value ? '1' : '0',
        settingDesc: 'API兜底发货开关（1启用，0关闭）'
      }),
      saveSetting({
        settingKey: API_FALLBACK_ACCOUNT_IDS_KEY,
        settingValue: apiFallbackAccountIds.value.trim(),
        settingDesc: 'API兜底发货允许的账号ID，多个用逗号分隔'
      })
    ])

    if (enabledRes.code === 200 && accountIdsRes.code === 200) {
      toast.success('API 兜底发货配置已保存')
    }
  } catch (e) {
    console.error('保存API兜底发货配置失败:', e)
    toast.error('保存失败')
  } finally {
    apiFallbackSaving.value = false
  }
}

onMounted(loadApiFallbackConfig)
</script>

<template>
  <div class="settings__panel">
    <div class="settings__panel-title">API 兜底发货</div>
    <div class="settings__section">
      <div class="settings__section-title">后台兜底开关</div>
      <p class="settings__desc">开启后，当 WebSocket 断线时，系统会按允许账号列表轮询“待发货”订单并尝试 API 发货；关闭后仅保留前台断线自动退回 API 的能力。</p>
      <div class="settings__field">
        <label class="settings__label">启用 API 兜底发货</label>
        <label class="settings__switch">
          <input type="checkbox" v-model="apiFallbackEnabled" :disabled="apiFallbackSaving" />
          <span class="settings__switch-track"></span>
          <span class="settings__switch-thumb"></span>
        </label>
      </div>
      <div class="settings__field">
        <label class="settings__label">允许账号 ID（逗号分隔）</label>
        <input
          v-model="apiFallbackAccountIds"
          type="text"
          class="settings__input"
          placeholder="如 1,2,3"
          :disabled="apiFallbackSaving"
        />
        <p class="settings__hint">留空时不会执行后台轮询。</p>
      </div>
      <div class="settings__actions">
        <button
          class="settings__btn settings__btn--primary"
          :disabled="apiFallbackSaving"
          @click="handleSaveApiFallbackConfig"
        >
          {{ apiFallbackSaving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>
