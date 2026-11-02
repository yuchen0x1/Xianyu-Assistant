<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getAccountStatusText, formatTime, accountDisplayName, accountAvatarChar } from '@/utils'
import type { Account } from '@/types'

import IconEdit from '@/components/icons/IconEdit.vue'
import IconTrash from '@/components/icons/IconTrash.vue'
import IconEmpty from '@/components/icons/IconEmpty.vue'
import IconClock from '@/components/icons/IconClock.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconAlert from '@/components/icons/IconAlert.vue'

interface Props {
  accounts: Account[]
  loading?: boolean
}

interface Emits {
  (e: 'edit', account: Account): void
  (e: 'delete', id: number): void
}

defineProps<Props>()
const emit = defineEmits<Emits>()

const isMobile = ref(false)
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})

const getStatusColor = (status: number) => {
  const info = getAccountStatusText(status)
  switch (info.type) {
    case 'success': return 'var(--success-600)'
    case 'warning': return 'var(--warning-600)'
    case 'danger': return 'var(--danger-600)'
    default: return 'var(--brand-700)'
  }
}

const getStatusBg = (status: number) => {
  const info = getAccountStatusText(status)
  switch (info.type) {
    case 'success': return 'var(--success-50)'
    case 'warning': return 'var(--warning-50)'
    case 'danger': return 'var(--danger-50)'
    default: return 'var(--brand-50)'
  }
}

const displayName = (account: Account) => accountDisplayName(account)

const avatarChar = (account: Account) => accountAvatarChar(account)

const hideImg = (e: Event) => {
  (e.target as HTMLImageElement).style.display = 'none'
}
</script>

