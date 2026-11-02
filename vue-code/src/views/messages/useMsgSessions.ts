import { ref, computed } from 'vue'
import {
  getMsgSessions,
  getContextMessages,
  sendMessage,
  type MsgSession,
  type ChatMessage
} from '@/api/message'
import { showInfo } from '@/utils'
import { toast } from '@/utils/toast'

/**
 * 消息会话中心逻辑：会话聚合列表 + 会话详情（上下文） + 快速回复
 */
export function useMsgSessions(selectedAccountId: { value: number | null }) {
  // ---- 会话列表 ----
  const sessionList = ref<MsgSession[]>([])
  const sessionTotal = ref(0)
  const sessionPage = ref(1)
  const sessionPageSize = ref(20)
  const sessionKeyword = ref('')
  const sessionLoading = ref(false)
  const sessionTotalPages = computed(() => Math.ceil(sessionTotal.value / sessionPageSize.value))

  // ---- 会话详情 ----
  const activeSession = ref<MsgSession | null>(null)
  const contextVisible = ref(false)
  const contextMessages = ref<ChatMessage[]>([])
  const contextLoading = ref(false)

  // ---- 快速回复 ----
  const replyText = ref('')
  const sendingReply = ref(false)

  const loadSessions = async (page = 1) => {
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    sessionLoading.value = true
    try {
      const response = await getMsgSessions({
        xianyuAccountId: selectedAccountId.value,
        keyword: sessionKeyword.value || undefined,
        pageNum: page,
        pageSize: sessionPageSize.value
      })
      if (response.code === 0 || response.code === 200) {
        sessionList.value = response.data?.list || []
        sessionTotal.value = response.data?.totalCount || 0
        sessionPage.value = page
      } else {
        throw new Error(response.msg || '获取会话列表失败')
      }
    } catch (error) {
      console.error('加载会话列表失败:', error)
      sessionList.value = []
      sessionTotal.value = 0
      if (page === 1) {
        toast.error('加载会话列表失败')
      }
    } finally {
      sessionLoading.value = false
    }
  }

  const searchSessions = () => {
    loadSessions(1)
  }

  const handleSessionPageChange = (page: number) => {
    loadSessions(page)
  }

  // ---- 会话详情 ----
  const openSession = async (session: MsgSession) => {
    activeSession.value = session
    contextVisible.value = true
    replyText.value = ''
    await loadContext(session.sid)
  }

  const closeSession = () => {
    contextVisible.value = false
    activeSession.value = null
    contextMessages.value = []
    replyText.value = ''
  }

  const loadContext = async (sid: string) => {
    contextLoading.value = true
    try {
      const response = await getContextMessages({ sid, limit: 50 })
      if (response.code === 0 || response.code === 200) {
        contextMessages.value = (response.data as ChatMessage[]) || []
      } else {
        throw new Error(response.msg || '获取会话消息失败')
      }
    } catch (error) {
      console.error('加载会话消息失败:', error)
      contextMessages.value = []
      toast.error('加载会话消息失败')
    } finally {
      contextLoading.value = false
    }
  }

  // ---- 快速回复 ----
  const sendReply = async () => {
    const text = replyText.value.trim()
    if (!selectedAccountId.value) {
      showInfo('请先选择账号')
      return
    }
    if (!activeSession.value) {
      showInfo('请先选择会话')
      return
    }
    if (!text) {
      showInfo('请输入回复内容')
      return
    }
    sendingReply.value = true
    try {
      const session = activeSession.value
      // cid/toId 均为去掉 @goofish 后缀的纯 ID
      const cid = session.sid.replace('@goofish', '')
      const toId = session.buyerUserId
      const response = await sendMessage({
        xianyuAccountId: selectedAccountId.value,
        cid,
        toId,
        text,
        xyGoodsId: session.xyGoodsId || undefined
      })
      if (response.code === 0 || response.code === 200) {
        // 本地追加一条手动回复，避免整页刷新
        contextMessages.value.push({
          // 加随机后缀防止同一毫秒多条本地回显 v-for key 冲突
          id: Date.now() + Math.floor(Math.random() * 1000),
          xianyuAccountId: selectedAccountId.value,
          lwp: '',
          pnmId: '',
          sid: session.sid,
          contentType: 999,
          msgContent: text,
          senderUserName: '我',
          senderUserId: toId,
          senderAppV: '',
          senderOsType: '',
          reminderUrl: '',
          xyGoodsId: session.xyGoodsId || '',
          completeMsg: '',
          messageTime: Date.now(),
          createTime: new Date().toISOString()
        })
        // 更新会话摘要
        session.lastContent = text
        session.pendingReply = false
        replyText.value = ''
        toast.success('回复成功')
      } else {
        toast.error(response.msg || '回复失败')
      }
    } catch (error) {
      console.error('发送回复失败:', error)
      toast.error('回复失败，请检查 WebSocket 连接')
    } finally {
      sendingReply.value = false
    }
  }

  return {
    sessionList,
    sessionTotal,
    sessionPage,
    sessionPageSize,
    sessionKeyword,
    sessionLoading,
    sessionTotalPages,
    activeSession,
    contextVisible,
    contextMessages,
    contextLoading,
    replyText,
    sendingReply,
    loadSessions,
    searchSessions,
    handleSessionPageChange,
    openSession,
    closeSession,
    loadContext,
    sendReply
  }
}
