import { computed, ref, watch } from 'vue'

export type Locale = 'zh-CN' | 'en-US'
export type LocaleSetting = 'auto' | Locale

const STORAGE_KEY = 'xianyu:locale'
const localeSetting = ref<LocaleSetting>(readSetting())

const messages = {
  'zh-CN': {
    language: '语言', auto: '自动', workspace: '工作区', mainNav: '主导航', closeNav: '关闭导航菜单',
    openNav: '打开导航菜单', quickNav: '快速导航', checkUpdate: '检查更新', newVersion: '发现新版本',
    dark: '深色', light: '浅色', serviceChecking: '正在检测本机服务', serviceRunning: '本机服务运行中',
    serviceUnavailable: '本机服务不可用', dashboardGroup: '工作台', automationGroup: '自动化', systemGroup: '系统', opsGroup: '运维与集成',
    todo: '待办中心', dashboard: '数据面板', accounts: '闲鱼账号', connection: '连接管理', goods: '商品管理', messages: '消息管理',
    kami: '卡密仓库', autoDelivery: '自动发货', deliveryTemplates: '发货模板', orders: '发货记录', pendingOrders: '待发货订单', autoReply: '自动回复', reviews: '自动评价',
    operationLog: '操作日志', commands: '命令中心', jobs: '任务中心', scheduleTasks: '计划任务', settings: '系统设置', monitoring: '监控中心', similar: '同类资源', webhooks: 'Webhook',
  },
  'en-US': {
    language: 'Language', auto: 'Auto', workspace: 'Workspace', mainNav: 'Main navigation', closeNav: 'Close navigation',
    openNav: 'Open navigation', quickNav: 'Quick navigation', checkUpdate: 'Check for updates', newVersion: 'New version available',
    dark: 'Dark', light: 'Light', serviceChecking: 'Checking local service', serviceRunning: 'Local service is running',
    serviceUnavailable: 'Local service unavailable', dashboardGroup: 'Workspace', automationGroup: 'Automation', systemGroup: 'System', opsGroup: 'Operations',
    todo: 'Tasks', dashboard: 'Dashboard', accounts: 'Xianyu accounts', connection: 'Connections', goods: 'Products', messages: 'Messages',
    kami: 'License keys', autoDelivery: 'Auto delivery', deliveryTemplates: 'Delivery templates', orders: 'Delivery records', pendingOrders: 'Pending orders', autoReply: 'Auto reply', reviews: 'Auto reviews',
    operationLog: 'Operation log', commands: 'Command center', jobs: 'Jobs', scheduleTasks: 'Scheduled tasks', settings: 'Settings', monitoring: 'Monitoring', similar: 'Similar resources', webhooks: 'Webhooks',
  },
} as const
export type LocaleMessageKey = keyof typeof messages['zh-CN']

function readSetting(): LocaleSetting {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'zh-CN' || value === 'en-US' ? value : 'auto'
  } catch {
    return 'auto'
  }
}

export function resolveLocale(setting: LocaleSetting, candidates: readonly string[] = []): Locale {
  if (setting === 'zh-CN' || setting === 'en-US') return setting
  for (const candidate of (candidates.length ? candidates : ['zh-CN'])) {
    const value = String(candidate).toLowerCase()
    if (value === 'en' || value.startsWith('en-')) return 'en-US'
    if (value === 'zh' || value.startsWith('zh-')) return 'zh-CN'
  }
  return 'zh-CN'
}

const locale = computed<Locale>(() => resolveLocale(
  localeSetting.value,
  typeof navigator === 'undefined' ? [] : navigator.languages || [navigator.language]
))

watch(localeSetting, (value) => {
  try { localStorage.setItem(STORAGE_KEY, value) } catch { /* storage unavailable */ }
  if (typeof document !== 'undefined') document.documentElement.lang = locale.value
}, { immediate: true })

export function useLocale() {
  const t = (key: LocaleMessageKey) => messages[locale.value][key]
  const setLocaleSetting = (value: LocaleSetting) => { localeSetting.value = value }
  return { locale, localeSetting, setLocaleSetting, t }
}
