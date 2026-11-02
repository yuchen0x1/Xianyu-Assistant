<script setup lang="ts">
import type { Account } from '@/types'
import { accountDisplayName, accountAvatarChar } from '@/utils'
import IconArrowRight from '@/components/icons/IconArrowRight.vue'
import IconEmpty from '@/components/icons/IconEmpty.vue'
import IconCookie from '@/components/icons/IconCookie.vue'
import IconWs from '@/components/icons/IconWs.vue'

interface ConnectionInfo {
  connected?: boolean
  status?: string
  cookieStatus?: number
  tokenExpireTime?: number
}

interface Props {
  accounts: Account[]
  connections: Map<number, ConnectionInfo>
  selectedId: number | null
  loading?: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  select: [account: Account]
}>()

const hideImg = (e: Event) => {
  (e.target as HTMLImageElement).style.display = 'none'
}

const getCookieStyle = (status?: number) => {
  if (status === 1) return { color: 'var(--success-600)', background: 'var(--success-50)' }
  if (status === 2) return { color: 'var(--warning-600)', background: 'var(--warning-50)' }
  if (status === 3) return { color: 'var(--danger-600)', background: 'var(--danger-50)' }
  return { color: 'var(--text-3)', background: 'var(--bg-muted)' }
}

const getCookieText = (status?: number) => {
  if (status === 1) return '有效'
  if (status === 2) return '过期'
  if (status === 3) return '失效'
  return '未知'
}

const getWsStyle = (info?: ConnectionInfo) => {
  if (info?.connected) return { color: 'var(--success-600)', background: 'var(--success-50)' }
  if (info) return { color: 'var(--danger-600)', background: 'var(--danger-50)' }
  return { color: 'var(--text-3)', background: 'var(--bg-muted)' }
}

const getWsText = (info?: ConnectionInfo) => {
  if (!info) return '未检测'
  return info.connected ? '已连接' : '未连接'
}
</script>

<template>
  <div class="account-list" :class="{ 'account-list--loading': loading }">
    <button
      v-for="account in accounts"
      :key="account.id"
      class="account-row"
      :class="{ 'account-row--active': selectedId === Number(account.id) }"
      type="button"
      @click="emit('select', account)"
    >
      <img
        v-if="account.avatarUrl"
        :src="account.avatarUrl"
        alt=""
        class="account-row__avatar account-row__avatar--img"
        @error="hideImg"
      />
      <span v-else class="account-row__avatar">{{ accountAvatarChar(account) }}</span>

      <span class="account-row__identity">
        <strong>{{ accountDisplayName(account) }}</strong>
        <small>ID {{ account.id }}</small>
      </span>

      <span class="account-row__statuses">
        <span
          class="status-tag"
          :style="getCookieStyle(connections.get(Number(account.id))?.cookieStatus)"
        >
          <IconCookie />
          {{ getCookieText(connections.get(Number(account.id))?.cookieStatus) }}
        </span>
        <span
          class="status-tag"
          :style="getWsStyle(connections.get(Number(account.id)))"
        >
          <IconWs />
          {{ getWsText(connections.get(Number(account.id))) }}
        </span>
      </span>

      <IconArrowRight class="account-row__arrow" aria-hidden="true" />
    </button>

    <div v-if="!loading && accounts.length === 0" class="account-empty">
      <IconEmpty />
      <strong>暂无账号</strong>
    </div>
  </div>
</template>

<style scoped>
.account-list {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  background: transparent;
}

.account-list--loading {
  opacity: 0.55;
  pointer-events: none;
}

.account-row {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 78px;
  padding: 11px 12px;
  grid-template-columns: 38px minmax(0, 1fr) 16px;
  grid-template-rows: auto auto;
  column-gap: 10px;
  row-gap: 7px;
  color: var(--text-primary);
  text-align: left;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--glass-border);
  cursor: pointer;
  transition: background-color 140ms ease, box-shadow 140ms ease;
}

.account-row:hover {
  background: color-mix(in srgb, var(--glass-highlight) 72%, var(--bg-hover));
}

.account-row--active {
  background: color-mix(in srgb, var(--brand-500) 8%, var(--glass-bg-strong));
  box-shadow: inset 3px 0 0 var(--ab);
}

.account-row__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  color: var(--brand-700);
  font-size: 14px;
  font-weight: 700;
  background: color-mix(in srgb, var(--brand-500) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--brand-500) 25%, transparent);
  border-radius: 8px;
  grid-row: 1 / 3;
}

.account-row__avatar--img {
  object-fit: cover;
}

.account-row__identity {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-self: end;
}

.account-row__identity strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-row__identity small {
  margin-top: 2px;
  color: var(--text-tertiary);
  font-size: 10px;
  line-height: 1.2;
}

.account-row__statuses {
  display: flex;
  min-width: 0;
  align-items: center;
  align-self: start;
  gap: 5px;
  overflow: hidden;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  gap: 3px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  border-radius: var(--radius-xs);
  white-space: nowrap;
}

.status-tag svg {
  width: 11px;
  height: 11px;
}

.account-row__arrow {
  width: 16px;
  height: 16px;
  color: var(--text-tertiary);
  align-self: center;
  grid-column: 3;
  grid-row: 1 / 3;
}

.account-empty {
  display: flex;
  min-height: 220px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  color: var(--text-tertiary);
}

.account-empty svg {
  width: 34px;
  height: 34px;
}

.account-empty strong {
  font-size: 13px;
  font-weight: 600;
}

@media (max-width: 767.98px) {
  .account-row {
    min-height: 82px;
    padding: 12px 13px;
  }
}
</style>
