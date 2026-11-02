<script setup lang="ts">
import { computed, markRaw, nextTick, onMounted, onUnmounted, provide, ref, shallowRef, watch, type Component } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import NavMenu from './NavMenu.vue'
import UpdateDialog from './UpdateDialog.vue'
import CommandPalette from './CommandPalette.vue'
import NotificationBell from '@/components/notification/NotificationBell.vue'
import { checkUpdate, getSystemHealth } from '@/api/system'
import { useTheme } from '@/composables/useTheme'

import IconChart from '@/components/icons/IconChart.vue'
import IconAccount from '@/components/icons/IconAccount.vue'
import IconWifi from '@/components/icons/IconWifi.vue'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconMessage from '@/components/icons/IconMessage.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconLog from '@/components/icons/IconLog.vue'
import IconShield from '@/components/icons/IconShield.vue'
import IconKey from '@/components/icons/IconKey.vue'
import IconClose from '@/components/icons/IconClose.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconSun from '@/components/icons/IconSun.vue'
import IconMoon from '@/components/icons/IconMoon.vue'
import IconSearch from '@/components/icons/IconSearch.vue'

declare const __APP_VERSION__: string

const route = useRoute()
const { resolvedTheme, toggleTheme } = useTheme()
const drawerVisible = ref(false)
const isCompactLayout = ref(false)
const sidebar = ref<HTMLElement | null>(null)
const menuToggle = ref<HTMLButtonElement | null>(null)
const drawerCloseButton = ref<HTMLButtonElement | null>(null)
const headerContent = shallowRef<Component | null>(null)
const updateDialog = ref<InstanceType<typeof UpdateDialog> | null>(null)
const currentVersion = ref(__APP_VERSION__ || '3.4.10')
const hasNewVersion = ref(false)
const serviceStatus = ref<'checking' | 'available' | 'unavailable'>('checking')
const commandPaletteOpen = ref(false)
let compactLayoutQuery: MediaQueryList | null = null

const pageIconMap: Record<string, Component> = {
  dashboard: markRaw(IconChart),
  accounts: markRaw(IconAccount),
  connection: markRaw(IconWifi),
  'connection-detail': markRaw(IconWifi),
  goods: markRaw(IconShoppingBag),
  orders: markRaw(IconTruck),
  messages: markRaw(IconMessage),
  'auto-delivery': markRaw(IconRobot),
  'auto-reply': markRaw(IconChat),
  'operation-log': markRaw(IconLog),
  settings: markRaw(IconShield),
  'kami-config': markRaw(IconKey)
}

const currentPageTitle = computed(() => String(route.meta.title || 'XianYu Seller Console'))
const currentPageIcon = computed(() => pageIconMap[String(route.name || '')] || IconChart)
const drawerIsHidden = computed(() => isCompactLayout.value && !drawerVisible.value)
const workspaceIsHidden = computed(() => isCompactLayout.value && drawerVisible.value)
const serviceStatusText = computed(() => {
  if (serviceStatus.value === 'checking') return '正在检测本机服务'
  return serviceStatus.value === 'available' ? '本机服务运行中' : '本机服务不可用'
})
const themeToggleLabel = computed(() => resolvedTheme.value === 'dark' ? '切换到浅色模式' : '切换到深色模式')

const setHeaderContent = (content: Component | null) => {
  headerContent.value = content
}

provide('setHeaderContent', setHeaderContent)

const loadServiceStatus = async () => {
  serviceStatus.value = 'checking'
  try {
    const response = await getSystemHealth()
    if (response.data?.version) currentVersion.value = response.data.version
    serviceStatus.value = 'available'
    return true
  } catch {
    serviceStatus.value = 'unavailable'
    return false
  }
}

const loadUpdateStatus = async () => {
  try {
    const response = await checkUpdate()
    hasNewVersion.value = response.data?.hasUpdate === true
  } catch {
    hasNewVersion.value = false
  }
}

const initializeVersionState = async () => {
  const serviceAvailable = await loadServiceStatus()
  if (serviceAvailable) await loadUpdateStatus()
}

const openUpdateDialog = () => updateDialog.value?.open()

const openDrawer = async () => {
  drawerVisible.value = true
  await nextTick()
  drawerCloseButton.value?.focus()
}

const closeDrawer = () => {
  if (!drawerVisible.value) return
  drawerVisible.value = false
  if (isCompactLayout.value) {
    void nextTick(() => menuToggle.value?.focus())
  }
}

const getDrawerFocusableElements = () => {
  if (!sidebar.value) return []
  return Array.from(sidebar.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )).filter(element => element.getClientRects().length > 0 && element.getAttribute('aria-hidden') !== 'true')
}