<template>
  <!-- Mobile: Card View -->
  <div v-if="isMobile" class="card-list" :class="{ 'card-list--loading': loading }">
    <div
      v-for="account in accounts"
      :key="account.id"
      class="account-card"
    >
      <div class="account-card__header">
        <img
          v-if="account.avatarUrl"
          :src="account.avatarUrl"
          alt=""
          class="account-card__avatar account-card__avatar--img"
          @error="hideImg"
        />
        <div v-else class="account-card__avatar">{{ avatarChar(account) }}</div>
        <div class="account-card__info">
          <span class="account-card__name">{{ displayName(account) }}</span>
          <span class="account-card__unb">UNB: {{ account.unb }}</span>
        </div>
        <span
          class="account-card__status"
          :style="{
            color: getStatusColor(account.status),
            background: getStatusBg(account.status)
          }"
        >
          <component :is="getAccountStatusText(account.status).type === 'success' ? IconCheck : IconAlert" />
          {{ getAccountStatusText(account.status).text }}
        </span>
      </div>

      <div class="account-card__body">
        <div class="account-card__row">
          <span class="account-card__label">ID</span>
          <span class="account-card__value">{{ account.id }}</span>
        </div>
        <div class="account-card__row">
          <span class="account-card__label">信用分</span>
          <span class="account-card__value">{{ account.creditScore ?? '-' }}</span>
        </div>
        <div class="account-card__row">
          <span class="account-card__label">卖出</span>
          <span class="account-card__value">{{ account.soldCount ?? '-' }}</span>
        </div>
        <div class="account-card__row">
          <span class="account-card__label">粉丝</span>
          <span class="account-card__value">{{ account.followers ?? '-' }}</span>
        </div>
        <div class="account-card__row">
          <div class="account-card__label-icon"><IconClock /></div>
          <span class="account-card__label">创建</span>
          <span class="account-card__value">{{ formatTime(account.createdTime) }}</span>
        </div>
        <div class="account-card__row">
          <div class="account-card__label-icon"><IconClock /></div>
          <span class="account-card__label">更新</span>
          <span class="account-card__value">{{ formatTime(account.updatedTime) }}</span>
        </div>
      </div>

      <div class="account-card__footer">
        <button class="account-card__btn account-card__btn--edit" @click="emit('edit', account)">
          <IconEdit />
          <span>编辑</span>
        </button>
        <button class="account-card__btn account-card__btn--delete" @click="emit('delete', account.id)">
          <IconTrash />
          <span>删除</span>
        </button>
      </div>
    </div>

        <UiEmpty
      v-if="!loading && accounts.length === 0"
      title="暂无账号数据"
      description="点击上方扫码添加或手动添加账号"
    >
      <template #icon><IconEmpty /></template>
    </UiEmpty>
  </div>

  <!-- Desktop/Tablet: Table View -->
  <div v-else class="table-container" :class="{ 'table-container--loading': loading }">
    <table class="table" v-if="accounts.length > 0">
      <thead class="table__head">
        <tr>
          <th class="table__th table__th--id">ID</th>
          <th class="table__th table__th--nickname">昵称</th>
          <th class="table__th">UNB</th>
          <th class="table__th">账号备注</th>
          <th class="table__th table__th--status">状态</th>
          <th class="table__th table__th--credit">信用分</th>
          <th class="table__th table__th--stat">卖出</th>
          <th class="table__th table__th--stat">粉丝</th>
          <th class="table__th table__th--time">创建时间</th>
          <th class="table__th table__th--time">更新时间</th>
          <th class="table__th table__th--actions">操作</th>
        </tr>
      </thead>
      <tbody class="table__body">
        <tr v-for="account in accounts" :key="account.id" class="table__tr">
          <td class="table__td table__td--id">{{ account.id }}</td>
          <td class="table__td table__td--nickname">
            <div class="nickname-cell">
              <img
                v-if="account.avatarUrl"
                :src="account.avatarUrl"
                alt=""
                class="nickname-cell__avatar"
                @error="hideImg"
              />
              <span v-else class="nickname-cell__avatar nickname-cell__avatar--placeholder">{{ avatarChar(account) }}</span>
              <span class="nickname-cell__text" :title="account.nickname || ''">{{ account.nickname || '-' }}</span>
            </div>
          </td>
          <td class="table__td">{{ account.unb }}</td>
          <td class="table__td">{{ account.accountNote || '未命名账号' }}</td>
          <td class="table__td table__td--status">
            <span
              class="status-tag"
              :style="{
                color: getStatusColor(account.status),
                background: getStatusBg(account.status)
              }"
            >
              {{ getAccountStatusText(account.status).text }}
            </span>
          </td>
          <td class="table__td table__td--credit">{{ account.creditScore ?? '-' }}</td>
          <td class="table__td table__td--credit">{{ account.soldCount ?? '-' }}</td>
          <td class="table__td table__td--credit">{{ account.followers ?? '-' }}</td>
          <td class="table__td table__td--time">{{ formatTime(account.createdTime) }}</td>
          <td class="table__td table__td--time">{{ formatTime(account.updatedTime) }}</td>
          <td class="table__td table__td--actions">
            <button class="table__action table__action--edit" @click="emit('edit', account)">
              <IconEdit />
              <span>编辑</span>
            </button>
            <button class="table__action table__action--delete" @click="emit('delete', account.id)">
              <IconTrash />
              <span>删除</span>
            </button>
          </td>
        </tr>
      </tbody>
    </table>

        <UiEmpty
      v-if="!loading && accounts.length === 0"
      title="暂无账号数据"
      description="点击上方扫码添加或手动添加账号"
    >
      <template #icon><IconEmpty /></template>
    </UiEmpty>
  </div>
</template>

<style scoped>
/* AccountTable — tokenized table/card views */
.card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
}

.account-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  transition: transform var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard), border-color var(--duration-fast) var(--ease-standard);
}

@media (hover: hover) {
  .account-card:hover {
    transform: translateY(-1px);
    border-color: var(--glass-border-strong);
    box-shadow: var(--glass-shadow-float);
  }
}

.account-card__header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.account-card__avatar {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--brand-500) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--brand-500) 25%, transparent);
  color: var(--brand-700);
  font-size: 16px;
  font-weight: var(--weight-bold);
  flex-shrink: 0;
}

