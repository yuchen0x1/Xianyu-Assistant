<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useDashboard } from './useDashboard';
import { getDataPanelStats, getDataPanelTrend, getRealtimeRevenue, getSalesRevenue, getGoodsRank, getAccountCompare, getKeywordRank, getOperationStats, getFailureRank } from '@/api/data-panel';
import type { DataPanelStats, DataPanelTrend, SalesRevenueData, GoodsRankData, AccountCompareData, KeywordRankData, OperationStatsData, FailureRankData } from '@/api/data-panel';

import IconAccount from '@/components/icons/IconAccount.vue';
import IconPackage from '@/components/icons/IconPackage.vue';
import IconFire from '@/components/icons/IconFire.vue';
import IconLink from '@/components/icons/IconLink.vue';
import IconRobot from '@/components/icons/IconRobot.vue';
import IconChat from '@/components/icons/IconChat.vue';
import IconChart from '@/components/icons/IconChart.vue';
import IconLog from '@/components/icons/IconLog.vue';
import IconRocket from '@/components/icons/IconRocket.vue';
import IconHelp from '@/components/icons/IconHelp.vue';
import IconInfo from '@/components/icons/IconInfo.vue';
import IconArrowRight from '@/components/icons/IconArrowRight.vue';
import IconChevronDown from '@/components/icons/IconChevronDown.vue';
import IconUsers from '@/components/icons/IconUsers.vue';
import IconSend from '@/components/icons/IconSend.vue';
import IconShield from '@/components/icons/IconShield.vue';
import IconSparkle from '@/components/icons/IconSparkle.vue';
import IconCheck from '@/components/icons/IconCheck.vue';
import IconAlert from '@/components/icons/IconAlert.vue';

defineOptions({ name: 'DashboardView' })

const router = useRouter();
const { loading: dashboardLoading, stats, loadStatistics } = useDashboard();
loadStatistics();

const activeStep = ref(0);
const expandedFaq = ref<number | null>(null);

const toggleFaq = (index: number) => {
  expandedFaq.value = expandedFaq.value === index ? null : index;
};

const statCards = [
  { key: 'accountCount', label: '闲鱼账号', icon: IconAccount, color: 'var(--brand-600)' },
  { key: 'goodsCount', label: '商品总数', icon: IconPackage, color: 'var(--success-500)' },
  { key: 'onlineGoodsCount', label: '在售商品', icon: IconFire, color: 'var(--warning-500)' }
] as const;

const quickStartSteps = [
  { title: '添加闲鱼账号', icon: IconAccount, description: '通过扫码登录添加您的闲鱼账号', route: '/accounts', details: ['点击左侧菜单"闲鱼账号"', '点击"扫码登录"按钮', '使用闲鱼APP扫描二维码', '等待登录成功，账号自动添加'] },
  { title: '确认WebSocket连接', icon: IconLink, description: '后台自动保持与闲鱼服务器的实时连接', route: '/connection', details: ['进入"连接管理"页面', '选择要确认的账号', '等待状态自动变为"已连接"', '仅在需要暂停该账号时点击"断开连接"'] },
  { title: '同步商品信息', icon: IconPackage, description: '获取您的闲鱼商品列表', route: '/goods', details: ['进入"商品管理"页面', '选择已连接的账号', '点击"刷新商品"按钮', '等待商品同步完成'] },
  { title: '配置自动化功能', icon: IconRobot, description: '设置自动发货和自动回复', route: '/auto-delivery', details: ['在商品列表中找到目标商品', '开启"自动发货"或"自动回复"', '配置发货内容或回复规则', '保存配置，自动化开始工作'] }
];

const features = [
  { icon: IconUsers, title: '多账号管理', description: '支持同时管理多个闲鱼账号，轻松切换', color: 'var(--brand-600)' },
  { icon: IconSend, title: '自动发货', description: '买家付款后自动发送发货信息，节省时间', color: 'var(--success-500)' },
  { icon: IconChat, title: '自动回复', description: '智能匹配关键词，自动回复买家消息', color: 'var(--warning-500)' },
  { icon: IconChart, title: '数据统计', description: '实时查看商品、订单、消息等数据统计', color: 'var(--danger-500)' },
  { icon: IconShield, title: 'Token自动刷新', description: '智能维护登录状态，无需频繁重新登录', color: 'var(--purple-500)' },
  { icon: IconLog, title: '操作日志', description: '详细记录所有操作，方便追踪和排查', color: 'var(--text-3)' }
];

const faqs = [
  { question: '如何获取Cookie？', answer: '在连接管理页面，点击Cookie部分的帮助按钮，查看详细的获取步骤和示例图片。' },
  { question: 'WebSocket连接失败怎么办？', answer: '1. 检查Cookie是否有效；2. 尝试刷新Token；3. 如果提示需要滑块验证，访问 goofish.com/im 完成验证后手动更新Cookie和Token。' },
  { question: '自动发货什么时候触发？', answer: '当买家付款后，系统会自动检测到"已付款待发货"消息，并根据配置自动发送发货信息。' },
  { question: 'Token过期了怎么办？', answer: '系统会自动刷新Token（1.5-2.5小时刷新一次），也可以在连接管理页面手动刷新。' }
];

const tips = [
  '系统会自动刷新Token，保持登录状态，无需频繁重新登录',
  '建议不要频繁启动/断开连接，避免触发人机验证',
  '自动发货和自动回复依赖WebSocket连接，系统会在后台自动连接并持续重连',
  '所有操作都会记录在"操作日志"中，方便追踪和排查问题'
];

const navigateTo = (route: string) => {
  router.push(route);
};

// === 数据面板 ===
const dataPanelLoading = ref(false)
const dataPanelStats = ref<DataPanelStats>({
  orderCount: 0,
  deliverySuccessCount: 0,
  deliveryFailCount: 0,
  aiReplyCount: 0,
  hasData: false
})
const trendData = ref<DataPanelTrend>({ dates: [], deliverySuccess: [], deliveryFail: [], aiReplies: [] })
const deliveryChartMode = ref<'bar' | 'line'>('bar')
const aiChartMode = ref<'bar' | 'line'>('bar')

