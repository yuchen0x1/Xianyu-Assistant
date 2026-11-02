import { createApp, h, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PendingOrders from '@/views/pending-orders/index.vue'
import { useGoodsManager } from '@/views/goods/useGoodsManager'

const accountApi = vi.hoisted(() => ({ getAccountList: vi.fn() }))
const orderApi = vi.hoisted(() => ({
  getPendingOrders: vi.fn(),
  deliverPendingOrders: vi.fn(),
  consignDummyDelivery: vi.fn(),
}))
const goodsApi = vi.hoisted(() => ({
  getGoodsList: vi.fn(),
  refreshGoods: vi.fn(),
  updateAutoDeliveryStatus: vi.fn(),
  updateAutoReplyStatus: vi.fn(),
  deleteItem: vi.fn(),
  syncSingleItem: vi.fn(),
  getSyncProgress: vi.fn(),
}))

vi.mock('@/api/account', () => accountApi)
vi.mock('@/api/order', () => orderApi)
vi.mock('@/api/goods', () => goodsApi)
vi.mock('@/utils/confirm', () => ({ showConfirm: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/utils/toast', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))
vi.mock('@/utils', () => ({
  accountDisplayName: (account: { id: number }) => `账号${account.id}`,
  getGoodsStatusText: vi.fn(),
  formatPrice: vi.fn(),
  formatTime: vi.fn(),
  showSuccess: vi.fn(),
  showError: vi.fn(),
  showInfo: vi.fn(),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

let app: App<Element> | null = null
let host: HTMLDivElement | null = null

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

function mount(component: Parameters<typeof createApp>[0]) {
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp(component)
  app.mount(host)
  return host
}

beforeEach(() => {
  vi.clearAllMocks()
  accountApi.getAccountList.mockResolvedValue({
    code: 200,
    data: { accounts: [{ id: 11 }, { id: 22 }] },
  })
  orderApi.getPendingOrders.mockResolvedValue({ code: 200, data: [] })
  orderApi.consignDummyDelivery.mockResolvedValue({ code: 200, data: 'ok' })
})

afterEach(() => {
  app?.unmount()
  app = null
  host?.remove()
  host = null
  vi.useRealTimers()
})

describe('账号切换竞态', () => {
  it('自动发货启用时锁定账号，后续轮次沿用启动时账号', async () => {
    vi.useFakeTimers()
    const firstDelivery = deferred<{ code: number; data: number }>()
    orderApi.deliverPendingOrders.mockReturnValueOnce(firstDelivery.promise)
    const root = mount(PendingOrders)
    await flushUi()

    const autoDeliverInput = root.querySelector<HTMLInputElement>('.po__toggle--deliver input')
    expect(autoDeliverInput).not.toBeNull()
    autoDeliverInput!.checked = true
    autoDeliverInput!.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()

    const accountSelect = root.querySelector<HTMLSelectElement>('#pending-orders-account')
    expect(orderApi.deliverPendingOrders).toHaveBeenNthCalledWith(1, 11)
    expect(accountSelect?.disabled).toBe(true)

    accountSelect!.value = '22'
    accountSelect!.dispatchEvent(new Event('change', { bubbles: true }))
    await flushUi()
    expect(orderApi.getPendingOrders).not.toHaveBeenCalledWith(22)

    firstDelivery.resolve({ code: 200, data: 0 })
    await flushUi()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(orderApi.deliverPendingOrders).toHaveBeenNthCalledWith(2, 11)
  })

  it('较旧账号的商品列表响应不会覆盖当前账号数据', async () => {
    const earlierResponse = deferred<{
      code: number
      data: { itemsWithConfig: { item: { title: string } }[]; totalCount: number }
    }>()
    const latestResponse = deferred<{
      code: number
      data: { itemsWithConfig: { item: { title: string } }[]; totalCount: number }
    }>()
    goodsApi.getGoodsList
      .mockReturnValueOnce(earlierResponse.promise)
      .mockReturnValueOnce(latestResponse.promise)

    let manager!: ReturnType<typeof useGoodsManager>
    mount({ setup: () => { manager = useGoodsManager(); return () => h('div') } })
    manager.selectedAccountId.value = 11
    const earlierLoad = manager.loadGoods()

    manager.selectedAccountId.value = 22
    const latestLoad = manager.handleAccountChange()
    latestResponse.resolve({
      code: 200,
      data: { itemsWithConfig: [{ item: { title: '当前账号商品' } }], totalCount: 1 },
    })
    await latestLoad
    expect(manager.goodsList.value[0]?.item.title).toBe('当前账号商品')
    expect(manager.loading.value).toBe(false)

    earlierResponse.resolve({
      code: 200,
      data: { itemsWithConfig: [{ item: { title: '旧账号商品' } }], totalCount: 99 },
    })
    await earlierLoad

    expect(manager.goodsList.value[0]?.item.title).toBe('当前账号商品')
    expect(manager.total.value).toBe(1)
    expect(manager.loading.value).toBe(false)
    expect(goodsApi.getGoodsList).toHaveBeenNthCalledWith(1, expect.objectContaining({ xianyuAccountId: 11 }))
    expect(goodsApi.getGoodsList).toHaveBeenNthCalledWith(2, expect.objectContaining({ xianyuAccountId: 22 }))
  })
})
