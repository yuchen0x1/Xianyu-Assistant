import { ref, reactive, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAccountList } from '@/api/account'
import { accountDisplayName } from '@/utils'
import {
  getGoodsList,
  refreshGoods,
  updateAutoDeliveryStatus,
  updateAutoReplyStatus,
  deleteItem,
  syncSingleItem,
  getSyncProgress,
  polishGoods,
  batchPolishGoods,
  batchUpdatePrice
} from '@/api/goods'
import { batchOffShelf } from '@/api/publish'
import { showSuccess, showError, showInfo } from '@/utils'
import { showConfirm } from '@/utils/confirm'
import { getGoodsStatusText, formatPrice, formatTime } from '@/utils'
import type { Account } from '@/types'
import type { GoodsItemWithConfig, SyncProgressResponse } from '@/api/goods'

/** 只有在售商品可以下架 */
const SELLABLE_STATUS = 0
/** 单次批量下架上限，与后端校验保持一致 */
const BATCH_OFF_SHELF_LIMIT = 50

export function useGoodsManager() {
  const router = useRouter()

  const loading = ref(false)
  const refreshing = ref(false)
  const accounts = ref<Account[]>([])
  const selectedAccountId = ref<number | null>(null)
  const statusFilter = ref<string>('')
  const goodsList = ref<GoodsItemWithConfig[]>([])
  const currentPage = ref(1)
  const pageSize = ref(20)
  const total = ref(0)
  let goodsLoadSequence = 0

  const dialogs = reactive({
    detail: false,
    deleteConfirm: false,
    filter: false,
    editor: false,
    batchPrice: false
  })

  const selectedGoodsId = ref<string>('')
  const selectedGoods = ref<GoodsItemWithConfig | null>(null)
  const editorGoods = ref<GoodsItemWithConfig['item'] | null>(null)
  const deleteTarget = ref<{ id: string; title: string } | null>(null)

  const syncProgress = ref<SyncProgressResponse | null>(null)
  const syncing = ref(false)
  let syncProgressTimer: ReturnType<typeof setInterval> | null = null
  // 轮询必须能终止：接口持续失败时旧实现每秒重试且永不复位，刷新按钮永久转圈
  let syncPollInFlight = false
  let syncPollFailures = 0
  const SYNC_POLL_MAX_FAILURES = 5

  const stopSyncPolling = () => {
    if (syncProgressTimer) {
      clearInterval(syncProgressTimer)
      syncProgressTimer = null
    }
  }

  const finishSyncPolling = () => {
    stopSyncPolling()
    syncPollInFlight = false
    syncPollFailures = 0
    syncing.value = false
    refreshing.value = false
  }

  const handleSyncPollFailure = (error?: unknown) => {
    syncPollFailures += 1
    if (error) {
      console.error('获取同步进度失败:', error)
    }
    if (syncPollFailures >= SYNC_POLL_MAX_FAILURES) {
      finishSyncPolling()
      showError('同步进度获取失败，请刷新页面查看同步结果')
    }
  }

  const pollSyncProgress = async (syncId: string) => {
    if (syncPollInFlight) return
    syncPollInFlight = true
    try {
      const response = await getSyncProgress(syncId)
      if (response.code === 0 || response.code === 200) {
        syncPollFailures = 0
        if (response.data) {
          syncProgress.value = response.data
          if (response.data.isCompleted || !response.data.isRunning) {
            const completed = response.data
            finishSyncPolling()
            if (completed.successCount && completed.successCount > 0) {
              showSuccess(`详情同步完成: 成功${completed.successCount}个, 失败${completed.failedCount}个`)
            }
            await loadGoods()
          }
        }
      } else {
        handleSyncPollFailure()
      }
    } catch (error) {
      handleSyncPollFailure(error)
    } finally {
      syncPollInFlight = false
    }
  }

  const startSyncPolling = (syncId: string) => {
    stopSyncPolling()
    syncPollFailures = 0
    syncing.value = true
    syncProgressTimer = setInterval(() => {
      void pollSyncProgress(syncId)
    }, 1000)
  }

  onUnmounted(() => {
    stopSyncPolling()
  })

  // Computed
  const totalPages = computed(() => Math.ceil(total.value / pageSize.value))
  const accountName = computed(() => {
    if (!selectedAccountId.value) return ''
    const acc = accounts.value.find(a => a.id === selectedAccountId.value)
    return acc ? accountDisplayName(acc) : ''
  })

  // 加载账号列表
  const loadAccounts = async () => {
    try {
      const response = await getAccountList()
      if (response.code === 0 || response.code === 200) {
        accounts.value = response.data?.accounts || []
        if (accounts.value.length > 0 && !selectedAccountId.value) {
          selectedAccountId.value = accounts.value[0]?.id || null
          await loadGoods()
        }
      }
    } catch (error) {
      console.error('加载账号列表失败:', error)
    }
  }

  // 加载商品列表
  const loadGoods = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }

    const requestSequence = ++goodsLoadSequence
    const accountId = selectedAccountId.value
    loading.value = true
    try {
      const params: { xianyuAccountId: number; onlyOnSale?: boolean; status?: number; pageNum?: number; pageSize?: number } = {
        xianyuAccountId: accountId,
        // 商品管理默认展示全部本地资料，包含新建草稿和已下架商品。
        onlyOnSale: false,
        pageNum: currentPage.value,
        pageSize: pageSize.value
      }
      if (statusFilter.value !== '') {
        params.status = parseInt(statusFilter.value)
      }
      const response = await getGoodsList(params)
      if (requestSequence !== goodsLoadSequence || accountId !== selectedAccountId.value) return
      if (response.code === 0 || response.code === 200) {
        goodsList.value = response.data?.itemsWithConfig || []
        total.value = response.data?.totalCount || 0
      }
    } catch (error) {
      if (requestSequence !== goodsLoadSequence || accountId !== selectedAccountId.value) return
      console.error('加载商品列表失败:', error)
      goodsList.value = []
    } finally {
      if (requestSequence === goodsLoadSequence) loading.value = false
    }
  }

  // 刷新商品数据
  const handleRefresh = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    refreshing.value = true
    try {
      const response = await refreshGoods(selectedAccountId.value)
      if (response.code === 0 || response.code === 200) {
        if (response.data && response.data.success) {
          showSuccess('商品数据刷新成功')
          if (response.data.syncId) {
            startSyncPolling(response.data.syncId)
          } else {
            await loadGoods()
            refreshing.value = false
          }
        } else {
          showError(response.data?.message || '刷新商品数据失败')
          refreshing.value = false
        }
      }
    } catch (error) {
      console.error('刷新商品数据失败:', error)
      refreshing.value = false
    }
  }

  // 账号变更
  const handleAccountChange = () => {
    currentPage.value = 1
    clearSelection()
    loadGoods()
  }

  // 状态筛选
  const handleStatusFilter = () => {
    currentPage.value = 1
    clearSelection()
    loadGoods()
  }

  // 分页
  const handlePageChange = (page: number) => {
    currentPage.value = page
    clearSelection()
    loadGoods()
  }

  // 查看详情
  const viewDetail = (xyGoodId: string) => {
    selectedGoodsId.value = xyGoodId
    dialogs.detail = true
  }

  const createGoods = () => {
    editorGoods.value = null
    dialogs.editor = true
  }

  const editGoods = (item: GoodsItemWithConfig) => {
    editorGoods.value = item.item
    dialogs.editor = true
  }

  // 配置自动发货
  const configAutoDelivery = (item: GoodsItemWithConfig) => {
    router.push({
      path: '/auto-delivery',
      query: {
        accountId: selectedAccountId.value?.toString(),
        goodsId: item.item.xyGoodId
      }
    })
  }

  // 切换自动发货
  const toggleAutoDelivery = async (item: GoodsItemWithConfig, value: boolean) => {
    if (!selectedAccountId.value) return
    try {
      const response = await updateAutoDeliveryStatus({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: item.item.xyGoodId,
        xianyuAutoDeliveryOn: value ? 1 : 0
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess(`自动发货${value ? '开启' : '关闭'}成功`)
        item.xianyuAutoDeliveryOn = value ? 1 : 0
      } else {
        throw new Error(response.msg || '操作失败')
      }
    } catch (error) {
      console.error('操作失败:', error)
      item.xianyuAutoDeliveryOn = value ? 0 : 1
    }
  }

  // 切换自动回复
  const toggleAutoReply = async (item: GoodsItemWithConfig, value: boolean) => {
    if (!selectedAccountId.value) return
    try {
      const response = await updateAutoReplyStatus({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: item.item.xyGoodId,
        xianyuAutoReplyOn: value ? 1 : 0
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess(`自动回复${value ? '开启' : '关闭'}成功`)
        item.xianyuAutoReplyOn = value ? 1 : 0
      } else {
        throw new Error(response.msg || '操作失败')
      }
    } catch (error) {
      console.error('操作失败:', error)
      item.xianyuAutoReplyOn = value ? 0 : 1
    }
  }

  // 删除商品
  const confirmDelete = (xyGoodId: string, title: string) => {
    deleteTarget.value = { id: xyGoodId, title }
    dialogs.deleteConfirm = true
  }

  const executeDelete = async () => {
    if (!selectedAccountId.value || !deleteTarget.value) return
    try {
      const response = await deleteItem({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: deleteTarget.value.id
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess('商品删除成功')
        dialogs.deleteConfirm = false
        deleteTarget.value = null
        await loadGoods()
      } else {
        throw new Error(response.msg || '删除失败')
      }
    } catch (error) {
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!(error as Error & { messageShown?: boolean }).messageShown) {
        showError('删除失败: ' + (error as Error).message)
      }
    }
  }

  const syncSingleGoods = async (xyGoodId: string) => {
    if (!selectedAccountId.value) return
    try {
      const response = await syncSingleItem({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: xyGoodId
      })
      if (response.code === 0 || response.code === 200) {
        showSuccess('同步成功')
        loadGoods()
      } else {
        throw new Error(response.msg || '同步失败')
      }
    } catch (error) {
      console.error('同步失败:', error)
    }
  }

  const polishing = ref(false)

  // 擦亮单件商品
  const handlePolishItem = async (xyGoodId: string) => {
    if (!selectedAccountId.value) return
    polishing.value = true
    try {
      const res = await polishGoods({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsId: xyGoodId
      })
      if (res.code === 0 || res.code === 200) {
        showSuccess(res.msg || '商品擦亮成功')
      } else {
        showError(res.msg || '商品擦亮失败')
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      showError('擦亮异常: ' + message)
    } finally {
      polishing.value = false
    }
  }

  // 一键擦亮当前账号在售商品
  const handleBatchPolish = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    polishing.value = true
    try {
      const res = await batchPolishGoods({
        xianyuAccountId: selectedAccountId.value
      })
      if (res.code === 0 || res.code === 200) {
        showSuccess(res.msg || '批量擦亮完成')
      } else {
        showError(res.msg || '批量擦亮失败')
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      showError('批量擦亮异常: ' + message)
    } finally {
      polishing.value = false
    }
  }

  // ==================== 批量选择与批量下架 ====================

  const selectedGoodsIds = ref<string[]>([])
  const batchOffShelving = ref(false)
  const batchPrice = ref('')
  const batchUpdatingPrice = ref(false)

  /** 当前页可被选中（在售）的商品 */
  const selectableGoods = computed(() =>
    goodsList.value.filter(item => item.item.status === SELLABLE_STATUS)
  )

  const isAllSelected = computed(
    () =>
      selectableGoods.value.length > 0 &&
      selectableGoods.value.every(item => selectedGoodsIds.value.includes(item.item.xyGoodId))
  )

  const toggleSelect = (xyGoodId: string) => {
    const index = selectedGoodsIds.value.indexOf(xyGoodId)
    if (index >= 0) {
      selectedGoodsIds.value.splice(index, 1)
    } else {
      selectedGoodsIds.value.push(xyGoodId)
    }
  }

  const toggleSelectAll = () => {
    if (isAllSelected.value) {
      selectedGoodsIds.value = []
      return
    }
    selectedGoodsIds.value = selectableGoods.value.map(item => item.item.xyGoodId)
  }

  const clearSelection = () => {
    selectedGoodsIds.value = []
  }

  const handleBatchOffShelf = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    const ids = [...selectedGoodsIds.value]
    if (ids.length === 0) {
      showInfo('请先选择要下架的商品')
      return
    }
    if (ids.length > BATCH_OFF_SHELF_LIMIT) {
      showError(`单次最多批量下架${BATCH_OFF_SHELF_LIMIT}个商品，当前已选${ids.length}个`)
      return
    }

    try {
      await showConfirm(
        `即将下架 ${ids.length} 个商品。下架后商品在闲鱼端不再展示，此操作不可撤销。`,
        '确认批量下架',
        { variant: 'danger' }
      )
    } catch {
      return
    }

    batchOffShelving.value = true
    try {
      const res = await batchOffShelf({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsIds: ids
      })
      if (res.code === 0 || res.code === 200) {
        const data = res.data
        if (data) {
          if (data.failed > 0) {
            showError(`批量下架完成：成功${data.success}个，失败${data.failed}个`)
            const failedItems = data.details.filter(d => !d.success).slice(0, 3)
            if (failedItems.length > 0) {
              console.error('批量下架失败明细:', failedItems)
            }
          } else {
            showSuccess(`批量下架成功：共${data.success}个商品`)
          }
        } else {
          showSuccess(res.msg || '批量下架成功')
        }
        clearSelection()
        await loadGoods()
      } else {
        showError(res.msg || '批量下架失败')
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      showError('批量下架异常: ' + message)
    } finally {
      batchOffShelving.value = false
    }
  }

  const openBatchPriceDialog = () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    if (selectedGoodsIds.value.length === 0) {
      showInfo('请先选择要改价的商品')
      return
    }
    batchPrice.value = ''
    dialogs.batchPrice = true
  }

  const closeBatchPriceDialog = () => {
    if (!batchUpdatingPrice.value) dialogs.batchPrice = false
  }

  const handleBatchUpdatePrice = async () => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    const ids = [...selectedGoodsIds.value]
    const price = batchPrice.value.trim()
    if (ids.length === 0) {
      showInfo('请先选择要改价的商品')
      return
    }
    if (ids.length > BATCH_OFF_SHELF_LIMIT) {
      showError(`单次最多批量改价${BATCH_OFF_SHELF_LIMIT}个商品，当前已选${ids.length}个`)
      return
    }
    if (!/^\d+(\.\d{1,2})?$/.test(price) || Number(price) <= 0 || Number(price) > 100000000) {
      showError('请输入大于0且最多两位小数、最高100000000元的价格')
      return
    }

    try {
      await showConfirm(
        `将 ${ids.length} 个商品的本地售价统一改为 ¥${price}。闲鱼远程商品需在官方页面确认。`,
        '确认批量改价'
      )
    } catch {
      return
    }

    batchUpdatingPrice.value = true
    try {
      const res = await batchUpdatePrice({
        xianyuAccountId: selectedAccountId.value,
        xyGoodsIds: ids,
        soldPrice: price
      })
      if (res.code === 0 || res.code === 200) {
        const data = res.data
        if (data && data.failed > 0) {
          showError(`批量改价完成：成功${data.success}个，失败${data.failed}个`)
          console.error('批量改价失败明细:', data.details.filter(item => !item.success))
        } else {
          showSuccess(`批量改价成功：${data?.success ?? ids.length}个商品已更新本地售价`)
        }
        dialogs.batchPrice = false
        clearSelection()
        await loadGoods()
      } else {
        showError(res.msg || '批量改价失败')
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      showError('批量改价异常: ' + message)
    } finally {
      batchUpdatingPrice.value = false
    }
  }

  return {
    loading,
    refreshing,
    polishing,
    handlePolishItem,
    handleBatchPolish,
    syncing,
    syncProgress,
    accounts,
    selectedAccountId,
    statusFilter,
    goodsList,
    currentPage,
    pageSize,
    total,
    totalPages,
    accountName,
    dialogs,
    selectedGoodsId,
    selectedGoods,
    editorGoods,
    deleteTarget,
    loadAccounts,
    loadGoods,
    handleRefresh,
    handleAccountChange,
    handleStatusFilter,
    handlePageChange,
    viewDetail,
    createGoods,
    editGoods,
    configAutoDelivery,
    toggleAutoDelivery,
    toggleAutoReply,
    confirmDelete,
    executeDelete,
    getGoodsStatusText,
    formatPrice,
    formatTime,
    syncSingleGoods,
    selectedGoodsIds,
    batchOffShelving,
    batchPrice,
    batchUpdatingPrice,
    selectableGoods,
    isAllSelected,
    toggleSelect,
    toggleSelectAll,
    clearSelection,
    handleBatchOffShelf,
    openBatchPriceDialog,
    closeBatchPriceDialog,
    handleBatchUpdatePrice
  }
}