.account-card__info {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.account-card__name {
  color: var(--text-1);
  font-size: 15px;
  font-weight: var(--weight-semibold);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-card__unb {
  color: var(--text-3);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.account-card__status,
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 24px;
  padding: 0 10px;
  border-radius: var(--radius-pill);
  border: 1px solid color-mix(in srgb, currentColor 18%, transparent);
  font-size: 12px;
  font-weight: var(--weight-semibold);
  line-height: 1;
  white-space: nowrap;
}

.account-card__status svg {
  width: 12px;
  height: 12px;
}

.account-card__body {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
  padding: 10px 0;
  border-top: 1px solid var(--border-1);
  border-bottom: 1px solid var(--border-1);
}

.account-card__row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.account-card__label-icon {
  display: inline-flex;
  color: var(--text-3);
}

.account-card__label-icon svg {
  width: 14px;
  height: 14px;
}

.account-card__label {
  color: var(--text-3);
  font-size: 12px;
}

.account-card__value {
  color: var(--text-1);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-card__footer {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.account-card__btn,
.table__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: var(--weight-semibold);
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
  -webkit-tap-highlight-color: transparent;
}

.account-card__btn svg,
.table__action svg {
  width: 14px;
  height: 14px;
}

.account-card__btn--edit,
.table__action--edit {
  color: var(--brand-700);
  background: var(--brand-50);
  border-color: var(--brand-200);
}

@media (hover: hover) {
  .account-card__btn--edit:hover,
  .table__action--edit:hover {
    background: var(--brand-100);
    border-color: var(--brand-300);
  }
}

.account-card__btn--delete,
.table__action--delete {
  color: var(--danger-600);
  background: var(--danger-50);
  border-color: color-mix(in srgb, var(--danger-500) 25%, transparent);
}

@media (hover: hover) {
  .account-card__btn--delete:hover,
  .table__action--delete:hover {
    background: color-mix(in srgb, var(--danger-50) 70%, var(--danger-500));
  }
}

.account-card__btn:active,
.table__action:active {
  transform: scale(0.97);
}

.table-container {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.table__head {
  position: sticky;
  top: 0;
  z-index: 10;
}

.table__th {
  text-align: left;
  padding: 12px 16px;
  font-size: 12px;
  font-weight: var(--weight-semibold);
  color: var(--text-2);
  letter-spacing: 0;
  background: color-mix(in srgb, var(--glass-bg-strong) 88%, var(--bg-muted));
  box-shadow: inset 0 1px 0 var(--glass-highlight-strong);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  white-space: nowrap;
  user-select: none;
  border-bottom: 1px solid var(--border-1);
}

.table__th--id { width: 64px; }
.table__th--status { width: 96px; }
.table__th--time { width: 168px; }
.table__th--actions { width: 140px; text-align: center; }
.table__th--nickname { width: 200px; }
.table__th--credit { width: 88px; }
.table__th--stat { width: 76px; }

.table__tr {
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.table__tr:not(:last-child) .table__td {
  border-bottom: 1px solid var(--border-1);
}

@media (hover: hover) {
  .table__tr:hover .table__td {
    background: linear-gradient(90deg, color-mix(in srgb, var(--brand-500) 12%, transparent), color-mix(in srgb, var(--glass-highlight) 70%, var(--bg-hover)) 26%, color-mix(in srgb, var(--glass-highlight) 70%, var(--bg-hover)));
    box-shadow: inset 2px 0 0 color-mix(in srgb, var(--brand-500) 72%, transparent);
  }
}

.table__td {
  padding: 12px 16px;
  color: var(--text-1);
  white-space: nowrap;
  background: transparent;
  line-height: 1.5;
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.table__td--id {
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.table__td--time {
  color: var(--text-2);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.table__td--credit {
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.nickname-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 180px;
}

.nickname-cell__avatar {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-pill);
  object-fit: cover;
  flex-shrink: 0;
}

.nickname-cell__avatar--placeholder {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--brand-500) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--brand-500) 25%, transparent);
  color: var(--brand-700);
  font-size: 13px;
  font-weight: var(--weight-semibold);
}

.nickname-cell__text {
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-card__avatar--img {
  object-fit: cover;
}

.table__td--actions {
  text-align: center;
}

.table__action + .table__action {
  margin-left: 6px;
}

.card-list--loading,
.table-container--loading {
  opacity: 0.55;
  pointer-events: none;
}

@media screen and (max-width: 480px) {
  .card-list {
    padding: 10px;
    gap: 10px;
  }

  .account-card {
    padding: 14px;
  }

  .account-card__body {
    grid-template-columns: 1fr;
  }

  .account-card__btn {
    min-height: 38px;
  }

}
</style>