const trapDrawerFocus = (event: KeyboardEvent) => {
  const focusableElements = getDrawerFocusableElements()
  if (!focusableElements.length) {
    event.preventDefault()
    sidebar.value?.focus()
    return
  }

  const firstElement = focusableElements[0]
  const lastElement = focusableElements[focusableElements.length - 1]
  const activeElement = document.activeElement
  const focusIsOutsideDrawer = !activeElement || !sidebar.value?.contains(activeElement)

  if (event.shiftKey && (activeElement === firstElement || focusIsOutsideDrawer)) {
    event.preventDefault()
    lastElement?.focus()
  } else if (!event.shiftKey && (activeElement === lastElement || focusIsOutsideDrawer)) {
    event.preventDefault()
    firstElement?.focus()
  }
}

const onKeydown = (event: KeyboardEvent) => {
  if (event.defaultPrevented) return
  const target = event.target as HTMLElement | null
  const isTyping = target?.matches('input, textarea, select, [contenteditable="true"]')
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    commandPaletteOpen.value = !commandPaletteOpen.value
    return
  }
  if (event.key === 'Escape' && commandPaletteOpen.value) {
    event.preventDefault()
    commandPaletteOpen.value = false
    return
  }
  if (isTyping || !drawerVisible.value || !isCompactLayout.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDrawer()
    return
  }
  if (event.key === 'Tab') trapDrawerFocus(event)
}

const onCompactLayoutChange = (event: MediaQueryListEvent | MediaQueryList) => {
  isCompactLayout.value = event.matches
  if (!event.matches) drawerVisible.value = false
}

watch(() => route.fullPath, () => {
  headerContent.value = null
  if (isCompactLayout.value) closeDrawer()
})

onMounted(() => {
  compactLayoutQuery = window.matchMedia('(max-width: 1023.98px)')
  onCompactLayoutChange(compactLayoutQuery)
  compactLayoutQuery.addEventListener('change', onCompactLayoutChange)
  window.addEventListener('keydown', onKeydown)
  void initializeVersionState()
})

