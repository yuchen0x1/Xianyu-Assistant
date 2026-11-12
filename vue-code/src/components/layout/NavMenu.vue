<script setup lang="ts">
import { computed, markRaw, onMounted } from 'vue'
import { usePermissions } from '@/composables/usePermissions'
import IconChart from '@/components/icons/IconChart.vue'
import IconAccount from '@/components/icons/IconAccount.vue'
import IconLink from '@/components/icons/IconLink.vue'
import IconShoppingBag from '@/components/icons/IconShoppingBag.vue'
import IconMessage from '@/components/icons/IconMessage.vue'
import IconKey from '@/components/icons/IconKey.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconLog from '@/components/icons/IconLog.vue'
import IconShield from '@/components/icons/IconShield.vue'
import IconRocket from '@/components/icons/IconRocket.vue'
import IconCode from '@/components/icons/IconCode.vue'
import IconAlert from '@/components/icons/IconAlert.vue'

const emit = defineEmits<{
  select: [path: string]
}>()

interface NavItem {
  path: string
  label: string
  icon: unknown
  permission?: string
}

interface NavGroup {
  label: string
  items: NavItem[]
}

const groups: NavGroup[] = [
  {
    label: '工作台',
    items: [
      { path: '/todo', label: '待办中心', icon: markRaw(IconRocket), permission: 'dashboard:read' },
      { path: '/dashboard', label: '数据面板', icon: markRaw(IconChart), permission: 'dashboard:read' },
      { path: '/accounts', label: '闲鱼账号', icon: markRaw(IconAccount), permission: 'account:read' },
      { path: '/connection', label: '连接管理', icon: markRaw(IconLink), permission: 'account:read' },
      { path: '/goods', label: '商品管理', icon: markRaw(IconShoppingBag), permission: 'goods:read' },
      { path: '/messages', label: '消息管理', icon: markRaw(IconMessage), permission: 'message:read' }
    ]
  },
  {
    label: '自动化',
    items: [
      { path: '/kami-config', label: '卡密仓库', icon: markRaw(IconKey), permission: 'kami:read' },
      { path: '/auto-delivery', label: '自动发货', icon: markRaw(IconRobot), permission: 'order:read' },
      { path: '/delivery-templates', label: '发货模板', icon: markRaw(IconTruck), permission: 'order:read' },
      { path: '/orders', label: '发货记录', icon: markRaw(IconTruck), permission: 'order:read' },
      { path: '/pending-orders', label: '待发货订单', icon: markRaw(IconTruck), permission: 'order:read' },
      { path: '/auto-reply', label: '自动回复', icon: markRaw(IconChat), permission: 'message:read' },
      { path: '/reviews', label: '自动评价', icon: markRaw(IconChat), permission: 'review:read' }
    ]
  },
  {
    label: '系统',
    items: [
      { path: '/operation-log', label: '操作日志', icon: markRaw(IconLog), permission: 'log:read' },
      { path: '/commands', label: '命令中心', icon: markRaw(IconCode), permission: 'commands:read' },
      { path: '/jobs', label: '任务中心', icon: markRaw(IconRocket), permission: 'jobs:read' },
      { path: '/schedule-tasks', label: '计划任务', icon: markRaw(IconRocket), permission: 'jobs:read' },
      { path: '/settings', label: '系统设置', icon: markRaw(IconShield), permission: 'settings:read' }
    ]
  },
  {
    label: '运维与集成',
    items: [
      { path: '/monitoring', label: '监控中心', icon: markRaw(IconAlert), permission: 'monitoring:read' },
      { path: '/similar-projects', label: '同类资源', icon: markRaw(IconCode), permission: 'settings:read' },
      { path: '/webhooks', label: 'Webhook', icon: markRaw(IconLink), permission: 'webhook:read' }
    ]
  }
]

const { hasPermission, refresh } = usePermissions()

const visibleGroups = computed(() =>
  groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => hasPermission(item.permission))
    }))
    .filter((group) => group.items.length > 0)
)

onMounted(() => {
  refresh().catch(() => {
    // 权限拉取失败时不阻断导航，后端拦截器仍会兜底校验
  })
})
</script>

<template>
  <nav class="nav-menu" aria-label="主导航">
    <section v-for="group in visibleGroups" :key="group.label" class="nav-group">
      <div class="nav-group__label">{{ group.label }}</div>
      <router-link
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        active-class="nav-item--active"
        @click="emit('select', item.path)"
      >
        <span class="nav-item__icon" aria-hidden="true">
          <component :is="item.icon" />
        </span>
        <span class="nav-item__label">{{ item.label }}</span>
      </router-link>
    </section>
  </nav>
</template>

<style scoped>
.nav-menu {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.nav-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-group__label {
  padding: 4px 18px 6px;
  color: color-mix(in srgb, var(--text-3) 78%, var(--brand-500));
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
  line-height: 1.2;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 40px;
  margin: 0 10px;
  padding: 0 12px;
  gap: 10px;
  overflow: hidden;
  color: var(--text-2);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  border-radius: 8px;
  transition:
    color var(--duration-fast) var(--ease-standard),
    background-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.nav-item:hover {
  color: var(--text-1);
  background: var(--glass-bg);
  box-shadow: var(--glass-edge-glow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  transform: translateX(3px);
}

.nav-item--active {
  color: var(--text-brand);
  font-weight: 700;
  background: linear-gradient(105deg, color-mix(in srgb, var(--brand-500) 22%, var(--glass-bg-strong)), color-mix(in srgb, var(--cyan-500) 14%, var(--glass-bg-strong)));
  box-shadow: var(--glow-brand), inset 0 1px 0 rgba(255, 255, 255, 0.15);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.nav-item--active::before {
  content: '';
  position: absolute;
  top: 6px;
  bottom: 6px;
  left: 0;
  width: 3px;
  border-radius: 999px;
  background: var(--gradient-neon);
  box-shadow: 0 0 10px var(--neon-cyan);
}

.nav-item__icon {
  display: inline-flex;
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  opacity: 0.9;
  transition: color var(--duration-fast) var(--ease-standard);
}

.nav-item--active .nav-item__icon {
  color: var(--brand-500);
  opacity: 1;
}

.nav-item__icon :deep(svg) {
  width: 18px;
  height: 18px;
}

.nav-item__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
