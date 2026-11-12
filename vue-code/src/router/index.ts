import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '@/utils/request'
import { loadPermissions, type PermissionState } from '@/composables/usePermissions'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/index.vue'),
      meta: { title: '登录', public: true }
    },
    {
      path: '/',
      redirect: '/dashboard'
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/views/dashboard/index.vue'),
      meta: { title: '数据面板', permissions: ['dashboard:read'] }
    },
    {
      path: '/todo',
      name: 'todo',
      component: () => import('@/views/todo/index.vue'),
      meta: { title: '待办中心', permissions: ['dashboard:read'] }
    },
    {
      path: '/accounts',
      name: 'accounts',
      component: () => import('@/views/accounts/index.vue'),
      meta: { title: '闲鱼账号', permissions: ['account:read'] }
    },
    {
      path: '/connection',
      name: 'connection',
      component: () => import('@/views/connection/index.vue'),
      meta: { title: '连接管理', permissions: ['account:read'] }
    },
    {
      path: '/connection/:id',
      name: 'connection-detail',
      component: () => import('@/views/connection/ConnectionDetail.vue'),
      meta: { title: '连接详情', permissions: ['account:read'] }
    },
    {
      path: '/goods',
      name: 'goods',
      component: () => import('@/views/goods/index.vue'),
      meta: { title: '商品管理', permissions: ['goods:read'] }
    },
    {
      path: '/publish',
      name: 'publish',
      component: () => import('@/views/publish/index.vue'),
      meta: { title: '商品发布', permissions: ['publish:read'] }
    },
    {
      path: '/orders',
      name: 'orders',
      component: () => import('@/views/orders/index.vue'),
      meta: { title: '发货记录', permissions: ['order:read'] }
    },
    {
      path: '/messages',
      name: 'messages',
      component: () => import('@/views/messages/index.vue'),
      meta: { title: '消息管理', permissions: ['message:read'] }
    },
    {
      path: '/auto-delivery',
      name: 'auto-delivery',
      component: () => import('@/views/auto-delivery/index.vue'),
      meta: { title: '自动发货', permissions: ['order:read'] }
    },
    {
      path: '/delivery-templates',
      name: 'delivery-templates',
      component: () => import('@/views/delivery-templates/index.vue'),
      meta: { title: '发货模板', permissions: ['order:read'] }
    },
    {
      path: '/pending-orders',
      name: 'pending-orders',
      component: () => import('@/views/pending-orders/index.vue'),
      meta: { title: '待发货订单', permissions: ['order:read'] }
    },
    {
      path: '/kami-config',
      name: 'kami-config',
      component: () => import('@/views/kami-config/index.vue'),
      meta: { title: '卡密仓库', permissions: ['kami:read'] }
    },
    {
      path: '/auto-reply',
      name: 'auto-reply',
      component: () => import('@/views/auto-reply/index.vue'),
      meta: { title: '自动回复', permissions: ['message:read'] }
    },
    {
      path: '/operation-log',
      name: 'operation-log',
      component: () => import('@/views/operation-log/index.vue'),
      meta: { title: '操作日志', permissions: ['log:read'] }
    },
    {
      path: '/commands',
      name: 'commands',
      component: () => import('@/views/commands/index.vue'),
      meta: { title: '命令中心', permissions: ['commands:read'] }
    },
    {
      path: '/jobs',
      name: 'jobs',
      component: () => import('@/views/jobs/index.vue'),
      meta: { title: '任务中心', permissions: ['jobs:read'] }
    },
    {
      path: '/schedule-tasks',
      name: 'schedule-tasks',
      component: () => import('@/views/schedule-tasks/index.vue'),
      meta: { title: '计划任务', permissions: ['jobs:read'] }
    },
    {
      path: '/webhooks',
      name: 'webhooks',
      component: () => import('@/views/webhooks/index.vue'),
      meta: { title: 'Webhook 配置', permissions: ['webhook:read'] }
    },
    {
      path: '/monitoring',
      name: 'monitoring',
      component: () => import('@/views/monitoring/index.vue'),
      meta: { title: '监控中心', permissions: ['monitoring:read'] }
    },
    {
      path: '/similar-projects',
      name: 'similar-projects',
      component: () => import('@/views/similar-projects/index.vue'),
      meta: { title: '同类资源', permissions: ['settings:read'] }
    },
    {
      path: '/reviews',
      name: 'reviews',
      component: () => import('@/views/reviews/index.vue'),
      meta: { title: '自动评价', permissions: ['review:read'] }
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/settings/index.vue'),
      meta: { title: '系统设置', permissions: ['settings:read'] }
    },
    {
      path: '/qrlogin',
      name: 'qrlogin',
      component: () => import('@/views/qrlogin/index.vue'),
      // 与后端 QRLoginController 的 @RequirePermission("account:write") 对齐：扫码会写入账号登录凭据
      meta: { title: '扫码登录', permissions: ['account:write'] }
    }
  ]
})