onUnmounted(() => {
  compactLayoutQuery?.removeEventListener('change', onCompactLayoutChange)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="app-layout">
    <div
      v-if="drawerVisible"
      class="sidebar-overlay"
      aria-hidden="true"
      @click="closeDrawer"
    ></div>

    <aside
      ref="sidebar"
      id="app-sidebar"
      class="sidebar"
      :class="{ 'sidebar--open': drawerVisible }"
      :inert="drawerIsHidden"
      :aria-hidden="drawerIsHidden ? 'true' : undefined"
      aria-label="主导航侧栏"
      tabindex="-1"
    >
      <div class="sidebar__header">
        <button class="brand" type="button" @click="openUpdateDialog">
          <img class="brand__mark" src="/brand-mark.svg" alt="XianYu Seller Console" />
          <div class="brand__copy">
            <strong>XianYu Seller Console</strong>
            <small>
              Operations Console
              <span class="brand__version" :class="{ 'brand__version--new': hasNewVersion }">
                v{{ currentVersion }}
              </span>
            </small>
          </div>
        </button>
        <button
          ref="drawerCloseButton"
          class="sidebar__close"
          type="button"
          aria-label="关闭导航菜单"
          title="关闭导航菜单"
          @click="closeDrawer"
        >
          <IconClose aria-hidden="true" />
        </button>
      </div>

      <div class="sidebar__nav">
        <NavMenu @select="closeDrawer" />
      </div>

      <div class="sidebar__footer">
        <div class="sidebar__status" role="status" aria-live="polite">
          <span
            class="sidebar__status-dot"
            :class="`sidebar__status-dot--${serviceStatus}`"
            aria-hidden="true"
          ></span>
          <span>{{ serviceStatusText }}</span>
        </div>
        <button
          class="update-check-button"
          type="button"
          :class="{ 'update-check-button--new': hasNewVersion }"
          @click="openUpdateDialog"
        >
          <IconRefresh aria-hidden="true" />
          <span>{{ hasNewVersion ? '发现新版本' : '检查更新' }}</span>
        </button>
        <button
          class="theme-toggle"
          type="button"
          :aria-label="themeToggleLabel"
          :title="themeToggleLabel"
          @click="toggleTheme"
        >
          <IconSun v-if="resolvedTheme === 'dark'" aria-hidden="true" />
          <IconMoon v-else aria-hidden="true" />
          <span>{{ resolvedTheme === 'dark' ? '深色' : '浅色' }}</span>
        </button>
      </div>
    </aside>

    <section
      class="workspace"
      :inert="workspaceIsHidden"
      :aria-hidden="workspaceIsHidden ? 'true' : undefined"
    >
      <header class="workspace-header">
        <button
          ref="menuToggle"
          class="menu-toggle"
          type="button"
          :aria-label="drawerVisible ? '关闭导航菜单' : '打开导航菜单'"
          aria-controls="app-sidebar"
          :aria-expanded="drawerVisible"
          @click="drawerVisible ? closeDrawer() : openDrawer()"
        >
          <span></span><span></span><span></span>
        </button>

        <div class="workspace-header__title">
          <span class="workspace-header__icon" aria-hidden="true">
            <component :is="currentPageIcon" />
          </span>
          <div class="workspace-header__copy">
            <span class="workspace-header__eyebrow">Workspace</span>
            <strong>{{ currentPageTitle }}</strong>
          </div>
        </div>

        <div class="workspace-header__right">
          <div v-if="headerContent" class="workspace-header__actions">
            <component :is="headerContent" />
          </div>
          <NotificationBell />
          <button
            class="command-trigger"
            type="button"
            aria-label="打开快速导航"
            title="快速导航（Ctrl + K）"
            @click="commandPaletteOpen = true"
          >
            <IconSearch aria-hidden="true" /><span class="command-trigger__label">快速导航</span><kbd>Ctrl K</kbd>
          </button>
          <button
            class="theme-toggle theme-toggle--header"
            type="button"
            :aria-label="themeToggleLabel"
            :title="themeToggleLabel"
            @click="toggleTheme"
          >
            <IconSun v-if="resolvedTheme === 'dark'" aria-hidden="true" />
            <IconMoon v-else aria-hidden="true" />
          </button>
        </div>
      </header>

      <main class="workspace-main">
        <RouterView v-slot="{ Component, route: currentRoute }">
          <Transition name="fade-slide" mode="out-in">
            <component :is="Component" :key="currentRoute.fullPath" />
          </Transition>
        </RouterView>
      </main>
    </section>

    <UpdateDialog ref="updateDialog" />
    <CommandPalette :open="commandPaletteOpen" @close="commandPaletteOpen = false" />
  </div>
</template>

<style scoped>
.app-layout {
  position: relative;
  isolation: isolate;
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background:
    linear-gradient(115deg, color-mix(in srgb, var(--brand-500) 8%, transparent), transparent 38%),
    linear-gradient(310deg, color-mix(in srgb, var(--teal-500) 7%, transparent), transparent 42%),
    linear-gradient(215deg, color-mix(in srgb, var(--rose-500) 5%, transparent), transparent 36%),
    var(--gradient-app-soft);
  color: var(--text-1);
}






.sidebar {
  position: relative;
  z-index: var(--z-sidebar);
  display: flex;
  width: var(--sidebar-width);
  min-width: var(--sidebar-width);
  height: 100%;
  flex-direction: column;
  background: var(--glass-bg-strong);
  border-right: 1px solid var(--glass-border);
  box-shadow: var(--glass-edge-glow), 12px 0 40px rgba(15, 23, 42, 0.1);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.sidebar::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  pointer-events: none;
  background: var(--glass-highlight-strong);
}

.sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 72px;
  padding: 16px 14px 10px;
}

.brand {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 0;
  color: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
  text-align: left;
}

.brand__mark {
  display: block;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  object-fit: contain;
  border-radius: 8px;
  box-shadow: var(--shadow-sm);
}

.brand__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.brand__copy strong {
  color: var(--text-1);
  font-size: 15px;
  font-weight: 750;
  line-height: 1.2;
}

.brand__copy small {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.2;
}

.brand__version {
  display: inline-flex;
  align-items: center;
  min-height: 18px;
  padding: 0 6px;
  color: var(--text-2);
  font-size: 10px;
  font-weight: 700;
  border-radius: var(--radius-pill);
  background: var(--bg-hover);
}

.brand__version--new {
  color: #fff;
  background: var(--gradient-brand);
}

.sidebar__close {
  display: none;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  color: var(--text-2);
  background: var(--bg-muted);
  border: 1px solid var(--border-1);
  border-radius: 8px;
  cursor: pointer;
}

.sidebar__close:hover {
  color: var(--text-1);
  background: var(--bg-hover);
}

.sidebar__close :deep(svg) {
  width: 16px;
  height: 16px;
}

.sidebar__nav {
  flex: 1 1 auto;
  overflow: auto;
  padding: 4px 0 12px;
}

.sidebar__footer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-top: 1px solid var(--glass-border);
}

.sidebar__status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  padding: 0 10px;
  color: var(--text-2);
  font-size: 12px;
  font-weight: 600;
  border-radius: 8px;
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.sidebar__status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--text-3) 16%, transparent);
}

