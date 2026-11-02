import { ref, reactive, computed, nextTick } from 'vue'
import { queryDeliveryRecordList, confirmShipment, confirmShipmentBatch } from '@/api/order'
import { getAccountList } from '@/api/account'
import { getGoodsList, type GoodsItemWithConfig } from '@/api/goods'
import type { DeliveryRecordVO, DeliveryRecordQueryReq } from '@/api/order'
import type { Account } from '@/types'
import { showSuccess, showError } from '@/utils'
import { formatTime } from '@/utils'

export interface DeliveryRecordItem extends DeliveryRecordVO {
  confirming?: boolean
}

export function useOrderManager() {
  const loading = ref(false)
  const orderList = ref<DeliveryRecordItem[]>([])
  const total = ref(0)
  const accounts = ref<Account[]>([])

  const goodsList = ref<GoodsItemWithConfig[]>([])
  const goodsTotal = ref(0)
  const goodsLoading = ref(false)
  const goodsListRef = ref<HTMLElement | null>(null)
  const goodsCurrentPage = ref(1)
  const onlyOnSale = ref(true)
  const selectedGoodsId = ref<string | null>(null)

  const queryParams = reactive<DeliveryRecordQueryReq>({
    pageNum: 1,
    pageSize: 20
  })

  const dialogs = reactive({
    confirmShipment: false,
    filter: false
  })

  const confirmTarget = ref<DeliveryRecordItem | null>(null)

  const totalPages = computed(() => Math.ceil(total.value / (queryParams.pageSize || 20)))

  const loadAccounts = async () => {
    try {
      const response = await getAccountList()
      if (response.code === 0 || response.code === 200) {
        accounts.value = response.data?.accounts || []
        if (accounts.value.length > 0 && !queryParams.xianyuAccountId) {
          queryParams.xianyuAccountId = accounts.value[0]?.id
        }
      }
    } catch (error) {
      console.error('加载账号列表失败:', error)
    }
  }

  const handleAccountChange = () => {
    queryParams.pageNum = 1
    queryParams.keyword = undefined
    selectedGoodsId.value = null
    goodsCurrentPage.value = 1
    goodsList.value = []
    loadGoods()
    loadOrders()
  }

  const loadGoods = async () => {
    if (!queryParams.xianyuAccountId) {
      return
    }

    goodsLoading.value = true
    try {
      const params = {
        xianyuAccountId: queryParams.xianyuAccountId,
        onlyOnSale: onlyOnSale.value,
        pageNum: goodsCurrentPage.value,
        pageSize: 20
      }

      const response = await getGoodsList(params)
      if (response.code === 0 || response.code === 200) {
        if (goodsCurrentPage.value === 1) {
          goodsList.value = response.data?.itemsWithConfig || []
        } else {
          goodsList.value.push(...(response.data?.itemsWithConfig || []))
        }
        goodsTotal.value = response.data?.totalCount || 0
        checkAndLoadMore()
      } else {
        throw new Error(response.msg || '获取商品列表失败')
      }
    } catch (error) {
      console.error('加载商品列表失败:', error)
      goodsList.value = []
    } finally {
      goodsLoading.value = false
    }
  }

  const checkAndLoadMore = () => {
    nextTick(() => {
      if (!goodsListRef.value) return
      const { scrollHeight, clientHeight } = goodsListRef.value
      if (scrollHeight <= clientHeight && goodsList.value.length < goodsTotal.value) {
        goodsCurrentPage.value++
        loadGoods()
      }
    })
  }

  const handleGoodsScroll = () => {
    if (!goodsListRef.value || goodsLoading.value) return
    const { scrollTop, scrollHeight, clientHeight } = goodsListRef.value
    if (scrollTop + clientHeight >= scrollHeight - 50) {
      if (goodsList.value.length < goodsTotal.value) {
        goodsCurrentPage.value++
        loadGoods()
      }
    }
  }

  const selectGoods = (goods: GoodsItemWithConfig) => {
    if (selectedGoodsId.value === goods.item.xyGoodId) {
      clearGoodsFilter()
      return
    }
    selectedGoodsId.value = goods.item.xyGoodId
    queryParams.xyGoodsId = goods.item.xyGoodId
    queryParams.pageNum = 1
    loadOrders()
  }

  const clearGoodsFilter = () => {
    selectedGoodsId.value = null
    queryParams.xyGoodsId = undefined
    queryParams.pageNum = 1
    loadOrders()
  }

  const toggleOnlyOnSale = () => {
    onlyOnSale.value = !onlyOnSale.value
    goodsCurrentPage.value = 1
    selectedGoodsId.value = null
    queryParams.xyGoodsId = undefined
    loadGoods()
  }

  const getStatusColor = (state: number) => {
    return state === 1 ? 'var(--success-500)' : 'var(--danger-500)'
  }

  const getStatusBg = (state: number) => {
    return state === 1 ? 'rgba(52, 199, 89, 0.1)' : 'rgba(255, 59, 48, 0.1)'
  }

  const getStatusText = (state: number) => {
    return state === 1 ? '成功' : '失败'
  }

  const loadOrders = async () => {
    loading.value = true
    selectedOrderIds.value = []
    try {
      const response = await queryDeliveryRecordList(queryParams)
      orderList.value = (response.data?.records || []).map(item => ({
        ...item,
        confirming: false
      }))
      total.value = response.data?.total || 0
    } catch (error) {
      console.error('查询发货记录失败:', error)
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!(error as Error & { messageShown?: boolean }).messageShown) {
        showError('查询发货记录失败: ' + ((error as Error).message || '未知错误'))
      }
      orderList.value = []
    } finally {
      loading.value = false
    }
  }

  const handleReset = () => {
    queryParams.keyword = undefined
    queryParams.state = undefined
    queryParams.pageNum = 1
    loadOrders()
  }

  const showManualReviewQueue = () => {
    queryParams.state = 0
    queryParams.pageNum = 1
    loadOrders()
  }

  const clearStateFilter = () => {
    queryParams.state = undefined
    queryParams.pageNum = 1
    loadOrders()
  }

  const handlePageChange = (page: number) => {
    queryParams.pageNum = page
    loadOrders()
  }

  const handleSizeChange = (size: number) => {
    queryParams.pageSize = size
    queryParams.pageNum = 1
    loadOrders()
  }

  const copySId = (sid: string) => {
    navigator.clipboard.writeText(sid).then(() => {
      showSuccess('已复制')
    }).catch(() => {
      showError('复制失败')
    })
  }

  const handleConfirmShipment = async (row: DeliveryRecordItem) => {
    if (!row.orderId) {
      showError('订单ID为空')
      return
    }
    try {
      row.confirming = true
      await confirmShipment({
        xianyuAccountId: row.xianyuAccountId!,
        orderId: row.orderId
      })

      showSuccess('确认发货成功')
      loadOrders()
    } catch (error) {
      showError('确认发货失败: ' + ((error as Error).message || '未知错误'))
    } finally {
      row.confirming = false
    }
  }

  // === 批量确认发货 ===
  const selectedOrderIds = ref<number[]>([])
  const batchConfirming = ref(false)

  // 当前页可批量操作的记录（需有订单ID和账号）
  const selectableOrderIds = computed(() =>
    orderList.value
      .filter(o => o.orderId && o.xianyuAccountId)
      .map(o => o.id)
  )

  const isAllSelected = computed(() =>
    selectableOrderIds.value.length > 0 &&
    selectableOrderIds.value.every(id => selectedOrderIds.value.includes(id))
  )

  const isIndeterminate = computed(() =>
    selectedOrderIds.value.length > 0 && !isAllSelected.value
  )

  const isOrderSelected = (id: number) => selectedOrderIds.value.includes(id)

  const toggleSelectOrder = (id: number) => {
    const idx = selectedOrderIds.value.indexOf(id)
    if (idx >= 0) {
      selectedOrderIds.value.splice(idx, 1)
    } else {
      selectedOrderIds.value.push(id)
    }
  }

  const toggleSelectAll = () => {
    if (isAllSelected.value) {
      selectedOrderIds.value = []
    } else {
      selectedOrderIds.value = [...selectableOrderIds.value]
    }
  }

  const clearSelection = () => {
    selectedOrderIds.value = []
  }

  const handleBatchConfirmShipment = async () => {
    const items = orderList.value
      .filter(o => selectedOrderIds.value.includes(o.id))
      .map(o => ({
        xianyuAccountId: o.xianyuAccountId as number,
        orderId: o.orderId as string
      }))
    if (!items.length) {
      showError('请先选择要确认的记录')
      return
    }
    batchConfirming.value = true
    try {
      const res = await confirmShipmentBatch(items)
      if ((res.code === 200 || res.code === 0) && res.data) {
        const data = res.data
        if (data.successCount > 0) {
          showSuccess(`批量确认成功 ${data.successCount} 条`)
        }
        if (data.failCount > 0) {
          const first = data.failures[0]
          showError(`批量确认失败 ${data.failCount} 条` + (first ? `：${first.reason || '未知原因'}` : ''))
        }
        selectedOrderIds.value = []
        loadOrders()
      } else {
        showError(res.msg || '批量确认发货失败')
      }
    } catch (error) {
      showError('批量确认发货失败: ' + ((error as Error).message || '未知错误'))
    } finally {
      batchConfirming.value = false
    }
  }

  return {
    loading,
    orderList,
    total,
    accounts,
    goodsList,
    goodsTotal,
    goodsLoading,
    goodsListRef,
    goodsCurrentPage,
    onlyOnSale,
    selectedGoodsId,
    queryParams,
    dialogs,
    confirmTarget,
    totalPages,
    loadAccounts,
    loadOrders,
    loadGoods,
    handleAccountChange,
    handleReset,
    showManualReviewQueue,
    clearStateFilter,
    handlePageChange,
    handleSizeChange,
    copySId,
    handleConfirmShipment,
    selectedOrderIds,
    batchConfirming,
    isAllSelected,
    isIndeterminate,
    isOrderSelected,
    toggleSelectOrder,
    toggleSelectAll,
    clearSelection,
    handleBatchConfirmShipment,
    handleGoodsScroll,
    selectGoods,
    clearGoodsFilter,
    toggleOnlyOnSale,
    getStatusColor,
    getStatusBg,
    getStatusText,
    formatTime
  }
}
