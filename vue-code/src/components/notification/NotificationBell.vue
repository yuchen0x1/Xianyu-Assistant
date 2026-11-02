<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getNotificationList, markNotificationRead, markAllNotificationsRead } from '@/api/notification'
import type { NotificationItem } from '@/api/notification'
import { toast } from '@/utils/toast'
import IconBell from '@/components/icons/IconBell.vue'
import IconClose from '@/components/icons/IconClose.vue'

defineOptions({ name: 'NotificationBell' })

const open = ref(false)
const list = ref<NotificationItem[]>([])
const unread = ref(0)
const loading = ref(false)
const bellRef = ref<HTMLElement | null>(null)

const levelLabel = (level: string): string => {
  switch (level) {
    case 'ERROR': return '错误'
    case 'WARNING': return '警告'
    default: return '信息'
  }
}

const levelClass = (level: string): string => {
  switch (level) {
    case 'ERROR': return 'nb__level nb__level--error'
    case 'WARNING': return 'nb__level nb__level--warning'
    default: return 'nb__level nb__level--info'
  }
}

const formatTime = (time: string): string => {
  if (!time) return ''
  return time.replace('T', ' ').slice(0, 19)
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await getNotificationList(20)
    if ((res.code === 0 || res.code === 200) && res.data) {
      list.value = res.data.list || []
      unread.value = res.data.unread || 0
    }
  } catch (e) {
    console.error('加载通知失败:', e)
  } finally {
    loading.value = false
  }
}

const toggle = () => {
  open.value = !open.value
  if (open.value) void loadList()
}

const handleItemClick = async (item: NotificationItem) => {
  if (item.readStatus === 0) {
    await markNotificationRead(item.id)
    item.readStatus = 1
    unread.value = Math.max(0, unread.value - 1)
  }
}

const handleMarkAllRead = async () => {
  try {
    const res = await markAllNotificationsRead()
    if (res.code === 0 || res.code === 200) {
      list.value.forEach((item) => (item.readStatus = 1))
      unread.value = 0
      toast.success('已全部标记为已读')
    }
  } catch (e) {
    console.error('标记全部已读失败:', e)
    toast.error('操作失败')
  }
}

const handleClickOutside = (e: MouseEvent) => {
  if (bellRef.value && !bellRef.value.contains(e.target as Node)) {
    open.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div ref="bellRef" class="nb">
    <button
      class="nb__trigger"
      type="button"
      :aria-label="unread > 0 ? `有 ${unread} 条未读通知` : '通知中心'"
      :title="unread > 0 ? `有 ${unread} 条未读通知` : '通知中心'"
      @click.stop="toggle"
    >
      <IconBell aria-hidden="true" />
      <span v-if="unread > 0" class="nb__badge">{{ unread > 99 ? '99+' : unread }}</span>
    </button>

    <Teleport to="body">
      <Transition name="nb-pop">
        <div v-if="open" class="nb__panel">
          <div class="nb__panel-header">
            <span class="nb__panel-title">通知中心</span>
            <div class="nb__panel-actions">
              <button v-if="unread > 0" class="nb__mark-all" type="button" @click="handleMarkAllRead">全部已读</button>
              <button class="nb__close" type="button" aria-label="关闭通知" @click="open = false">
                <IconClose aria-hidden="true" />
              </button>
            </div>
          </div>

          <div v-if="loading" class="nb__loading">
            <span class="nb__spinner" aria-hidden="true"></span>
            <span>加载中...</span>
          </div>

          <div v-else-if="list.length === 0" class="nb__empty">
            暂无通知
          </div>

          <ul v-else class="nb__list">
            <li
              v-for="item in list"
              :key="item.id"
              class="nb__item"
              :class="{ 'nb__item--unread': item.readStatus === 0 }"
              @click="handleItemClick(item)"
            >
              <div class="nb__item-head">
                <span :class="levelClass(item.level)">{{ levelLabel(item.level) }}</span>
                <span class="nb__item-time">{{ formatTime(item.createdTime) }}</span>
              </div>
              <div class="nb__item-title">{{ item.title }}</div>
              <div class="nb__item-content">{{ item.content }}</div>
            </li>
          </ul>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.nb {
  position: relative;
}

.nb__trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--text-2);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-standard), background-color var(--duration-fast) var(--ease-standard);
}

.nb__trigger:hover {
  color: var(--text-1);
  background: var(--bg-hover);
}

.nb__trigger svg {
  width: 18px;
  height: 18px;
}

.nb__badge {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  font-size: 10px;
  font-weight: var(--weight-semibold);
  line-height: 1;
  color: #fff;
  background: var(--danger-500);
  border: 2px solid var(--bg-elevated);
  border-radius: 999px;
}

.nb__panel {
  position: fixed;
  top: 52px;
  right: 16px;
  z-index: 1000;
  width: 340px;
  max-height: 440px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
}

.nb__panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border-1);
}

.nb__panel-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
}

.nb__panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nb__mark-all {
  padding: 2px 8px;
  font-size: var(--text-xs);
  color: var(--brand-600);
  background: transparent;
  border: none;
  border-radius: var(--radius-xs);
  cursor: pointer;
}

.nb__mark-all:hover {
  background: var(--bg-hover);
}

.nb__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  color: var(--text-2);
  background: transparent;
  border: none;
  border-radius: var(--radius-xs);
  cursor: pointer;
}

.nb__close:hover {
  color: var(--text-1);
  background: var(--bg-hover);
}

.nb__close svg {
  width: 14px;
  height: 14px;
}

.nb__loading,
.nb__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 16px;
  color: var(--text-2);
  font-size: var(--text-sm);
}

.nb__spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--border-2);
  border-top-color: var(--brand-600);
  border-radius: 50%;
  animation: nb-spin 0.7s linear infinite;
}

@keyframes nb-spin {
  to { transform: rotate(360deg); }
}

.nb__list {
  flex: 1;
  overflow-y: auto;
  margin: 0;
  padding: 0;
  list-style: none;
}

.nb__item {
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid var(--border-1);
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.nb__item:last-child {
  border-bottom: none;
}

.nb__item:hover {
  background: var(--bg-hover);
}

.nb__item--unread {
  background: color-mix(in srgb, var(--brand-50) 60%, transparent);
}

.nb__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.nb__level {
  padding: 1px 6px;
  font-size: 10px;
  font-weight: var(--weight-medium);
  border-radius: var(--radius-xs);
}

.nb__level--info {
  color: var(--brand-700);
  background: var(--brand-50);
}

.nb__level--warning {
  color: var(--warning-700);
  background: var(--warning-50);
}

.nb__level--error {
  color: var(--danger-700);
  background: var(--danger-50);
}

.nb__item-time {
  font-size: var(--text-xs);
  color: var(--text-3);
}

.nb__item-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
  margin-bottom: 2px;
}

.nb__item-content {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: var(--text-xs);
  color: var(--text-2);
  line-height: 1.5;
}

.nb-pop-enter-active,
.nb-pop-leave-active {
  transition: opacity var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard);
}

.nb-pop-enter-from,
.nb-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
}

@media (max-width: 767.98px) {
  .nb__panel {
    position: fixed;
    top: 48px;
    right: 8px;
    left: 8px;
    width: auto;
  }
}
</style>