// 路由守卫：未登录跳转登录页；无权限跳回可访问页面
router.beforeEach(async (to, _from, next) => {
  if (to.meta.public) {
    next()
    return
  }
  if (!isLoggedIn()) {
    next('/login')
    return
  }
  const required = (to.meta.permissions as string[] | undefined) ?? []
  if (required.length > 0) {
    let state: PermissionState
    try {
      state = await loadPermissions()
    } catch {
      // fail-closed：权限接口异常（网络抖动/5xx）时不能放行，否则前端权限防线
      // 整体失效。但不能跳回 `/login` —— 登录页检测到已登录会再跳回仪表盘，
      // 两个页面用整页重载互相踢，形成刷新死循环。统一带 reason 停在登录页
      // 提示服务异常，且登录页见到 reason 时不再自动跳转。
      console.error('权限信息加载失败，已拒绝导航:', to.fullPath)
      next({ path: '/login', query: { reason: 'perm_unavailable' } })
      return
    }
    if (!canAccess(state, required)) {
      const fallback = resolveLandingPath(state)
      if (fallback) {
        next(fallback)
        return
      }
      // 一个可用页面都没有：同样是"停下来告知"，而不是让两个路由互相踢
      next({ path: '/login', query: { reason: 'no_permission' } })
      return
    }
  }
  next()
})

/**
 * 计算登录后应该落地的页面：优先仪表盘，其次第一个可访问页面，都没有则返回 null。
 *
 * <p>登录页也用它来决定跳转目标。否则"权限接口正常但账号零权限"时，守卫会把
 * 导航打回 `/login`，而此时登录页已挂载、`onMounted` 不再执行，用户只会看到
 * 一张没有任何提示的空白登录页。</p>
 */
export function resolveLandingPath(state: PermissionState): string | null {
  const dashboardPermission = router
    .getRoutes()
    .find((record) => record.path === '/dashboard')?.meta.permissions as string[] | undefined
  if (canAccess(state, dashboardPermission ?? ['dashboard:read'])) {
    return '/dashboard'
  }
  return findFirstAccessiblePath(state)
}

function canAccess(state: PermissionState, required: string[]): boolean {
  if (state.roles.includes('ADMIN')) return true
  if (required.length === 0) return true
  return required.some((permission) => state.permissions.includes(permission))
}

/**
 * 找到当前用户第一个可访问的静态页面，避免缺 dashboard:read 时被反复弹回
 * 同一个无权页面（旧实现固定 fallback 到 /dashboard）。
 */
function findFirstAccessiblePath(state: PermissionState): string | null {
  for (const record of router.getRoutes()) {
    if (record.meta.public) continue
    if (record.path.includes(':') || record.redirect) continue
    const required = (record.meta.permissions as string[] | undefined) ?? []
    if (required.length === 0) continue
    if (canAccess(state, required)) return record.path
  }
  return null
}

export default router
