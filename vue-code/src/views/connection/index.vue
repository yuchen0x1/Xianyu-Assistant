<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { accountDisplayName } from '@/utils'
import { useConnectionManager } from './useConnectionManager'
import ConnectionCard from './components/ConnectionCard.vue'
import ConnectionDetail from './components/ConnectionDetail.vue'

const router = useRouter()

const {
  loading,
  accounts,
  selectedAccountId,
  allConnectionStatuses,
  selectAccount,
} = useConnectionManager()

// Responsive
const isMobile = ref(false)
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

// Build connection map for card display
const connectionMap = computed(() => {
  const map = new Map<number, {
    connected?: boolean
    status?: string
    cookieStatus?: number
    tokenExpireTime?: number
  }>()
  for (const [accountId, status] of allConnectionStatuses.value) {
    map.set(accountId, {
      connected: status.connected,
      status: status.status,
      cookieStatus: status.cookieStatus,
      tokenExpireTime: status.tokenExpireTime
    })
  }
  return map
})

const selectedAccountName = computed(() => {
  if (!selectedAccountId.value) return ''
  const acc = accounts.value.find(a => Number(a.id) === selectedAccountId.value)
  return acc ? accountDisplayName(acc) : ''
})

const connectedCount = computed(() => {
  return Array.from(allConnectionStatuses.value.values()).filter(status => status.connected).length
})

// Handle account select
const handleSelectAccount = (account: { id: number }) => {
  const id = Number(account.id)
  if (isMobile.value) {
    router.push(`/connection/${id}`)
  } else {
    selectAccount(id)
  }
}

onMounted(() => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<template>
  <div class="connection">
    <header class="connection__header">
      <div class="connection__title-row">
        <h1 class="connection__title mobile-hidden">连接管理</h1>
      </div>
      <div class="connection__summary" aria-label="连接概况">
        <span><strong>{{ accounts.length }}</strong> 个账号</span>
        <span class="connection__summary-separator"></span>
        <span><strong class="connection__summary-online">{{ connectedCount }}</strong> 已连接</span>
      </div>
    </header>

    <section class="connection__workspace">
      <aside class="connection__accounts-pane">
        <div class="connection__pane-header">
          <h2>账号</h2>
          <span class="connection__count">{{ accounts.length }}</span>
        </div>
        <div class="connection__list">
          <ConnectionCard
            :accounts="accounts"
            :connections="connectionMap"
            :selected-id="selectedAccountId"
            :loading="loading"
            @select="handleSelectAccount"
          />
        </div>
      </aside>

      <div v-if="!isMobile" class="connection__detail">
        <ConnectionDetail
          :account-id="selectedAccountId"
          :account-name="selectedAccountName"
        />
      </div>
    </section>
  </div>
</template>

<style scoped src="./connection.css"></style>

<style scoped>
@media screen and (max-width: 767.98px) {
  .connection__detail { display: none; }
}
</style>