.sidebar__status-dot--available {
  background: var(--success-500);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--success-500) 18%, transparent);
}

.sidebar__status-dot--unavailable {
  background: var(--danger-500);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--danger-500) 18%, transparent);
}

.sidebar__status-dot--checking {
  background: var(--warning-500);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--warning-500) 18%, transparent);
  animation: pulse-dot 1.2s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(0.85); opacity: 0.7; }
}

.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 36px;
  padding: 0 12px;
  color: var(--text-2);
  font-size: 12px;
  font-weight: 700;
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: 8px;
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard);
}

.update-check-button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 12px;
  color: var(--text-2);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-standard),
    background-color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.update-check-button svg {
  width: 15px;
  height: 15px;
}

.update-check-button:hover {
  color: var(--text-1);
  background: var(--bg-hover);
  border-color: var(--border-2);
}

.update-check-button--new {
  color: var(--brand-700);
  background: var(--brand-50);
  border-color: var(--brand-200);
  box-shadow: 0 0 16px color-mix(in srgb, var(--brand-500) 16%, transparent);
}

.theme-toggle:hover {
  color: var(--text-1);
  background: var(--bg-hover);
}

.theme-toggle--header {
  width: 36px;
  min-height: 36px;
  padding: 0;
}

.theme-toggle :deep(svg) {
  width: 16px;
  height: 16px;
}
.command-trigger { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 0 9px; color: var(--text-2); font: 600 var(--text-sm) var(--font-sans); background: var(--glass-bg-soft); border: 1px solid var(--glass-border); border-radius: 8px; cursor: pointer; -webkit-backdrop-filter: var(--glass-blur-sm); backdrop-filter: var(--glass-blur-sm); }
.command-trigger:hover { color: var(--text-1); background: var(--glass-bg-strong); border-color: var(--glass-border-strong); }
.command-trigger kbd { padding: 1px 4px; color: var(--text-3); font: 700 10px var(--font-sans); background: var(--glass-bg-soft); border: 1px solid var(--glass-border); border-radius: var(--radius-xs); }
.command-trigger :deep(svg) { width: 16px; height: 16px; }

.workspace {
  position: relative;
  z-index: 0;
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  height: 100%;
}

.workspace-header {
  z-index: var(--z-header);
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: var(--header-height);
  padding: 10px 18px;
  background: var(--glass-bg-strong);
  border-bottom: 1px solid var(--glass-border-strong);
  box-shadow: var(--glass-edge-glow), 0 10px 30px rgba(15, 23, 42, 0.08);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.workspace-header::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  pointer-events: none;
  background: var(--glass-highlight-strong);
}

.menu-toggle {
  display: none;
  width: 38px;
  height: 38px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 0;
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-radius: 8px;
  cursor: pointer;
}

.menu-toggle span {
  display: block;
  width: 16px;
  height: 1.5px;
  background: var(--text-2);
  border-radius: var(--radius-pill);
}

.workspace-header__title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.workspace-header__icon {
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-600), var(--cyan-500));
  border: 1px solid color-mix(in srgb, var(--brand-500) 70%, var(--glass-highlight-strong));
  border-radius: 8px;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.22), inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.workspace-header__icon :deep(svg) {
  width: 18px;
  height: 18px;
}

.workspace-header__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.workspace-header__eyebrow {
  color: var(--text-3);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.workspace-header__copy strong {
  color: var(--text-1);
  font-size: 15px;
  font-weight: 750;
  line-height: 1.2;
}

.workspace-header__right {
  display: flex;
  margin-left: auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.workspace-header__actions {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.workspace-main {
  position: relative;
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
  padding: 22px;
}

.sidebar-overlay {
  display: none;
}

@media (max-width: 1023.98px) {
  .sidebar {
    position: fixed;
    top: 0;
    left: 0;
    transform: translateX(-104%);
    transition: transform var(--duration-normal) var(--ease-emphasized);
  }

  .sidebar--open {
    transform: translateX(0);
  }

  .sidebar__close {
    display: inline-flex;
  }

  .menu-toggle {
    display: inline-flex;
  }

  .sidebar-overlay {
    position: fixed;
    inset: 0;
    z-index: calc(var(--z-sidebar) - 1);
    display: block;
    background: rgba(15, 23, 42, 0.36);
    }

  .workspace-main {
    padding: 16px;
  }
}

@media (max-width: 767.98px) {
  .workspace-header {
    padding: 10px 12px;
  }

  .workspace-header__eyebrow {
    display: none;
  }

  .workspace-main {
    padding: 14px;
  }
  .command-trigger__label, .command-trigger kbd { display: none; }
  .command-trigger { width: 36px; justify-content: center; padding: 0; }
}
</style>
