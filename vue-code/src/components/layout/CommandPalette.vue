<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import IconSearch from '@/components/icons/IconSearch.vue'

type CommandItem = { path: string; label: string; group: string; keywords: string }

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const query = ref('')
const input = ref<HTMLInputElement | null>(null)
const activeIndex = ref(0)

const commands: CommandItem[] = [
  { path: '/dashboard', label: '数据面板', group: '工作台', keywords: 'dashboard stats 数据 统计 首页' },
  { path: '/accounts', label: '闲鱼账号', group: '工作台', keywords: 'account 账号 cookie' },
  { path: '/connection', label: '连接管理', group: '工作台', keywords: 'connection websocket 连接 状态' },
  { path: '/goods', label: '商品管理', group: '工作台', keywords: 'goods 商品' },
  { path: '/messages', label: '消息管理', group: '工作台', keywords: 'message 消息 会话' },
  { path: '/kami-config', label: '卡密仓库', group: '自动化', keywords: 'kami 卡密 库存' },
  { path: '/auto-delivery', label: '自动发货', group: '自动化', keywords: 'delivery 发货 自动' },
  { path: '/orders', label: '发货记录', group: '自动化', keywords: 'order 订单 发货 记录' },
  { path: '/auto-reply', label: '自动回复', group: '自动化', keywords: 'reply ai 回复 智能' },
  { path: '/operation-log', label: '操作日志', group: '系统', keywords: 'log 日志 审计' },
  { path: '/commands', label: '命令中心', group: '系统', keywords: 'command 命令 mcp 开放能力 执行' },
  { path: '/settings', label: '系统设置', group: '系统', keywords: 'settings 设置 配置 备份' }
]

const results = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return commands
  return commands.filter(item => `${item.label} ${item.group} ${item.keywords}`.toLowerCase().includes(keyword))
})

const close = () => emit('close')
const go = async (path: string) => {
  await router.push(path)
  close()
}

const selectActiveResult = () => {
  const active = results.value[activeIndex.value]
  if (active) void go(active.path)
}

const moveActiveResult = (direction: 1 | -1) => {
  if (!results.value.length) return
  activeIndex.value = (activeIndex.value + direction + results.value.length) % results.value.length
}

const onSearchKeydown = (event: KeyboardEvent) => {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveActiveResult(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveActiveResult(-1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    selectActiveResult()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

watch(() => props.open, async isOpen => {
  if (!isOpen) { query.value = ''; activeIndex.value = 0; return }
  await nextTick()
  input.value?.focus()
})

watch(results, () => {
  activeIndex.value = 0
})
</script>

<template>
  <Teleport to="body">
    <Transition name="command-palette">
      <div v-if="open" class="command-backdrop" role="presentation" @mousedown.self="close">
        <section class="command-dialog" role="dialog" aria-modal="true" aria-label="快速导航">
          <div class="command-search">
            <IconSearch aria-hidden="true" />
            <input ref="input" v-model="query" type="search" placeholder="搜索页面或功能…" aria-label="搜索页面或功能" @keydown="onSearchKeydown">
            <kbd>Esc</kbd>
          </div>
          <div class="command-list">
            <button v-for="(item, index) in results" :key="item.path" type="button" class="command-item" :class="{ 'command-item--active': index === activeIndex }" @mouseenter="activeIndex = index" @click="go(item.path)">
              <span class="command-item__copy"><strong>{{ item.label }}</strong><small>{{ item.group }}</small></span>
              <span aria-hidden="true">↵</span>
            </button>
            <p v-if="!results.length" class="command-empty">没有匹配的页面或功能</p>
          </div>
          <footer class="command-footer">按 <kbd>↑</kbd><kbd>↓</kbd> 选择，<kbd>↵</kbd> 打开，<kbd>Ctrl</kbd> <span>+</span> <kbd>K</kbd> 随时唤起</footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.command-backdrop { position: fixed; inset: 0; z-index: var(--z-toast); display: grid; place-items: start center; padding: min(12vh, 120px) 16px 16px; background: rgba(15, 23, 42, .38); }
.command-dialog { width: min(100%, 560px); overflow: hidden; background: color-mix(in srgb, var(--bg-elevated) 94%, transparent); border: 1px solid var(--border-2); border-radius: var(--radius-lg); box-shadow: var(--shadow-lg); }
.command-search { display: flex; align-items: center; gap: 10px; padding: 14px 16px; color: var(--text-3); border-bottom: 1px solid var(--border-1); }
.command-search :deep(svg) { width: 18px; height: 18px; }
.command-search input { width: 100%; min-width: 0; color: var(--text-1); font: inherit; font-size: var(--text-base); background: transparent; border: 0; outline: 0; }
kbd { display: inline-flex; align-items: center; min-height: 20px; padding: 0 5px; color: var(--text-2); font: 600 11px var(--font-sans); background: var(--bg-muted); border: 1px solid var(--border-1); border-radius: 5px; }
.command-list { max-height: min(56vh, 420px); padding: 8px; overflow: auto; }
.command-item { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 12px; color: var(--text-3); text-align: left; background: transparent; border: 0; border-radius: var(--radius-sm); cursor: pointer; }
.command-item:hover, .command-item:focus-visible, .command-item--active { color: var(--text-brand); background: var(--bg-active); outline: none; }
.command-item__copy { display: flex; flex-direction: column; gap: 2px; }
.command-item strong { color: var(--text-1); font-size: var(--text-base); }
.command-item small { color: var(--text-3); font-size: var(--text-sm); }
.command-empty { padding: 28px 12px; margin: 0; color: var(--text-2); text-align: center; font-size: var(--text-sm); }
.command-footer { display: flex; align-items: center; gap: 5px; padding: 10px 16px; color: var(--text-3); font-size: var(--text-sm); border-top: 1px solid var(--border-1); }
.command-palette-enter-active, .command-palette-leave-active { transition: opacity var(--duration-normal) var(--ease-standard); }
.command-palette-enter-active .command-dialog, .command-palette-leave-active .command-dialog { transition: transform var(--duration-normal) var(--ease-emphasized), opacity var(--duration-normal) var(--ease-standard); }
.command-palette-enter-from, .command-palette-leave-to { opacity: 0; }
.command-palette-enter-from .command-dialog, .command-palette-leave-to .command-dialog { opacity: 0; transform: translateY(-10px) scale(.98); }
</style>