const getYesterday = () => {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const getToday = () => {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const selectedDate = ref(getYesterday())
const dateQuick = ref<'today' | 'yesterday' | 'custom'>('yesterday')
let refreshTimer: ReturnType<typeof setInterval> | null = null

const canRefreshRealtimeData = () => activeTab.value === 'data' && !document.hidden

const loadDataPanel = async () => {
  const requestedDate = selectedDate.value
  dataPanelLoading.value = true
  try {
    const [statsRes, trendRes] = await Promise.all([
      getDataPanelStats(requestedDate),
      getDataPanelTrend()
    ])
    // 快速切换日期时丢弃过期响应，避免旧日期数据短暂覆盖当前展示
    if (requestedDate !== selectedDate.value) {
      return
    }
    if (statsRes && (statsRes.code === 200 || statsRes.code === 0) && statsRes.data) {
      dataPanelStats.value = statsRes.data
    }
    if (trendRes && (trendRes.code === 200 || trendRes.code === 0) && trendRes.data) {
      trendData.value = trendRes.data
    }
    void loadAccountCompare()
  } catch (e) {
    console.error('加载数据面板失败:', e)
  } finally {
    dataPanelLoading.value = false
  }
}

const startRealtimeRefresh = () => {
  stopRealtimeRefresh()
  if (canRefreshRealtimeData() && selectedDate.value === getToday()) {
    refreshTimer = setInterval(() => {
      getDataPanelStats(selectedDate.value).then(res => {
        if (res && (res.code === 200 || res.code === 0) && res.data) {
          dataPanelStats.value = res.data
        }
      }).catch(() => {})
    }, 5000)
  }
}

const stopRealtimeRefresh = () => {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
}

const refreshSelectedDate = () => {
  stopRealtimeRefresh()
  void loadDataPanel().then(startRealtimeRefresh)
}

const onDateChange = () => {
  if (!selectedDate.value) selectedDate.value = getYesterday()
  if (selectedDate.value === getToday()) dateQuick.value = 'today'
  else if (selectedDate.value === getYesterday()) dateQuick.value = 'yesterday'
  else dateQuick.value = 'custom'
  refreshSelectedDate()
}

const setQuickDate = (quick: 'today' | 'yesterday') => {
  dateQuick.value = quick
  selectedDate.value = quick === 'today' ? getToday() : getYesterday()
  refreshSelectedDate()
}

const deliveryTotal = computed(() => dataPanelStats.value.deliverySuccessCount + dataPanelStats.value.deliveryFailCount)
const successRate = computed(() => {
  if (deliveryTotal.value === 0) return 0
  return Math.round(dataPanelStats.value.deliverySuccessCount / deliveryTotal.value * 100)
})

// 折线图SVG path生成
const TREND_CHART_WIDTH = 640
const TREND_CHART_PAD_LEFT = 30
const TREND_CHART_PAD_RIGHT = 30

const generatePath = (data: number[], niceMax: number) => {
  if (!data.length) return ''
  const n = data.length
  const xStep = (TREND_CHART_WIDTH - TREND_CHART_PAD_LEFT - TREND_CHART_PAD_RIGHT) / (n - 1 || 1)
  return data.map((v, i) => {
    const x = TREND_CHART_PAD_LEFT + i * xStep
    const y = getLineY(v, niceMax)
    return `${i === 0 ? 'M' : 'L'}${x},${y}`
  }).join(' ')
}

const getLineX = (i: number, total: number) => TREND_CHART_PAD_LEFT + i * ((TREND_CHART_WIDTH - TREND_CHART_PAD_LEFT - TREND_CHART_PAD_RIGHT) / (total - 1 || 1))
const getLineY = (v: number, niceMax: number) => 150 - (v / Math.max(niceMax, 1)) * 140
const getLineMax = (arrs: number[][]) => Math.max(...arrs.flat(), 1)

const niceScale = (maxVal: number, tickCount = 4): { niceMax: number; ticks: number[] } => {
  if (maxVal <= 0) return { niceMax: 1, ticks: [0, 0.25, 0.5, 0.75, 1] }
  const roughStep = maxVal / tickCount
  const mag = Math.pow(10, Math.floor(Math.log10(roughStep)))
  const residual = roughStep / mag
  let niceStep: number
  if (residual <= 1.5) niceStep = mag
  else if (residual <= 3) niceStep = 2 * mag
  else if (residual <= 7) niceStep = 5 * mag
  else niceStep = 10 * mag
  const niceMax = Math.ceil(maxVal / niceStep) * niceStep
  const ticks: number[] = []
  for (let v = 0; v <= niceMax + niceStep * 0.01; v += niceStep) {
    ticks.push(Math.round(v * 1e10) / 1e10)
  }
  return { niceMax, ticks }
}

const salesNiceScale = computed(() => niceScale(salesMaxVal.value))
const deliveryNiceScale = computed(() => niceScale(getLineMax([trendData.value.deliverySuccess, trendData.value.deliveryFail])))
const aiNiceScale = computed(() => niceScale(getLineMax([trendData.value.aiReplies])))

const formatTickValue = (v: number): string => {
  if (v >= 10000) return (v / 10000).toFixed(1) + 'w'
  if (v >= 1000) return (v / 1000).toFixed(1) + 'k'
  if (Number.isInteger(v)) return v.toString()
  return v.toFixed(1)
}

// === Tab 切换 ===
const activeTab = ref<'guide' | 'data'>('data')

// === 实时销售额 ===
const revenueValue = ref(0)
const revenueDisplay = ref('0.00')
let revenueTimer: ReturnType<typeof setInterval> | null = null

const formatRevenue = (val: number): string => {
  return val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const loadRevenue = async () => {
  try {
    const res = await getRealtimeRevenue()
    if (res && (res.code === 200 || res.code === 0) && res.data != null) {
      revenueValue.value = res.data
      revenueDisplay.value = formatRevenue(res.data)
    }
  } catch {
    // ignore
  }
}

const startRevenueRefresh = () => {
  stopRevenueRefresh()
  if (!canRefreshRealtimeData()) return
  loadRevenue()
  revenueTimer = setInterval(loadRevenue, 3000)
}

const stopRevenueRefresh = () => {
  if (revenueTimer) { clearInterval(revenueTimer); revenueTimer = null }
}

const switchTab = (tab: 'guide' | 'data') => {
  activeTab.value = tab
  if (tab === 'data') {
    loadDataPanel().then(() => startRealtimeRefresh())
    startRevenueRefresh()
    loadSalesData()
    loadGoodsRank()
    loadKeywordRank()
    loadOperationStats()
    loadFailureRank()
  } else {
    stopRealtimeRefresh()
    stopRevenueRefresh()
  }
}

const onVisibilityChange = () => {
  if (document.hidden) {
    stopRealtimeRefresh()
    stopRevenueRefresh()
    return
  }

  if (activeTab.value === 'data') {
    void loadDataPanel().then(() => {
      startRealtimeRefresh()
      startRevenueRefresh()
    })
    void loadSalesData()
    void loadGoodsRank()
    void loadKeywordRank()
    void loadOperationStats()
    void loadFailureRank()
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  loadDataPanel().then(() => {
    if (activeTab.value === 'data') {
      startRealtimeRefresh()
      startRevenueRefresh()
      loadSalesData()
      loadGoodsRank()
      loadKeywordRank()
      loadOperationStats()
      loadFailureRank()
    }
  })
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopRealtimeRefresh()
  stopRevenueRefresh()
})

// === 销售额趋势图 ===
const salesDimension = ref<'day' | 'week' | 'month'>('day')
const salesData = ref<SalesRevenueData>({ labels: [], values: [] })
const salesLoading = ref(false)

const formatDateStr = (date: Date): string => {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const salesEndDate = ref(formatDateStr(new Date()))
const salesStartDate = ref(formatDateStr(new Date(new Date().getTime() - 14 * 86400000)))
const salesDateError = ref('')

type SalesQuickRange = '15d' | '1m' | '8w' | '6m_w' | '6m' | '1y'
const salesQuickRange = ref<SalesQuickRange | null>('15d')

const salesQuickOptions = computed<{ key: SalesQuickRange; label: string }[]>(() => {
  switch (salesDimension.value) {
    case 'day': return [{ key: '15d', label: '近15天' }, { key: '1m', label: '近1月' }]
    case 'week': return [{ key: '8w', label: '近8周' }, { key: '6m_w', label: '近半年' }]
    case 'month': return [{ key: '6m', label: '近半年' }, { key: '1y', label: '近1年' }]
    default: return []
  }
})

const getQuickRangeDays = (range: SalesQuickRange): number => {
  switch (range) {
    case '15d': return 14
    case '1m': return 30
    case '8w': return 55
    case '6m_w': return 182
    case '6m': return 182
    case '1y': return 365
  }
}

const setSalesQuickRange = (range: SalesQuickRange) => {
  salesDateError.value = ''
  salesQuickRange.value = range
  const end = new Date()
  const start = new Date(end.getTime() - getQuickRangeDays(range) * 86400000)
  salesStartDate.value = formatDateStr(start)
  salesEndDate.value = formatDateStr(end)
  loadSalesData()
}

const onSalesDateChange = () => {
  salesDateError.value = ''
  if (!salesStartDate.value || !salesEndDate.value) {
    salesDateError.value = '请选择完整的开始和结束日期'
    return
  }
  if (salesStartDate.value > salesEndDate.value) {
    salesDateError.value = '开始日期不能晚于结束日期'
    return
  }
  salesQuickRange.value = null
  loadSalesData()
}

const switchSalesDimension = (dim: 'day' | 'week' | 'month') => {
  salesDateError.value = ''
  salesDimension.value = dim
  const defaultRange = salesQuickOptions.value[0]?.key ?? '15d'
  salesQuickRange.value = defaultRange
  const end = new Date()
  const start = new Date(end.getTime() - getQuickRangeDays(defaultRange) * 86400000)
  salesStartDate.value = formatDateStr(start)
  salesEndDate.value = formatDateStr(end)
  loadSalesData()
}

const loadSalesData = async () => {
  if (!salesStartDate.value || !salesEndDate.value || salesStartDate.value > salesEndDate.value) return
  salesLoading.value = true
  try {
    const res = await getSalesRevenue(salesDimension.value, salesStartDate.value, salesEndDate.value)
    if (res && (res.code === 200 || res.code === 0) && res.data) {
      salesData.value = res.data
    }
  } catch (e) {
    console.error('加载销售额数据失败:', e)
  } finally {
    salesLoading.value = false
  }
}

const salesMaxVal = computed(() => Math.max(...salesData.value.values, 1))
const salesTotal = computed(() => salesData.value.values.reduce((a, b) => a + b, 0))

const SALES_MIN_GAP = 2
const SALES_CHART_PAD_LEFT = 30
const SALES_CHART_PAD_RIGHT = 30
const SALES_CHART_HEIGHT = 180
const SALES_CHART_BASE_WIDTH = 1200

const salesXStep = computed(() => {
  const n = salesData.value.labels.length
  if (n <= 1) return 0
  const drawWidth = SALES_CHART_BASE_WIDTH - SALES_CHART_PAD_LEFT - SALES_CHART_PAD_RIGHT
  const gap = drawWidth / (n - 1)
  return gap >= SALES_MIN_GAP ? gap : SALES_MIN_GAP
})

const salesDrawWidth = computed(() => {
  const n = salesData.value.labels.length
  if (n <= 0) return SALES_CHART_BASE_WIDTH
  return SALES_CHART_PAD_LEFT + (n - 1) * salesXStep.value + SALES_CHART_PAD_RIGHT
})

const salesSvgWidth = computed(() => Math.max(SALES_CHART_BASE_WIDTH, salesDrawWidth.value))
const salesViewBox = computed(() => `0 0 ${salesSvgWidth.value} ${SALES_CHART_HEIGHT}`)
const salesNeedScroll = computed(() => salesDrawWidth.value > SALES_CHART_BASE_WIDTH)

const getSalesX = (i: number) => SALES_CHART_PAD_LEFT + i * salesXStep.value
const getSalesY = (v: number, niceMax: number) => (SALES_CHART_HEIGHT - 30) - (v / Math.max(niceMax, 1)) * (SALES_CHART_HEIGHT - 40)

const SALES_LABEL_CHAR_WIDTH = 5
const SALES_LABEL_MIN_GAP = 4

const salesLabelStep = computed(() => {
  const step = salesXStep.value
  if (step <= 0) return 1
  const maxLabelLen = Math.max(...salesData.value.labels.map(l => l.length), 1)
  const labelPx = maxLabelLen * SALES_LABEL_CHAR_WIDTH
  const needed = labelPx + SALES_LABEL_MIN_GAP
  if (step >= needed) return 1
  return Math.ceil(needed / step)
})

const isSalesLabelVisible = (i: number) => {
  const step = salesLabelStep.value
  if (step <= 1) return true
  return i % step === 0
}

const generateSalesPath = (data: number[], niceMax: number) => {
  if (!data.length) return ''
  return data.map((v, i) => {
    const x = getSalesX(i)
    const y = getSalesY(v, niceMax)
    return `${i === 0 ? 'M' : 'L'}${x},${y}`
  }).join(' ')
}

// === SVG Tooltip ===
interface ChartTooltip {
  visible: boolean
  chartId: string
  x: number
  y: number
  label: string
  lines: { color: string; text: string }[]
}

const tooltip = ref<ChartTooltip>({ visible: false, chartId: '', x: 0, y: 0, label: '', lines: [] })

const showTooltip = (e: MouseEvent, chartId: string, label: string, lines: { color: string; text: string }[]) => {
  const container = (e.currentTarget as HTMLElement).closest('.dp__chart-tooltip-zone') as HTMLElement
  if (!container) return
  const rect = container.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  tooltip.value = { visible: true, chartId, x, y, label, lines }
}

const hideTooltip = () => {
  tooltip.value = { ...tooltip.value, visible: false }
}

// === 商品排行 ===
const goodsRankLoading = ref(false)
const goodsRank = ref<GoodsRankData>({ orderRank: [], inquiryRank: [] })

const loadGoodsRank = async () => {
  goodsRankLoading.value = true
  try {
    const res = await getGoodsRank(5)
    if (res && (res.code === 200 || res.code === 0) && res.data) {
      goodsRank.value = res.data
    }
  } catch (e) {
    console.error('加载商品排行失败:', e)
  } finally {
    goodsRankLoading.value = false
  }
}

const truncateTitle = (title: string, maxLen = 14): string => {
  if (!title) return '未知商品'
  return title.length > maxLen ? title.slice(0, maxLen) + '…' : title
}

// === 关键词命中排行 ===
const keywordRankLoading = ref(false)
const keywordRank = ref<KeywordRankData>({ items: [], totalHits: 0 })

const loadKeywordRank = async () => {
  keywordRankLoading.value = true
  try {
    const res = await getKeywordRank(undefined, 10)
    if (res && (res.code === 200 || res.code === 0) && res.data) {
      keywordRank.value = res.data
    }
  } catch (e) {
    console.error('加载关键词命中排行失败:', e)
  } finally {
    keywordRankLoading.value = false
  }
}

// === 操作健康度统计 ===
const operationStatsLoading = ref(false)
const operationStats = ref<OperationStatsData>({ types: [], totalOperations: 0, totalSuccess: 0, totalFail: 0, totalPartial: 0, successRate: 0 })

const loadOperationStats = async () => {
  operationStatsLoading.value = true
  try {
    const res = await getOperationStats(undefined, 10)
    if (res && (res.code === 200 || res.code === 0) && res.data) {
      operationStats.value = res.data
    }
  } catch (e) {
    console.error('加载操作健康度失败:', e)
  } finally {
    operationStatsLoading.value = false
  }
}

// 操作类型 → 中文标签
const operationTypeLabels: Record<string, string> = {
  LOGIN: '扫码登录',
  WEBSOCKET_CONNECT: 'WS连接',
  WEBSOCKET_DISCONNECT: 'WS断开',
  SEND_MESSAGE: '发送消息',
  RECEIVE_MESSAGE: '接收消息',
  AUTO_DELIVERY: '自动发货',
  AUTO_REPLY: '自动回复',
  CONFIRM_SHIPMENT: '确认收货',
  TOKEN_REFRESH: 'Token刷新',
  COOKIE_UPDATE: 'Cookie更新',
  GOODS_SYNC: '商品同步',
  MESSAGE_SYNC: '消息同步',
  RATE_LIMIT: '限流拦截',
  PUBLISH: '商品发布',
  OFF_SHELF: '商品下架'
}

const operationTypeLabel = (type: string): string => {
  return operationTypeLabels[type] || type
}

// 成功率颜色：>=95 绿，>=80 橙，否则红
const rateClass = (rate: number): string => {
  if (rate >= 95) return 'dp__health-rate--good'
  if (rate >= 80) return 'dp__health-rate--warn'
  return 'dp__health-rate--bad'
}

// 耗时格式化：<1000ms 显示 ms，否则 s
const formatDuration = (ms: number): string => {
  if (!ms || ms <= 0) return '-'
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// === 失败原因排行 ===
const failureRankLoading = ref(false)
const failureRank = ref<FailureRankData>({ items: [], totalFailures: 0 })

const loadFailureRank = async () => {
  failureRankLoading.value = true
  try {
    const res = await getFailureRank(undefined, 10)
    if (res && (res.code === 200 || res.code === 0) && res.data) {
      failureRank.value = res.data
    }
  } catch (e) {
    console.error('加载失败原因排行失败:', e)
  } finally {
    failureRankLoading.value = false
  }
}

// 失败原因关联操作类型 → 中文标签（逗号分隔）
const relatedTypeLabel = (types: string): string => {
  if (!types) return ''
  return types.split(',').filter(Boolean).map((t) => operationTypeLabel(t.trim())).join(' / ')
}

const formatAmount = (v: number): string => {
  if (!v) return '0.00'
  return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 排行最大值（用于进度条比例）
const rankMaxCount = (items: { count?: number; hitCount?: number; failCount?: number }[]): number => {
  return Math.max(...items.map((i) => i.count ?? i.hitCount ?? i.failCount ?? 1), 1)
}

// 排行榜序号样式（前三名奖牌色）
const rankNoClass = (i: number): string => {
  if (i === 0) return 'dp__rank-no--gold'
  if (i === 1) return 'dp__rank-no--silver'
  if (i === 2) return 'dp__rank-no--bronze'
  return ''
}

// === 多账号对比 ===
const accountCompareLoading = ref(false)
const accountCompare = ref<AccountCompareData>({ accounts: [], totalOrders: 0, totalAmount: 0, totalReplies: 0 })

const loadAccountCompare = async () => {
  accountCompareLoading.value = true
  try {
    const res = await getAccountCompare(selectedDate.value)
    if (res && (res.code === 200 || res.code === 0) && res.data) {
      accountCompare.value = res.data
    }
  } catch (e) {
    console.error('加载账号对比失败:', e)
  } finally {
    accountCompareLoading.value = false
  }
}

const compareMaxOrders = (): number => {
  return Math.max(...accountCompare.value.accounts.map((a) => a.orderCount), 1)
}

const formatCompareAmount = (v: number): string => {
  if (!v) return '0.00'
  return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

</script>

<template>
  <div class="dashboard">
    <!-- Tab 切换 -->
    <div class="dashboard__tabs">
      <button
        class="dashboard__tab"
        :class="{ 'dashboard__tab--active': activeTab === 'guide' }"
        @click="switchTab('guide')"
      >
        <IconRocket />
        <span>入门导航</span>
      </button>
      <button
        class="dashboard__tab"
        :class="{ 'dashboard__tab--active': activeTab === 'data' }"
        @click="switchTab('data')"
      >
        <IconChart />
        <span>数据面板</span>
      </button>
    </div>

    <!-- ====== 入门导航 ====== -->
    <template v-if="activeTab === 'guide'">
      <div :class="{ 'is-loading': dashboardLoading }">
        <!-- 统计卡片 -->
        <section class="stats-grid">
          <div v-for="card in statCards" :key="card.key" class="stat-card">
            <div class="stat-card__icon" :style="{ color: card.color }">
              <component :is="card.icon" />
            </div>
            <div class="stat-card__body">
              <span class="stat-card__value">{{ stats[card.key] }}</span>
              <span class="stat-card__label">{{ card.label }}</span>
            </div>
          </div>
        </section>

        <!-- 快速开始 -->
        <section class="section">
          <div class="section__header">
            <div class="section__icon"><IconRocket /></div>
            <div class="section__text">
              <h2 class="section__title">快速开始</h2>
              <p class="section__subtitle">4步完成系统配置，开启自动化之旅</p>
            </div>
          </div>
          <div class="steps-grid">
            <div
              v-for="(step, index) in quickStartSteps"
              :key="index"
              class="step-card"
              :class="{ 'step-card--active': activeStep === index }"
              @click="activeStep = index"
            >
              <div class="step-card__number">{{ index + 1 }}</div>
              <div class="step-card__icon"><component :is="step.icon" /></div>
              <h3 class="step-card__title">{{ step.title }}</h3>
              <p class="step-card__desc">{{ step.description }}</p>
              <ul class="step-card__details">
                <li v-for="(detail, idx) in step.details" :key="idx">{{ detail }}</li>
              </ul>
              <button class="step-card__btn" @click.stop="navigateTo(step.route)">
                <span>前往操作</span>
                <IconArrowRight />
              </button>
            </div>
          </div>
        </section>

        <!-- 功能特性 -->
        <section class="section">
          <div class="section__header">
            <div class="section__icon"><IconSparkle /></div>
            <div class="section__text">
              <h2 class="section__title">功能特性</h2>
              <p class="section__subtitle">强大的自动化功能，提升您的工作效率</p>
            </div>
          </div>
          <div class="features-grid">
            <div v-for="(feature, index) in features" :key="index" class="feature-card">
              <div class="feature-card__icon" :style="{ color: feature.color }">
                <component :is="feature.icon" />
              </div>
              <h3 class="feature-card__title">{{ feature.title }}</h3>
              <p class="feature-card__desc">{{ feature.description }}</p>
            </div>
          </div>
        </section>

        <!-- 常见问题 -->
        <section class="section">
          <div class="section__header">
            <div class="section__icon"><IconHelp /></div>
            <div class="section__text">
              <h2 class="section__title">常见问题</h2>
              <p class="section__subtitle">快速解答您的疑问</p>
            </div>
          </div>
          <div class="faq-list">
            <div
              v-for="(faq, index) in faqs"
              :key="index"
              class="faq-item"
              :class="{ 'faq-item--expanded': expandedFaq === index }"
            >
              <button class="faq-item__trigger" @click="toggleFaq(index)">
                <span class="faq-item__question">{{ faq.question }}</span>
                <div class="faq-item__chevron"><IconChevronDown /></div>
              </button>
              <div class="faq-item__body">
                <p class="faq-item__answer">{{ faq.answer }}</p>
              </div>
            </div>
          </div>
        </section>

        <!-- 温馨提示 -->
        <section class="section section--tips">
          <div class="section__header">
            <div class="section__icon section__icon--info"><IconInfo /></div>
            <div class="section__text">
              <h2 class="section__title">温馨提示</h2>
            </div>
          </div>
          <ul class="tips-list">
            <li v-for="(tip, index) in tips" :key="index">{{ tip }}</li>
          </ul>
        </section>
      </div>
    </template>

    <!-- ====== 数据面板 ====== -->
    <template v-if="activeTab === 'data'">
      <div class="dp" :aria-busy="dataPanelLoading">
        <div v-if="dataPanelLoading" class="dp__loading-overlay" role="status" aria-live="polite">
          <span class="dp__loading-spinner" aria-hidden="true"></span>
          <span>正在加载数据...</span>
        </div>
        <div class="dp__header">
          <h1 class="dp__title">数据面板</h1>
          <div class="dp__date-bar">
            <button
              class="dp__quick-btn"
              :class="{ 'dp__quick-btn--active': dateQuick === 'yesterday' }"
              :disabled="dataPanelLoading"
              @click="setQuickDate('yesterday')"
            >昨日</button>
            <button
              class="dp__quick-btn"
              :class="{ 'dp__quick-btn--active': dateQuick === 'today' }"
              :disabled="dataPanelLoading"
              @click="setQuickDate('today')"
            >
              今日
              <span v-if="dateQuick === 'today'" class="dp__live-dot"></span>
            </button>
            <input
              v-model="selectedDate"
              type="date"
              class="dp__date-input"
              :max="getToday()"
              :disabled="dataPanelLoading"
              aria-label="统计日期"
              @change="onDateChange"
            >
          </div>
        </div>

        <div class="dp__cards">
          <div class="dp__card">
            <div class="dp__card-icon dp__card-icon--blue"><IconPackage /></div>
            <div class="dp__card-body">
              <div class="dp__card-value">{{ dataPanelStats.orderCount }}</div>
              <div class="dp__card-label">订单数</div>
            </div>
          </div>
          <div class="dp__card">
            <div class="dp__card-icon dp__card-icon--green"><IconCheck /></div>
            <div class="dp__card-body">
              <div class="dp__card-value">{{ dataPanelStats.deliverySuccessCount }}</div>
              <div class="dp__card-label">发货成功</div>
            </div>
          </div>
          <div class="dp__card">
            <div class="dp__card-icon dp__card-icon--red"><IconAlert /></div>
            <div class="dp__card-body">
              <div class="dp__card-value">{{ dataPanelStats.deliveryFailCount }}</div>
              <div class="dp__card-label">发货失败</div>
            </div>
          </div>
          <div class="dp__card">
            <div class="dp__card-icon dp__card-icon--purple"><IconRobot /></div>
            <div class="dp__card-body">
              <div class="dp__card-value">{{ dataPanelStats.aiReplyCount }}</div>
              <div class="dp__card-label">AI回复</div>
            </div>
          </div>
        </div>

        <!-- 实时销售额 -->
        <div class="dp__revenue">
          <div class="dp__revenue-inner">
            <div class="dp__revenue-header">
              <div class="dp__revenue-label">
                <IconFire />
                <span>本助手为你打理销售额：</span>
              </div>
              <div class="dp__revenue-tip-wrap">
                <IconHelp class="dp__revenue-tip-icon" />
                <div class="dp__revenue-tooltip">发货成功 + 确认发货的订单金额总和，每3秒刷新</div>
              </div>
            </div>
            <div class="dp__revenue-value">
              <span class="dp__revenue-symbol">¥</span>
              <span class="dp__revenue-num">{{ revenueDisplay }}</span>
            </div>
          </div>
        </div>

        <!-- 销售额趋势图 -->
        <div class="dp__sales-chart-card" :aria-busy="salesLoading">
          <div v-if="salesLoading" class="dp__loading-overlay dp__loading-overlay--card" role="status" aria-live="polite">
            <span class="dp__loading-spinner" aria-hidden="true"></span>
            <span>正在加载销售趋势...</span>
          </div>
          <div class="dp__sales-header">
            <span class="dp__chart-title">销售额趋势</span>
            <div class="dp__sales-controls">
              <div class="dp__chart-toggle">
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': salesDimension === 'day' }]" :disabled="salesLoading" @click="switchSalesDimension('day')">日</button>
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': salesDimension === 'week' }]" :disabled="salesLoading" @click="switchSalesDimension('week')">周</button>
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': salesDimension === 'month' }]" :disabled="salesLoading" @click="switchSalesDimension('month')">月</button>
              </div>
            </div>
          </div>

          <div class="dp__sales-date-bar">
            <div class="dp__sales-quick-bar">
              <button v-for="opt in salesQuickOptions" :key="opt.key" :class="['dp__quick-btn', { 'dp__quick-btn--active': salesQuickRange === opt.key }]" :disabled="salesLoading" @click="setSalesQuickRange(opt.key)">{{ opt.label }}</button>
            </div>
            <input
              v-model="salesStartDate"
              type="date"
              class="dp__date-input"
              :max="salesEndDate || getToday()"
              :disabled="salesLoading"
              aria-label="销售趋势开始日期"
              @change="onSalesDateChange"
            >
            <span class="dp__date-sep">~</span>
            <input
              v-model="salesEndDate"
              type="date"
              class="dp__date-input"
              :min="salesStartDate"
              :max="getToday()"
              :disabled="salesLoading"
              aria-label="销售趋势结束日期"
              @change="onSalesDateChange"
            >
            <span v-if="salesDateError" class="dp__date-error" role="alert">{{ salesDateError }}</span>
          </div>

          <div class="dp__sales-summary">
            <span class="dp__sales-summary-label">区间销售额合计</span>
            <span class="dp__sales-summary-value">¥{{ salesTotal.toFixed(2) }}</span>
          </div>

          <!-- 折线图 -->
          <div class="dp__sales-line-chart dp__chart-tooltip-zone">
            <div class="dp__sales-chart-scroll">
              <svg :viewBox="salesViewBox" class="dp__line-svg" :style="salesNeedScroll ? { minWidth: salesDrawWidth + 'px' } : {}">
                <!-- Y轴刻度 -->
                <text v-for="(tick, ti) in salesNiceScale.ticks" :key="'sy'+ti" :x="SALES_CHART_PAD_LEFT - 4" :y="getSalesY(tick, salesNiceScale.niceMax) + 3" text-anchor="end" fill="var(--text-3)" font-size="8" class="dp__y-tick">{{ formatTickValue(tick) }}</text>
                <line v-for="(tick, ti) in salesNiceScale.ticks" :key="'sgh'+ti" :x1="SALES_CHART_PAD_LEFT" :y1="getSalesY(tick, salesNiceScale.niceMax)" :x2="salesSvgWidth - SALES_CHART_PAD_RIGHT" :y2="getSalesY(tick, salesNiceScale.niceMax)" stroke="var(--border-1)" stroke-width="1" />
                <text v-for="(label, i) in salesData.labels" :key="'slx'+i" v-show="isSalesLabelVisible(i)" :x="getSalesX(i)" y="170" text-anchor="middle" fill="var(--text-3)" font-size="8">{{ label }}</text>
                <path :d="generateSalesPath(salesData.values, salesNiceScale.niceMax)" fill="none" stroke="var(--warning-500)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <circle v-for="(v, i) in salesData.values" :key="'svc'+i" :cx="getSalesX(i)" :cy="getSalesY(v, salesNiceScale.niceMax)" r="6" fill="var(--warning-500)" fill-opacity="0" stroke="none" class="dp__chart-hitarea" @mouseenter="(e: any) => showTooltip(e, 'sales', salesData.labels[i] || '', [{ color: 'var(--warning-500)', text: '¥' + v.toFixed(2) }])" @mouseleave="hideTooltip" />
                <circle v-for="(v, i) in salesData.values" :key="'svd'+i" :cx="getSalesX(i)" :cy="getSalesY(v, salesNiceScale.niceMax)" r="3" fill="var(--warning-500)" pointer-events="none" />
                <text v-for="(v, i) in salesData.values" :key="'svl'+i" v-show="v > 0" :x="getSalesX(i)" :y="getSalesY(v, salesNiceScale.niceMax) - 6" text-anchor="middle" fill="var(--warning-500)" font-size="8" class="dp__data-label">{{ v >= 100 ? formatTickValue(v) : v.toFixed(0) }}</text>
              </svg>
            </div>
            <div class="dp__legend">
              <span class="dp__legend-item"><span class="dp__legend-dot dp__legend-dot--orange"></span>销售额</span>
            </div>
            <div v-if="tooltip.visible && tooltip.chartId === 'sales'" class="dp__chart-tooltip" :style="{ left: tooltip.x + 'px', top: tooltip.y + 'px' }">
              <div class="dp__chart-tooltip-label">{{ tooltip.label }}</div>
              <div v-for="(line, li) in tooltip.lines" :key="li" class="dp__chart-tooltip-row">
                <span class="dp__chart-tooltip-dot" :style="{ background: line.color }"></span>
                <span>{{ line.text }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="dp__charts">
          <!-- 发货情况 -->
          <div class="dp__chart-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">发货情况</span>
              <div class="dp__chart-toggle">
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': deliveryChartMode === 'bar' }]" @click="deliveryChartMode = 'bar'">柱状</button>
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': deliveryChartMode === 'line' }]" @click="deliveryChartMode = 'line'">折线</button>
              </div>
            </div>

            <!-- 柱状图 -->
            <div v-if="deliveryChartMode === 'bar'" class="dp__bar-chart">
              <div class="dp__bar-group">
                <div class="dp__bar-wrap">
                  <div class="dp__bar dp__bar--success" :style="{ height: deliveryTotal > 0 ? (dataPanelStats.deliverySuccessCount / deliveryTotal * 100) + '%' : '0%' }"></div>
                </div>
                <span class="dp__bar-label">成功</span>
                <span class="dp__bar-count">{{ dataPanelStats.deliverySuccessCount }}</span>
              </div>
              <div class="dp__bar-group">
                <div class="dp__bar-wrap">
                  <div class="dp__bar dp__bar--fail" :style="{ height: deliveryTotal > 0 ? (dataPanelStats.deliveryFailCount / deliveryTotal * 100) + '%' : '0%' }"></div>
                </div>
                <span class="dp__bar-label">失败</span>
                <span class="dp__bar-count">{{ dataPanelStats.deliveryFailCount }}</span>
              </div>
            </div>
            <div v-if="deliveryChartMode === 'bar'" class="dp__chart-footer">
              <span>成功率 <strong>{{ successRate }}%</strong></span>
            </div>

            <!-- 折线图 -->
            <div v-if="deliveryChartMode === 'line'" class="dp__line-chart dp__chart-tooltip-zone">
              <svg viewBox="0 0 640 180" class="dp__line-svg">
                <!-- Y轴刻度 -->
                <text v-for="(tick, ti) in deliveryNiceScale.ticks" :key="'dy'+ti" x="26" :y="getLineY(tick, deliveryNiceScale.niceMax) + 3" text-anchor="end" fill="var(--text-3)" font-size="9" class="dp__y-tick">{{ formatTickValue(tick) }}</text>
                <line v-for="(tick, ti) in deliveryNiceScale.ticks" :key="'gh'+ti" x1="30" :y1="getLineY(tick, deliveryNiceScale.niceMax)" x2="610" :y2="getLineY(tick, deliveryNiceScale.niceMax)" stroke="var(--border-1)" stroke-width="1" />
                <text v-for="(d, i) in trendData.dates" :key="'lx'+i" :x="getLineX(i, trendData.dates.length)" y="170" text-anchor="middle" fill="var(--text-3)" font-size="10">{{ d }}</text>
                <path :d="generatePath(trendData.deliverySuccess, deliveryNiceScale.niceMax)" fill="none" stroke="var(--success-500)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <path :d="generatePath(trendData.deliveryFail, deliveryNiceScale.niceMax)" fill="none" stroke="var(--danger-500)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <circle v-for="(v, i) in trendData.deliverySuccess" :key="'sc'+i" :cx="getLineX(i, trendData.dates.length)" :cy="getLineY(v, deliveryNiceScale.niceMax)" r="6" fill="var(--success-500)" fill-opacity="0" stroke="none" class="dp__chart-hitarea" @mouseenter="(e: any) => showTooltip(e, 'delivery', trendData.dates[i] || '', [{ color: 'var(--success-500)', text: '成功: ' + v }, { color: 'var(--danger-500)', text: '失败: ' + trendData.deliveryFail[i] }])" @mouseleave="hideTooltip" />
                <circle v-for="(v, i) in trendData.deliverySuccess" :key="'scd'+i" :cx="getLineX(i, trendData.dates.length)" :cy="getLineY(v, deliveryNiceScale.niceMax)" r="3" fill="var(--success-500)" pointer-events="none" />
                <circle v-for="(v, i) in trendData.deliveryFail" :key="'fc'+i" :cx="getLineX(i, trendData.dates.length)" :cy="getLineY(v, deliveryNiceScale.niceMax)" r="3" fill="var(--danger-500)" pointer-events="none" />
                <text v-for="(v, i) in trendData.deliverySuccess" :key="'scl'+i" v-show="v > 0" :x="getLineX(i, trendData.dates.length)" :y="getLineY(v, deliveryNiceScale.niceMax) - 6" text-anchor="middle" fill="var(--success-500)" font-size="8" class="dp__data-label">{{ v }}</text>
                <text v-for="(v, i) in trendData.deliveryFail" :key="'fcl'+i" v-show="v > 0" :x="getLineX(i, trendData.dates.length) + 12" :y="getLineY(v, deliveryNiceScale.niceMax) + 3" text-anchor="start" fill="var(--danger-500)" font-size="8" class="dp__data-label">{{ v }}</text>
              </svg>
              <div class="dp__legend">
                <span class="dp__legend-item"><span class="dp__legend-dot dp__legend-dot--green"></span>发货成功</span>
                <span class="dp__legend-item"><span class="dp__legend-dot dp__legend-dot--red"></span>发货失败</span>
              </div>
              <div v-if="tooltip.visible && tooltip.chartId === 'delivery'" class="dp__chart-tooltip" :style="{ left: tooltip.x + 'px', top: tooltip.y + 'px' }">
                <div class="dp__chart-tooltip-label">{{ tooltip.label }}</div>
                <div v-for="(line, li) in tooltip.lines" :key="li" class="dp__chart-tooltip-row">
                  <span class="dp__chart-tooltip-dot" :style="{ background: line.color }"></span>
                  <span>{{ line.text }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- AI回复 -->
          <div class="dp__chart-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">AI自动回复</span>
              <div class="dp__chart-toggle">
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': aiChartMode === 'bar' }]" @click="aiChartMode = 'bar'">柱状</button>
                <button :class="['dp__toggle-btn', { 'dp__toggle-btn--active': aiChartMode === 'line' }]" @click="aiChartMode = 'line'">折线</button>
              </div>
            </div>

            <!-- 环形图 -->
            <div v-if="aiChartMode === 'bar'" class="dp__ring-chart">
              <svg viewBox="0 0 120 120" class="dp__ring-svg">
                <circle cx="60" cy="60" r="48" fill="none" stroke="var(--border-1)" stroke-width="10" />
                <circle cx="60" cy="60" r="48" fill="none" stroke="var(--purple-500)" stroke-width="10" stroke-linecap="round" :stroke-dasharray="302" :stroke-dashoffset="302 - (dataPanelStats.aiReplyCount > 0 ? 302 : 0)" transform="rotate(-90 60 60)" />
              </svg>
              <div class="dp__ring-center">
                <div class="dp__ring-value">{{ dataPanelStats.aiReplyCount }}</div>
                <div class="dp__ring-label">条回复</div>
              </div>
            </div>
            <div v-if="aiChartMode === 'bar'" class="dp__chart-footer">
              <span>当日AI自动回复条数</span>
            </div>

            <!-- 折线图 -->
            <div v-if="aiChartMode === 'line'" class="dp__line-chart dp__chart-tooltip-zone">
              <svg viewBox="0 0 640 180" class="dp__line-svg">
                <!-- Y轴刻度 -->
                <text v-for="(tick, ti) in aiNiceScale.ticks" :key="'ay'+ti" x="26" :y="getLineY(tick, aiNiceScale.niceMax) + 3" text-anchor="end" fill="var(--text-3)" font-size="9" class="dp__y-tick">{{ formatTickValue(tick) }}</text>
                <line v-for="(tick, ti) in aiNiceScale.ticks" :key="'gh2'+ti" x1="30" :y1="getLineY(tick, aiNiceScale.niceMax)" x2="610" :y2="getLineY(tick, aiNiceScale.niceMax)" stroke="var(--border-1)" stroke-width="1" />
                <text v-for="(d, i) in trendData.dates" :key="'lx2'+i" :x="getLineX(i, trendData.dates.length)" y="170" text-anchor="middle" fill="var(--text-3)" font-size="10">{{ d }}</text>
                <path :d="generatePath(trendData.aiReplies, aiNiceScale.niceMax)" fill="none" stroke="var(--purple-500)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <circle v-for="(v, i) in trendData.aiReplies" :key="'ac'+i" :cx="getLineX(i, trendData.dates.length)" :cy="getLineY(v, aiNiceScale.niceMax)" r="6" fill="var(--purple-500)" fill-opacity="0" stroke="none" class="dp__chart-hitarea" @mouseenter="(e: any) => showTooltip(e, 'ai', trendData.dates[i] || '', [{ color: 'var(--purple-500)', text: 'AI回复: ' + v }])" @mouseleave="hideTooltip" />
                <circle v-for="(v, i) in trendData.aiReplies" :key="'acd'+i" :cx="getLineX(i, trendData.dates.length)" :cy="getLineY(v, aiNiceScale.niceMax)" r="3" fill="var(--purple-500)" pointer-events="none" />
                <text v-for="(v, i) in trendData.aiReplies" :key="'acl'+i" v-show="v > 0" :x="getLineX(i, trendData.dates.length)" :y="getLineY(v, aiNiceScale.niceMax) - 6" text-anchor="middle" fill="var(--purple-500)" font-size="8" class="dp__data-label">{{ v }}</text>
              </svg>
              <div class="dp__legend">
                <span class="dp__legend-item"><span class="dp__legend-dot dp__legend-dot--purple"></span>AI回复数</span>
              </div>
              <div v-if="tooltip.visible && tooltip.chartId === 'ai'" class="dp__chart-tooltip" :style="{ left: tooltip.x + 'px', top: tooltip.y + 'px' }">
                <div class="dp__chart-tooltip-label">{{ tooltip.label }}</div>
                <div v-for="(line, li) in tooltip.lines" :key="li" class="dp__chart-tooltip-row">
                  <span class="dp__chart-tooltip-dot" :style="{ background: line.color }"></span>
                  <span>{{ line.text }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 商品排行 -->
        <div class="dp__rank">
          <!-- 成交 TOP -->
          <div class="dp__chart-card dp__rank-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">成交 TOP 商品</span>
              <span class="dp__rank-badge dp__rank-badge--success">按订单数</span>
            </div>
            <div v-if="goodsRankLoading" class="dp__rank-empty">加载中…</div>
            <div v-else-if="!goodsRank.orderRank.length" class="dp__rank-empty">暂无成交数据</div>
            <div v-else class="dp__rank-list">
              <div v-for="(item, i) in goodsRank.orderRank" :key="'or' + item.goodsId" class="dp__rank-row">
                <span class="dp__rank-no" :class="rankNoClass(i)">{{ i + 1 }}</span>
                <div class="dp__rank-main">
                  <div class="dp__rank-row-head">
                    <span class="dp__rank-name" :title="item.goodsTitle">{{ truncateTitle(item.goodsTitle) }}</span>
                    <span class="dp__rank-count">{{ item.count }} 单</span>
                  </div>
                  <div class="dp__bar-track">
                    <div class="dp__bar-fill dp__bar-fill--rank" :style="{ width: (item.count / rankMaxCount(goodsRank.orderRank) * 100) + '%' }"></div>
                  </div>
                  <div class="dp__rank-row-foot">
                    <span class="dp__rank-amount">¥{{ formatAmount(item.amount) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 咨询 TOP -->
          <div class="dp__chart-card dp__rank-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">咨询 TOP 商品</span>
              <span class="dp__rank-badge dp__rank-badge--warning">按消息数</span>
            </div>
            <div v-if="goodsRankLoading" class="dp__rank-empty">加载中…</div>
            <div v-else-if="!goodsRank.inquiryRank.length" class="dp__rank-empty">暂无咨询数据</div>
            <div v-else class="dp__rank-list">
              <div v-for="(item, i) in goodsRank.inquiryRank" :key="'ir' + item.goodsId" class="dp__rank-row">
                <span class="dp__rank-no" :class="rankNoClass(i)">{{ i + 1 }}</span>
                <div class="dp__rank-main">
                  <div class="dp__rank-row-head">
                    <span class="dp__rank-name" :title="item.goodsTitle">{{ truncateTitle(item.goodsTitle) }}</span>
                    <span class="dp__rank-count">{{ item.count }} 条</span>
                  </div>
                  <div class="dp__bar-track">
                    <div class="dp__bar-fill dp__bar-fill--rank dp__bar-fill--warning" :style="{ width: (item.count / rankMaxCount(goodsRank.inquiryRank) * 100) + '%' }"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 关键词命中 TOP -->
          <div class="dp__chart-card dp__rank-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">关键词命中 TOP</span>
              <span class="dp__rank-badge dp__rank-badge--info">自动回复</span>
            </div>
            <div v-if="keywordRankLoading" class="dp__rank-empty">加载中…</div>
            <div v-else-if="!keywordRank.items.length" class="dp__rank-empty">暂无关键词命中数据</div>
            <div v-else>
              <div class="dp__compare-total">
                <span>总命中 <strong>{{ keywordRank.totalHits }}</strong></span>
              </div>
              <div class="dp__rank-list">
                <div v-for="(item, i) in keywordRank.items" :key="'kr' + i" class="dp__rank-row">
                  <span class="dp__rank-no" :class="rankNoClass(i)">{{ i + 1 }}</span>
                  <div class="dp__rank-main">
                    <div class="dp__rank-row-head">
                      <span class="dp__rank-name" :title="item.keyword">{{ truncateTitle(item.keyword) }}</span>
                      <span class="dp__rank-count">{{ item.hitCount }} 次</span>
                    </div>
                    <div class="dp__bar-track">
                      <div class="dp__bar-fill dp__bar-fill--rank dp__bar-fill--info" :style="{ width: (item.hitCount / rankMaxCount(keywordRank.items) * 100) + '%' }"></div>
                    </div>
                    <div class="dp__compare-meta">
                      <span class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--success"></i>{{ item.successCount }} 成功</span>
                      <span v-if="item.failCount > 0" class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--fail"></i>{{ item.failCount }} 失败</span>
                      <span v-if="item.pendingCount > 0" class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--pending"></i>{{ item.pendingCount }} 待回复</span>
                      <span class="dp__compare-meta-item dp__compare-meta-amount">{{ item.buyerCount }} 买家</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 操作健康度 -->
          <div class="dp__chart-card dp__rank-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">操作健康度</span>
              <span class="dp__rank-badge dp__rank-badge--success">成功率 {{ operationStats.successRate }}%</span>
            </div>
            <div v-if="operationStatsLoading" class="dp__rank-empty">加载中…</div>
            <div v-else-if="!operationStats.types.length" class="dp__rank-empty">暂无操作数据</div>
            <div v-else>
              <div class="dp__compare-total">
                <span>操作 <strong>{{ operationStats.totalOperations }}</strong></span>
                <span>成功 <strong class="dp__health-ok">{{ operationStats.totalSuccess }}</strong></span>
                <span v-if="operationStats.totalFail > 0">失败 <strong class="dp__health-fail">{{ operationStats.totalFail }}</strong></span>
              </div>
              <div class="dp__rank-list">
                <div v-for="(item, i) in operationStats.types" :key="'op' + i" class="dp__rank-row">
                  <span class="dp__rank-no" :class="rankNoClass(i)">{{ i + 1 }}</span>
                  <div class="dp__rank-main">
                    <div class="dp__rank-row-head">
                      <span class="dp__rank-name" :title="item.type">{{ operationTypeLabel(item.type) }}</span>
                      <span class="dp__rank-count">{{ item.totalCount }} 次</span>
                    </div>
                    <div class="dp__bar-track">
                      <div class="dp__bar-fill dp__bar-fill--rank" :style="{ width: (item.successCount / Math.max(item.totalCount, 1) * 100) + '%' }"></div>
                    </div>
                    <div class="dp__compare-meta">
                      <span class="dp__compare-meta-item"><span class="dp__health-rate" :class="rateClass(item.successRate)">{{ item.successRate }}%</span></span>
                      <span v-if="item.failCount > 0" class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--fail"></i>{{ item.failCount }} 失败</span>
                      <span v-if="item.partialCount > 0" class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--pending"></i>{{ item.partialCount }} 部分</span>
                      <span class="dp__compare-meta-item dp__compare-meta-amount">均 {{ formatDuration(item.avgDurationMs) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 失败原因 TOP -->
          <div class="dp__chart-card dp__rank-card">
            <div class="dp__chart-head">
              <span class="dp__chart-title">失败原因 TOP</span>
              <span class="dp__rank-badge dp__rank-badge--danger">诊断</span>
            </div>
            <div v-if="failureRankLoading" class="dp__rank-empty">加载中…</div>
            <div v-else-if="!failureRank.items.length" class="dp__rank-empty">当日无失败记录</div>
            <div v-else>
              <div class="dp__compare-total">
                <span>失败 <strong class="dp__health-fail">{{ failureRank.totalFailures }}</strong></span>
              </div>
              <div class="dp__rank-list">
                <div v-for="(item, i) in failureRank.items" :key="'fr' + i" class="dp__rank-row">
                  <span class="dp__rank-no" :class="rankNoClass(i)">{{ i + 1 }}</span>
                  <div class="dp__rank-main">
                    <div class="dp__rank-row-head">
                      <span class="dp__rank-name" :title="item.reason">{{ truncateTitle(item.reason, 18) }}</span>
                      <span class="dp__rank-count">{{ item.failCount }} 次</span>
                    </div>
                    <div class="dp__bar-track">
                      <div class="dp__bar-fill dp__bar-fill--rank dp__bar-fill--danger" :style="{ width: (item.failCount / rankMaxCount(failureRank.items) * 100) + '%' }"></div>
                    </div>
                    <div class="dp__rank-row-foot">
                      <span v-if="item.relatedTypes" class="dp__failure-types">{{ relatedTypeLabel(item.relatedTypes) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="dp__chart-card dp__compare-card">
          <div class="dp__chart-head">
            <span class="dp__chart-title">账号对比</span>
            <span class="dp__rank-badge dp__rank-badge--info">按订单数</span>
          </div>
          <div v-if="accountCompareLoading" class="dp__rank-empty">加载中…</div>
          <div v-else-if="!accountCompare.accounts.length" class="dp__rank-empty">该日期暂无账号数据</div>
          <div v-else>
            <div class="dp__compare-total">
              <span>订单 <strong>{{ accountCompare.totalOrders }}</strong></span>
              <span>销售额 <strong class="dp__compare-total-amount">¥{{ formatCompareAmount(accountCompare.totalAmount) }}</strong></span>
              <span>AI回复 <strong>{{ accountCompare.totalReplies }}</strong></span>
            </div>
            <div class="dp__rank-list">
              <div v-for="(acc, i) in accountCompare.accounts" :key="acc.accountId" class="dp__rank-row">
                <span class="dp__rank-no" :class="rankNoClass(i)">{{ i + 1 }}</span>
                <div class="dp__rank-main">
                  <div class="dp__rank-row-head">
                    <span class="dp__rank-name" :title="acc.accountName">{{ truncateTitle(acc.accountName) }}</span>
                    <span class="dp__rank-count">{{ acc.orderCount }} 单</span>
                  </div>
                  <div class="dp__bar-track">
                    <div class="dp__bar-fill dp__bar-fill--rank dp__bar-fill--info" :style="{ width: (acc.orderCount / compareMaxOrders() * 100) + '%' }"></div>
                  </div>
                  <div class="dp__compare-meta">
                    <span class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--success"></i>成功 {{ acc.successCount }}</span>
                    <span class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--fail"></i>失败 {{ acc.failCount }}</span>
                    <span class="dp__compare-meta-item"><i class="dp__compare-dot dp__compare-dot--purple"></i>回复 {{ acc.replyCount }}</span>
                    <span class="dp__compare-meta-item dp__compare-meta-amount">¥{{ formatCompareAmount(acc.amount) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped src="./dashboard.css"></style>

<style scoped>
/* Dashboard tabs + data panel (tokenized) */
.dashboard__tabs {
  display: flex;
  width: fit-content;
  gap: 2px;
  margin: 0 0 16px;
  padding: 3px;
  background: var(--bg-muted);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
}

.dashboard__tab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border: none;
  border-radius: calc(var(--radius-sm) - 2px);
  background: transparent;
  color: var(--text-2);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.dashboard__tab svg {
  width: 16px;
  height: 16px;
}

.dashboard__tab--active {
  color: var(--text-1);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-xs);
}

.dashboard__tab:hover:not(.dashboard__tab--active) {
  color: var(--text-1);
  background: var(--bg-hover);
}

.dp { position: relative; }

.dp__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.dp__title {
  margin: 0;
  color: var(--text-1);
  font-size: 22px;
  font-weight: var(--weight-bold);
}

.dp__subtitle {
  display: block;
  margin-top: 4px;
  color: var(--text-2);
  font-size: var(--text-md);
}

.dp__loading-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 160px;
  color: var(--text-2);
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  background: color-mix(in srgb, var(--bg-app) 88%, transparent);
  border-radius: var(--radius-sm);
}

.dp__loading-overlay--card { border-radius: var(--radius-sm); }

.dp__loading-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border-2);
  border-top-color: var(--brand-600);
  border-radius: 50%;
  animation: dp-spin 0.7s linear infinite;
}

@keyframes dp-spin {
  to { transform: rotate(360deg); }
}

.dp__date-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.dp__quick-btn,
.dp__export-btn,
.dp__toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 30px;
  padding: 5px 12px;
  color: var(--text-2);
  font-size: var(--text-md);
  background: color-mix(in srgb, var(--glass-bg-soft) 82%, transparent);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--glass-edge-glow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
  cursor: pointer;
  transition:
    background-color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.dp__quick-btn:hover,
.dp__export-btn:hover,
.dp__toggle-btn:hover:not(.dp__toggle-btn--active) {
  color: var(--text-1);
  border-color: var(--glass-border-strong);
  background: var(--glass-bg-strong);
  box-shadow: var(--glass-shadow-float);
}

.dp__quick-btn--active {
  color: var(--brand-700);
  background: var(--brand-50);
  border-color: var(--brand-200);
  box-shadow: 0 4px 14px color-mix(in srgb, var(--brand-500) 22%, transparent), inset 0 1px 0 var(--glass-highlight-strong);
}

.dp__quick-btn:disabled,
.dp__toggle-btn:disabled,
.dp__export-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.dp__live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--success-500);
  display: inline-block;
  animation: dp-pulse 1.5s infinite;
}

@keyframes dp-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.dp__date-input {
  width: 140px;
  height: 30px;
  padding: 0 8px;
  color: var(--text-1);
  font: inherit;
  font-size: var(--text-sm);
  color-scheme: light dark;
  background: color-mix(in srgb, var(--glass-bg-soft) 82%, transparent);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  outline: none;
  box-sizing: border-box;
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.dp__date-input:focus {
  border-color: var(--brand-600);
  background: var(--glass-bg);
  box-shadow: 0 0 0 3px var(--ring-brand), var(--glass-inner-shadow);
}

.dp__date-input:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.dp__cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.dp__card,
.dp__chart-card,
.dp__sales-chart-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-xs);
}

.dp__chart-card,
.dp__sales-chart-card {
  display: block;
}

.dp__card-icon {
  width: 38px;
  height: 38px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.dp__card-icon svg {
  width: 20px;
  height: 20px;
  color: #fff;
}

.dp__card-icon--blue {
  background: linear-gradient(135deg, var(--brand-600), var(--sky-500));
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.24), inset 0 1px 0 rgba(255, 255, 255, 0.2);
}
.dp__card-icon--green {
  background: linear-gradient(135deg, var(--success-500), var(--teal-500));
  box-shadow: 0 8px 18px rgba(22, 163, 74, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.2);
}
.dp__card-icon--red {
  background: linear-gradient(135deg, var(--danger-500), var(--rose-500));
  box-shadow: 0 8px 18px rgba(220, 38, 38, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.2);
}
.dp__card-icon--purple {
  background: linear-gradient(135deg, var(--purple-500), var(--violet-400));
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.dp__card-value {
  font-size: 28px;
  font-weight: var(--weight-bold);
  color: var(--text-1);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.dp__card-label {
  margin-top: 4px;
  font-size: var(--text-md);
  color: var(--text-2);
}

.dp__revenue { margin-bottom: 16px; }

.dp__revenue-inner {
  min-height: 124px;
  padding: 22px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: relative;
  overflow: hidden;
  background: var(--warning-50);
  border: 1px solid color-mix(in srgb, var(--warning-500) 28%, transparent);
  border-left: 4px solid var(--warning-600);
  border-radius: var(--radius-md);
}

.dp__revenue-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dp__revenue-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--warning-600);
  font-size: 14px;
  font-weight: var(--weight-medium);
}

.dp__revenue-label svg {
  width: 18px;
  height: 18px;
  color: var(--warning-500);
}

.dp__revenue-tip-wrap { position: relative; cursor: pointer; }

.dp__revenue-tip-icon {
  width: 16px;
  height: 16px;
  color: color-mix(in srgb, var(--warning-600) 70%, transparent);
  transition: color var(--duration-fast) var(--ease-standard);
}

.dp__revenue-tip-wrap:hover .dp__revenue-tip-icon {
  color: var(--warning-600);
}

.dp__revenue-tooltip {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  padding: 8px 12px;
  color: var(--text-1);
  font-size: var(--text-sm);
  white-space: nowrap;
  background: var(--glass-bg-strong);
  border: 1px solid var(--glass-border-strong);
  border-radius: var(--radius-sm);
  box-shadow: var(--glass-shadow-float), inset 0 1px 0 var(--glass-highlight-strong);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  opacity: 0;
  visibility: hidden;
  transition: opacity var(--duration-fast) var(--ease-standard), visibility var(--duration-fast) var(--ease-standard);
  z-index: 10;
}

.dp__revenue-tip-wrap:hover .dp__revenue-tooltip {
  opacity: 1;
  visibility: visible;
}

.dp__revenue-value {
  display: flex;
  align-items: baseline;
  gap: 4px;
  position: relative;
  z-index: 1;
}

.dp__revenue-symbol {
  font-size: 22px;
  font-weight: var(--weight-semibold);
  color: var(--warning-600);
}

.dp__revenue-num {
  font-size: 36px;
  font-weight: 800;
  color: var(--warning-600);
  font-variant-numeric: tabular-nums;
}

.dp__charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.dp__chart-title {
  font-size: 16px;
  font-weight: var(--weight-semibold);
  color: var(--text-1);
}

.dp__chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.dp__chart-toggle {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  background: color-mix(in srgb, var(--glass-bg-soft) 84%, transparent);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--glass-edge-glow);
  -webkit-backdrop-filter: var(--glass-blur-sm);
  backdrop-filter: var(--glass-blur-sm);
}

.dp__toggle-btn {
  min-height: 28px;
  padding: 0 10px;
  border: none;
  background: transparent;
}

.dp__toggle-btn--active {
  color: #fff;
  background: var(--gradient-brand);
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.dp__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  margin-bottom: 10px;
}

.dp__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
  font-size: var(--text-sm);
}

.dp__legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-500);
}

.dp__legend-dot--orange { background: var(--warning-500); }
.dp__legend-dot--green { background: var(--success-500); }
.dp__legend-dot--red { background: var(--danger-500); }
.dp__legend-dot--purple { background: var(--purple-500); }

.dp__bar-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dp__bar-row {
  display: grid;
  grid-template-columns: 88px 1fr 48px;
  align-items: center;
  gap: 10px;
}

.dp__bar-label {
  color: var(--text-2);
  font-size: var(--text-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dp__bar-track {
  height: 8px;
  background: var(--bg-muted);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.dp__bar-fill {
  height: 100%;
  background: var(--brand-500);
  border-radius: inherit;
}

.dp__bar-count {
  color: var(--text-1);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.dp__rank {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 16px;
}

.dp__rank-badge {
  flex-shrink: 0;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  white-space: nowrap;
}

.dp__rank-badge--success {
  color: var(--success-600);
  background: color-mix(in srgb, var(--success-500) 12%, transparent);
}

.dp__rank-badge--warning {
  color: var(--warning-600);
  background: color-mix(in srgb, var(--warning-500) 12%, transparent);
}

.dp__rank-badge--info {
  color: var(--brand-600);
  background: color-mix(in srgb, var(--brand-500) 12%, transparent);
}

.dp__rank-badge--danger {
  color: var(--danger-600);
  background: color-mix(in srgb, var(--danger-500) 12%, transparent);
}

.dp__compare-card {
  margin-top: 16px;
}

.dp__compare-total {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  padding: 10px 12px;
  margin-bottom: 14px;
  background: var(--bg-muted);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--text-2);
}

.dp__compare-total strong {
  color: var(--text-1);
  font-weight: var(--weight-semibold);
  font-variant-numeric: tabular-nums;
}

.dp__compare-total-amount {
  color: var(--warning-600) !important;
}

.dp__bar-fill--rank.dp__bar-fill--info {
  background: var(--brand-600);
}

.dp__compare-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 6px;
}

.dp__compare-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-xs);
  color: var(--text-2);
  font-variant-numeric: tabular-nums;
}

.dp__compare-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dp__compare-dot--success { background: var(--success-500); }
.dp__compare-dot--fail { background: var(--danger-500); }
.dp__compare-dot--purple { background: var(--purple-500); }
.dp__compare-dot--pending { background: var(--warning-500); }

.dp__health-rate {
  font-weight: var(--weight-semibold);
  font-variant-numeric: tabular-nums;
}
.dp__health-rate--good { color: var(--success-600); }
.dp__health-rate--warn { color: var(--warning-600); }
.dp__health-rate--bad { color: var(--danger-600); }

.dp__health-ok { color: var(--success-600); }
.dp__health-fail { color: var(--danger-600); }

.dp__compare-meta-amount {
  color: var(--warning-600);
  font-weight: var(--weight-semibold);
}

.dp__rank-empty {
  padding: 28px 0;
  text-align: center;
  color: var(--text-3);
  font-size: var(--text-sm);
}

.dp__rank-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.dp__rank-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.dp__rank-no {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-xs);
  background: var(--bg-muted);
  border: 1px solid var(--border-1);
  color: var(--text-2);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  font-variant-numeric: tabular-nums;
}

.dp__rank-no--gold {
  color: #fff;
  background: #d97706;
  border-color: transparent;
}

.dp__rank-no--silver {
  color: #fff;
  background: #64748b;
  border-color: transparent;
}

.dp__rank-no--bronze {
  color: #fff;
  background: #b45309;
  border-color: transparent;
}

.dp__rank-main {
  flex: 1;
  min-width: 0;
}

.dp__rank-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.dp__rank-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-1);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
}

.dp__rank-count {
  flex-shrink: 0;
  color: var(--text-1);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  font-variant-numeric: tabular-nums;
}

.dp__bar-fill--rank {
  background: var(--success-600);
}

.dp__bar-fill--rank.dp__bar-fill--warning {
  background: var(--warning-600);
}

.dp__bar-fill--rank.dp__bar-fill--danger {
  background: var(--danger-500);
}

.dp__failure-types {
  font-size: var(--text-xs);
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dp__rank-row-foot {
  margin-top: 5px;
}

.dp__rank-amount {
  color: var(--warning-600);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  font-variant-numeric: tabular-nums;
}

.dp__ring-wrap {
  display: flex;
  align-items: center;
  gap: 16px;
}

.dp__ring-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dp__ring-value {
  color: var(--text-1);
  font-size: 24px;
  font-weight: var(--weight-bold);
}

.dp__ring-label {
  color: var(--text-2);
  font-size: var(--text-sm);
}

.dp__chart-footer {
  margin-top: 12px;
  color: var(--text-2);
  font-size: var(--text-sm);
}

.dp__chart-footer strong {
  color: var(--text-1);
  font-weight: var(--weight-semibold);
}

.dp__sales-chart-card { margin-bottom: 16px; }

.dp__sales-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.dp__sales-controls,
.dp__sales-date-bar,
.dp__sales-quick-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dp__sales-date-bar {
  margin-bottom: 16px;
}

.dp__date-sep {
  color: var(--text-2);
  font-size: var(--text-md);
}

.dp__date-error {
  width: 100%;
  color: var(--danger-600);
  font-size: var(--text-sm);
  line-height: 1.4;
}

.dp__sales-summary {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 16px;
  padding: 10px 14px;
  background: var(--warning-50);
  border: 1px solid color-mix(in srgb, var(--warning-500) 20%, transparent);
  border-radius: var(--radius-sm);
}

.dp__sales-summary-label {
  font-size: var(--text-md);
  color: var(--text-2);
}

.dp__sales-summary-value {
  font-size: 20px;
  font-weight: var(--weight-bold);
  color: var(--warning-600);
  font-variant-numeric: tabular-nums;
}

.dp__export-btn svg {
  width: 14px;
  height: 14px;
}

.dp__sales-line-chart,
.dp__sales-bar-chart {
  padding: 0 4px;
}

.dp__sales-chart-scroll {
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.dp__sales-chart-scroll::-webkit-scrollbar { display: none; }

.dp__sales-bar-label {
  font-size: 10px;
  color: var(--text-2);
  white-space: nowrap;
  max-width: 60px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dp__sales-bar-value {
  font-size: 10px;
  font-weight: var(--weight-semibold);
  color: var(--text-1);
}

@media (max-width: 1023.98px) {
  .dp__title { display: none; }
  .dp__charts { grid-template-columns: 1fr; }
  .dp__rank { grid-template-columns: 1fr; }
}

@media (max-width: 767.98px) {
  .dashboard__tabs {
    width: 100%;
    margin-bottom: 12px;
  }

  .dashboard__tab {
    min-width: 0;
    flex: 1;
    padding: 0 8px;
  }

  .dp__header {
    align-items: stretch;
    margin-bottom: 12px;
  }

  .dp__date-bar,
  .dp__sales-date-bar {
    width: 100%;
  }

  .dp__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dp__card,
  .dp__chart-card,
  .dp__sales-chart-card {
    padding: 14px;
  }

  .dp__revenue-inner {
    min-height: 110px;
    padding: 18px;
  }

  .dp__revenue-num { font-size: 30px; }

  .dp__sales-header { flex-direction: column; align-items: flex-start; }
  .dp__sales-controls { width: 100%; }
  .dp__sales-summary { padding: 8px 12px; }
  .dp__sales-summary-value { font-size: 18px; }
}

@media (max-width: 430px) {
  .dp__cards { grid-template-columns: 1fr; }
}

/* --- Compact data panel -------------------------------------------------
   Keep the data view dense on desktop while retaining the existing mobile
   breakpoints above. Local tokens make the panel read consistently in both
   themes without changing the shared design system. */
.dp {
  --dp-panel-bg: var(--bg-elevated);
  --dp-panel-bg-soft: color-mix(in srgb, var(--bg-muted) 88%, var(--bg-elevated));
  --dp-panel-border: color-mix(in srgb, var(--border-1) 92%, var(--text-1) 8%);
  --dp-panel-shadow: 0 1px 2px color-mix(in srgb, var(--bg-inverse) 12%, transparent);
  --dp-control-active-text: var(--brand-700);
  --dp-control-active-bg: var(--brand-50);
  --dp-control-active-border: var(--brand-200);
  --dp-revenue-bg: color-mix(in srgb, var(--warning-50) 88%, var(--bg-elevated));
  --dp-revenue-border: color-mix(in srgb, var(--warning-500) 30%, var(--border-1));
  --dp-revenue-ink: var(--warning-600);
}

:global([data-theme="dark"] .dp) {
  --dp-panel-bg: color-mix(in srgb, var(--bg-elevated) 90%, #020617 10%);
  --dp-panel-bg-soft: color-mix(in srgb, var(--bg-muted) 82%, var(--bg-elevated));
  --dp-panel-border: color-mix(in srgb, var(--border-1) 78%, #93c5fd 22%);
  --dp-panel-shadow: 0 2px 8px rgba(0, 0, 0, 0.22);
  --dp-control-active-text: #bfdbfe;
  --dp-control-active-bg: rgba(37, 99, 235, 0.2);
  --dp-control-active-border: rgba(96, 165, 250, 0.48);
  --dp-revenue-bg: color-mix(in srgb, var(--warning-50) 76%, #0b1220 24%);
  --dp-revenue-border: color-mix(in srgb, var(--warning-500) 42%, var(--border-1));
  --dp-revenue-ink: #fbbf74;
}

.dp__quick-btn--active {
  color: var(--dp-control-active-text);
  background: var(--dp-control-active-bg);
  border-color: var(--dp-control-active-border);
}

.dp__card,
.dp__chart-card,
.dp__sales-chart-card {
  background: var(--dp-panel-bg);
  border-color: var(--dp-panel-border);
  box-shadow: var(--dp-panel-shadow);
}

.dp__revenue-inner {
  background: var(--dp-revenue-bg);
  border-color: var(--dp-revenue-border);
  border-left-color: var(--warning-600);
}

.dp__revenue-label,
.dp__revenue-symbol,
.dp__revenue-num,
.dp__revenue-tip-icon {
  color: var(--dp-revenue-ink);
}

.dp__sales-summary {
  background: var(--dp-revenue-bg);
  border-color: color-mix(in srgb, var(--warning-500) 24%, var(--border-1));
}

.dp__sales-summary-value { color: var(--dp-revenue-ink); }

/* The chart classes are intentionally explicit: SVGs otherwise use their
   intrinsic 300x150 size and become disproportionately tall on wide cards. */
.dp__line-chart,
.dp__sales-line-chart {
  min-width: 0;
  padding: 0 2px;
}

.dp__line-svg {
  display: block;
  width: 100%;
  height: 150px;
}

.dp__bar-chart {
  display: flex;
  justify-content: center;
  gap: clamp(20px, 4vw, 36px);
  min-height: 136px;
  padding: 0 8px;
}

.dp__bar-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.dp__bar-wrap {
  width: 42px;
  height: 112px;
  display: flex;
  align-items: flex-end;
  overflow: hidden;
  background: var(--dp-panel-bg-soft);
  border: 1px solid var(--dp-panel-border);
  border-radius: 7px;
}

.dp__bar {
  width: 100%;
  max-height: 112px;
  min-height: 0;
  border-radius: 6px;
  transition: height 360ms var(--ease-emphasized), filter var(--duration-normal) var(--ease-standard);
}

.dp__bar--success {
  background: linear-gradient(180deg, var(--success-500), var(--teal-600));
  box-shadow: 0 8px 16px rgba(22, 163, 74, 0.2);
}

.dp__bar--fail {
  background: linear-gradient(180deg, var(--danger-500), var(--rose-600));
  box-shadow: 0 8px 16px rgba(220, 38, 38, 0.18);
}

.dp__ring-chart {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 132px;
  padding: 0;
}

.dp__ring-svg {
  width: 116px;
  height: 116px;
  display: block;
}

.dp__ring-svg circle { transition: stroke-dashoffset 420ms var(--ease-emphasized); }

.dp__ring-center {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.dp__chart-footer { margin-top: 8px; }

@media (min-width: 1024px) {
  .dashboard__tabs {
    margin-bottom: 10px;
    padding: 2px;
  }

  .dashboard__tab {
    min-height: 30px;
    padding-inline: 10px;
  }

  .dp__header {
    gap: 8px;
    margin-bottom: 10px;
  }

  .dp__title { font-size: 20px; }

  .dp__date-bar,
  .dp__sales-controls,
  .dp__sales-date-bar,
  .dp__sales-quick-bar {
    gap: 6px;
  }

  .dp__quick-btn,
  .dp__export-btn,
  .dp__toggle-btn {
    min-height: 28px;
    padding: 4px 9px;
    font-size: var(--text-sm);
  }

  .dp__date-input {
    width: 126px;
    height: 28px;
    padding-inline: 7px;
  }

  .dp__cards {
    gap: 8px;
    margin-bottom: 10px;
  }

  .dp__card {
    min-height: 62px;
    gap: 10px;
    padding: 10px 12px;
    border-radius: 9px;
  }

  .dp__card-icon {
    width: 32px;
    height: 32px;
    border-radius: 7px;
  }

  .dp__card-icon svg {
    width: 17px;
    height: 17px;
  }

  .dp__card-value { font-size: 22px; }
  .dp__card-label { margin-top: 2px; font-size: var(--text-sm); }

  .dp__revenue { margin-bottom: 10px; }

  .dp__revenue-inner {
    min-height: 88px;
    padding: 14px 16px;
    gap: 6px;
    border-left-width: 3px;
    border-radius: 9px;
  }

  .dp__revenue-label { gap: 6px; font-size: var(--text-sm); }
  .dp__revenue-label svg { width: 16px; height: 16px; }
  .dp__revenue-symbol { font-size: 18px; }
  .dp__revenue-num { font-size: 30px; }

  .dp__sales-chart-card {
    position: relative;
    margin-bottom: 10px;
    padding: 12px;
    border-radius: 9px;
  }

  .dp__sales-header {
    gap: 6px;
    margin-bottom: 8px;
  }

  .dp__chart-title { font-size: 14px; }
  .dp__chart-toggle { gap: 1px; padding: 2px; }
  .dp__toggle-btn { min-height: 24px; padding-inline: 8px; }

  .dp__sales-date-bar {
    margin-bottom: 10px;
  }

  .dp__sales-summary {
    gap: 6px;
    margin-bottom: 10px;
    padding: 7px 10px;
  }

  .dp__sales-summary-label { font-size: var(--text-sm); }
  .dp__sales-summary-value { font-size: 18px; }

  .dp__sales-line-chart .dp__line-svg { height: 148px; }

  .dp__charts {
    gap: 10px;
  }

  .dp__rank {
    gap: 10px;
    margin-top: 12px;
  }

  .dp__rank-list { gap: 11px; }
  .dp__rank-row { gap: 8px; }
  .dp__rank-no { width: 20px; height: 20px; font-size: 11px; }
  .dp__rank-name { font-size: var(--text-xs); }
  .dp__rank-count { font-size: var(--text-xs); }

  .dp__chart-card {
    min-width: 0;
    padding: 12px;
    border-radius: 9px;
  }

  .dp__chart-head { margin-bottom: 8px; }
  .dp__chart-card .dp__line-svg { height: 142px; }
  .dp__legend { gap: 8px 12px; margin-top: 6px; margin-bottom: 4px; }
  .dp__legend-item { gap: 5px; font-size: var(--text-xs); }
  .dp__legend-dot { width: 7px; height: 7px; }
  .dp__chart-footer { font-size: var(--text-xs); }
}

@media (max-width: 1023.98px) {
  .dp__line-svg { height: auto; }
  .dp__bar-chart { min-height: 156px; }
  .dp__bar-wrap { height: 132px; }
  .dp__bar { max-height: 132px; }
  .dp__ring-chart { height: 148px; }
  .dp__ring-svg { width: 132px; height: 132px; }
}

@media (prefers-reduced-motion: no-preference) {
  .dp__card,
  .dp__chart-card,
  .dp__sales-chart-card {
    transition: transform var(--duration-normal) var(--ease-standard),
      border-color var(--duration-normal) var(--ease-standard),
      box-shadow var(--duration-normal) var(--ease-standard);
  }

  .dp__card:hover,
  .dp__chart-card:hover,
  .dp__sales-chart-card:hover {
    transform: translateY(-1px);
    box-shadow: var(--shadow-sm);
  }

  .dp__revenue-num {
    transition: color var(--duration-normal) var(--ease-standard),
      filter var(--duration-normal) var(--ease-standard);
  }

  .dp__revenue:hover .dp__revenue-num {
    filter: saturate(1.08);
  }

  .dp__bar:hover { filter: brightness(1.08); }
  .dp__line-chart,
  .dp__sales-line-chart,
  .dp__ring-chart { animation: dp-chart-enter 280ms var(--ease-emphasized) both; }
}

@keyframes dp-chart-enter {
  from { opacity: 0; transform: translateY(3px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
