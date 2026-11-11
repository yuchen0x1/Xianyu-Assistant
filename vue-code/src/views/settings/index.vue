<script setup lang="ts">
import { ref, onMounted, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentUser, changePassword } from '@/api/system'
import { logout } from '@/api/auth'
import { getSetting, saveSetting, testEmail, testNotifyChannel } from '@/api/setting'
import { chatTestWithAI, consumeAIStream, getAIStatus, listModels, type AIModelItem } from '@/api/ai'
import { getBackupModules, downloadBackupStream, importBackup, getBackupSchedule, getLogDates, downloadLog, type BackupModule, type BackupScheduleStatus } from '@/api/backup'
import { formatTime } from '@/utils'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'
import { clearAuthToken } from '@/utils/request'
import IconUser from '@/components/icons/IconUser.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconMail from '@/components/icons/IconMail.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconBackup from '@/components/icons/IconBackup.vue'
import IconInfo from '@/components/icons/IconInfo.vue'
import IconTruck from '@/components/icons/IconTruck.vue'
import IconShield from '@/components/icons/IconShield.vue'
import IconWifi from '@/components/icons/IconWifi.vue'
import ApiFallbackPanel from './components/ApiFallbackPanel.vue'
import ProxyPanel from './components/ProxyPanel.vue'
import AboutPanel from './components/AboutPanel.vue'

defineOptions({ name: 'SettingsView' })

const router = useRouter()

// 当前选中的菜单
const activeMenu = ref('account')

// 账号信息
const username = ref('')
const lastLoginTime = ref('')
const loading = ref(false)

// 修改密码
const showPasswordForm = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const changingPassword = ref(false)

const showOldPassword = ref(false)
const showNewPassword = ref(false)
const showConfirmPassword = ref(false)

// 退出登录
const loggingOut = ref(false)

// 系统提示词
const SYS_PROMPT_KEY = 'sys_prompt'
const DEFAULT_SYS_PROMPT = '你是一个闲鱼卖家，你叫肥极喵，不要回复的像AI，简短回答\n参考相关信息回答,不要乱回答,不知道就换不同语气回复提示用户详细点询问'
const sysPromptValue = ref('')
const sysPromptSaving = ref(false)
const sysPromptLoaded = ref(false)

// 相似度阈值
const SIMILARITY_THRESHOLD_KEY = 'similarity_threshold'
const DEFAULT_SIMILARITY_THRESHOLD = 0.1
const similarityThreshold = ref(DEFAULT_SIMILARITY_THRESHOLD)
const similarityThresholdSaving = ref(false)

// AI API Key 配置
const AI_API_KEY_SETTING = 'ai_api_key'
const AI_BASE_URL_SETTING = 'ai_base_url'
const AI_MODEL_SETTING = 'ai_model'
const DEFAULT_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode'
const DEFAULT_MODEL = 'deepseek-v3'

const aiApiKey = ref('')
const aiApiKeyConfigured = ref(false)
const aiBaseUrl = ref(DEFAULT_BASE_URL)
const aiModel = ref(DEFAULT_MODEL)
const aiApiKeySaving = ref(false)
const showApiKey = ref(false)

// 获取模型列表
const aiModelList = ref<AIModelItem[]>([])
const aiModelLoading = ref(false)
const aiModelError = ref('')

// AI 模型连接测试与保存状态独立管理；测试期间锁定配置表单，避免读写竞态。
const aiTestMessage = ref('你好，请用一句话确认模型连接正常。')
const aiTestResponse = ref('')
const aiTestError = ref('')
const aiTesting = ref(false)
const aiTestCompleted = ref(false)

const getErrorMessage = (error: unknown, fallback: string) => {
  return error instanceof Error && error.message ? error.message : fallback
}

// Embedding 模型配置（可选，默认共用 AI 配置）
const EMBEDDING_API_KEY_SETTING = 'ai_embedding_api_key'
const EMBEDDING_BASE_URL_SETTING = 'ai_embedding_base_url'
const EMBEDDING_MODEL_SETTING = 'ai_embedding_model'
const DEFAULT_EMBEDDING_MODEL = 'text-embedding-v3'

const embeddingApiKey = ref('')
const embeddingApiKeyConfigured = ref(false)
const embeddingBaseUrl = ref('')
const embeddingModel = ref(DEFAULT_EMBEDDING_MODEL)
const embeddingSaving = ref(false)
const showEmbeddingApiKey = ref(false)
const showEmbeddingConfig = ref(false)

// 邮箱通知配置
const EMAIL_SMTP_HOST_KEY = 'email_smtp_host'
const EMAIL_SMTP_PORT_KEY = 'email_smtp_port'
const EMAIL_SMTP_USERNAME_KEY = 'email_smtp_username'
const EMAIL_SMTP_PASSWORD_KEY = 'email_smtp_password'
const EMAIL_SMTP_FROM_KEY = 'email_smtp_from'
const EMAIL_SMTP_SSL_KEY = 'email_smtp_ssl'
const EMAIL_WS_DISCONNECT_NOTIFY_KEY = 'email_notify_ws_disconnect_enabled'
const EMAIL_COOKIE_EXPIRE_NOTIFY_KEY = 'email_notify_cookie_expire_enabled'
const EMAIL_CAPTCHA_NOTIFY_KEY = 'email_notify_captcha_enabled'

const emailSmtpHost = ref('')
const emailSmtpPort = ref('465')
const emailSmtpUsername = ref('')
const emailSmtpPassword = ref('')
const emailPasswordConfigured = ref(false)
const emailSmtpFrom = ref('')
const emailSmtpSsl = ref(true)
const emailSaving = ref(false)
const emailTesting = ref(false)
const showEmailPassword = ref(false)
const emailConfigured = ref(false)
const emailConfigExpanded = ref(true)
const wsDisconnectNotifyEnabled = ref(false)
const cookieExpireNotifyEnabled = ref(false)
const captchaNotifyEnabled = ref(false)

// 多渠道通知配置（企业微信/钉钉/Server酱/Bark/Telegram/ntfy）
const WECOM_WEBHOOK_KEY = 'notify_wecom_webhook'
const DINGTALK_WEBHOOK_KEY = 'notify_dingtalk_webhook'
const DINGTALK_SECRET_KEY = 'notify_dingtalk_secret'
const SERVERCHAN_SENDKEY_KEY = 'notify_serverchan_sendkey'
const BARK_DEVICE_KEY = 'notify_bark_device_key'
const BARK_SERVER_KEY = 'notify_bark_server'
const TELEGRAM_BOT_TOKEN_KEY = 'notify_telegram_bot_token'
const TELEGRAM_CHAT_ID_KEY = 'notify_telegram_chat_id'
const NTFY_TOPIC_KEY = 'notify_ntfy_topic'
const NTFY_SERVER_KEY = 'notify_ntfy_server'
const NTFY_TOKEN_KEY = 'notify_ntfy_token'

const wecomWebhook = ref('')
const dingtalkWebhook = ref('')
const dingtalkSecret = ref('')
const dingtalkSecretConfigured = ref(false)
const serverchanSendKey = ref('')
const serverchanSendKeyConfigured = ref(false)
const barkDeviceKey = ref('')
const barkDeviceKeyConfigured = ref(false)
const barkServer = ref('')
const telegramBotToken = ref('')
const telegramBotTokenConfigured = ref(false)
const telegramChatId = ref('')
const ntfyTopic = ref('')
const ntfyServer = ref('')
const ntfyToken = ref('')
const ntfyTokenConfigured = ref(false)
const notifySaving = ref(false)
const notifyHelpOpen = ref(false)
const notifyConfigs = ref([
  { key: 'wecom', label: '企业微信', desc: '企业微信群机器人 Webhook 地址', placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...', hint: '在企业微信群中添加"群机器人"获取 Webhook' },
  { key: 'dingtalk', label: '钉钉', desc: '钉钉群机器人 Webhook 地址（支持加签）', placeholder: 'https://oapi.dingtalk.com/robot/send?access_token=...', hint: '在钉钉群中添加"自定义机器人"获取 Webhook，安全设置可选加签' },
  { key: 'serverchan', label: 'Server酱', desc: 'Server酱 SendKey（sctapi.ftqq.com）', placeholder: 'SCTxxxxxxxxxxxxxxxxxx', hint: '在 sct.ftqq.com 获取 SendKey' },
  { key: 'bark', label: 'Bark', desc: 'Bark 设备 Key（iOS 推送）', placeholder: 'xxxxxxxxxxxxxxxxxxxxxx', hint: '在 App Store 安装 Bark 后从首页复制设备 Key' },
  { key: 'telegram', label: 'Telegram', desc: 'Telegram Bot Token + Chat ID', placeholder: '123456:ABC-DEF...', hint: '向 @BotFather 创建 Bot 获取 Token，向 @userinfobot 获取 Chat ID' },
  { key: 'ntfy', label: 'ntfy', desc: 'ntfy 主题（ntfy.sh 或自托管）', placeholder: 'my-alert-topic', hint: '订阅 https://ntfy.sh/你的主题 即可收到推送' }
])

// AI 状态
const aiStatus = ref({
  enabled: false,
  available: false,
  apiKeyConfigured: false,
  message: '',
  baseUrl: '',
  model: ''
})

// 菜单配置
const menuItems = [
  { key: 'account', label: '系统账号', icon: markRaw(IconUser) },
  { key: 'ai', label: 'AI 服务配置', icon: markRaw(IconRobot) },
  { key: 'prompt', label: 'AI客服配置', icon: markRaw(IconChat) },
  { key: 'notify', label: '通知渠道', icon: markRaw(IconMail) },
  { key: 'email', label: '邮箱通知', icon: markRaw(IconMail) },
  { key: 'proxy', label: '代理池', icon: markRaw(IconShield) },
  { key: 'backup', label: '备份与恢复', icon: markRaw(IconBackup) },
  { key: 'delivery', label: 'API 兜底发货', icon: markRaw(IconTruck) },
  { key: 'about', label: '关于', icon: markRaw(IconInfo) }
]

onMounted(async () => {
  loading.value = true
  try {
    const res = await getCurrentUser()
    if (res.code === 200 && res.data) {
      username.value = res.data.username || ''
      lastLoginTime.value = res.data.lastLoginTime || ''
    }
  } catch (e) {
    console.error('获取用户信息失败:', e)
  } finally {
    loading.value = false
  }

  // 加载系统提示词配置
  try {
    const res = await getSetting({ settingKey: SYS_PROMPT_KEY })
    if (res.code === 200 && res.data) {
      sysPromptValue.value = res.data.settingValue || ''
      sysPromptLoaded.value = true
    }
  } catch (e) {
    console.error('获取系统提示词配置失败:', e)
  }

  // 加载相似度阈值配置
  try {
    const res = await getSetting({ settingKey: SIMILARITY_THRESHOLD_KEY })
    if (res.code === 200 && res.data && res.data.settingValue) {
      similarityThreshold.value = parseFloat(res.data.settingValue) || DEFAULT_SIMILARITY_THRESHOLD
    }
  } catch (e) {
    console.error('获取相似度阈值配置失败:', e)
  }

  // 加载 AI 配置
  await loadAIConfig()
  // 加载 Embedding 配置
  await loadEmbeddingConfig()
  // 加载 AI 状态
  await loadAIStatus()
  // 加载邮箱通知配置
  await loadEmailConfig()
  // 加载多渠道通知配置
  await loadNotifyConfig()
})

async function loadAIConfig() {
  try {
    const [apiKeyRes, baseUrlRes, modelRes] = await Promise.all([
      getSetting({ settingKey: AI_API_KEY_SETTING }),
      getSetting({ settingKey: AI_BASE_URL_SETTING }),
      getSetting({ settingKey: AI_MODEL_SETTING })
    ])

    if (apiKeyRes.code === 200 && apiKeyRes.data) {
      aiApiKeyConfigured.value = apiKeyRes.data.hasSecret === true
      aiApiKey.value = aiApiKeyConfigured.value ? '' : (apiKeyRes.data.settingValue || '')
    }
    if (baseUrlRes.code === 200 && baseUrlRes.data && baseUrlRes.data.settingValue) {
      aiBaseUrl.value = baseUrlRes.data.settingValue
    }
    if (modelRes.code === 200 && modelRes.data && modelRes.data.settingValue) {
      aiModel.value = modelRes.data.settingValue
    }
  } catch (e) {
    console.error('获取AI配置失败:', e)
  }
}

async function loadEmbeddingConfig() {
  try {
    const [apiKeyRes, baseUrlRes, modelRes] = await Promise.all([
      getSetting({ settingKey: EMBEDDING_API_KEY_SETTING }),
      getSetting({ settingKey: EMBEDDING_BASE_URL_SETTING }),
      getSetting({ settingKey: EMBEDDING_MODEL_SETTING })
    ])

    if (apiKeyRes.code === 200 && apiKeyRes.data) {
      embeddingApiKeyConfigured.value = apiKeyRes.data.hasSecret === true
      embeddingApiKey.value = embeddingApiKeyConfigured.value ? '' : (apiKeyRes.data.settingValue || '')
    }
    if (baseUrlRes.code === 200 && baseUrlRes.data && baseUrlRes.data.settingValue) {
      embeddingBaseUrl.value = baseUrlRes.data.settingValue
    }
    if (modelRes.code === 200 && modelRes.data && modelRes.data.settingValue) {
      embeddingModel.value = modelRes.data.settingValue
    }
  } catch (e) {
    console.error('获取Embedding配置失败:', e)
  }
}

async function loadAIStatus() {
  try {
    const res = await getAIStatus()
    const data = await res.json()
    if (data.code === 200 && data.data) {
      aiStatus.value = data.data
    }
  } catch (e) {
    console.error('获取AI状态失败:', e)
  }
}

async function handleChangePassword() {
  if (!oldPassword.value) {
    toast.warning('请输入原密码')
    return
  }
  if (!newPassword.value || newPassword.value.length < 6) {
    toast.warning('新密码长度需在6-50之间')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    toast.warning('两次密码不一致')
    return
  }
  changingPassword.value = true
  try {
    const res = await changePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value
    })
    if (res.code === 200) {
      showPasswordForm.value = false
      oldPassword.value = ''
      newPassword.value = ''
      confirmPassword.value = ''
      clearAuthToken()
      toast.success('密码修改成功，请重新登录')
      await router.replace('/login')
    }
  } finally {
    changingPassword.value = false
  }
}

async function handleLogout() {
  try {
    await showConfirm(
      '确定要退出登录吗？',
      '退出确认'
    )

    loggingOut.value = true
    try {
      await logout()
      clearAuthToken()
      toast.success('已退出登录')
      router.push('/login')
    } catch (e) {
      console.error('退出登录失败:', e)
      // 即使接口失败，也清除本地token并跳转
      clearAuthToken()
      router.push('/login')
    } finally {
      loggingOut.value = false
    }
  } catch {
    // 用户取消
  }
}

async function handleSaveSysPrompt() {
  if (!sysPromptValue.value.trim()) {
    toast.warning('系统提示词不能为空')
    return
  }
  sysPromptSaving.value = true
  try {
    const res = await saveSetting({
      settingKey: SYS_PROMPT_KEY,
      settingValue: sysPromptValue.value,
      settingDesc: 'AI智能回复的系统提示词'
    })
    if (res.code === 200) {
      toast.success('系统提示词保存成功')
      sysPromptLoaded.value = true
    }
  } finally {
    sysPromptSaving.value = false
  }
}

function handleResetSysPrompt() {
  sysPromptValue.value = DEFAULT_SYS_PROMPT
}

async function handleSaveSimilarityThreshold() {
  if (similarityThreshold.value < 0 || similarityThreshold.value > 1) {
    toast.warning('相似度阈值必须在 0 到 1 之间')
    return
  }
  similarityThresholdSaving.value = true
  try {
    const res = await saveSetting({
      settingKey: SIMILARITY_THRESHOLD_KEY,
      settingValue: similarityThreshold.value.toString(),
      settingDesc: 'RAG向量搜索的相似度阈值（0-1之间，值越小匹配越宽松）'
    })
    if (res.code === 200) {
      toast.success('相似度阈值保存成功')
    }
  } catch (e) {
    console.error('保存相似度阈值失败:', e)
    toast.error('保存相似度阈值失败')
  } finally {
    similarityThresholdSaving.value = false
  }
}

function handleResetSimilarityThreshold() {
  similarityThreshold.value = DEFAULT_SIMILARITY_THRESHOLD
}

async function handleSaveAIConfig() {
  if (aiTesting.value) {
    toast.info('请等待 AI 连接测试完成后再保存配置')
    return
  }
  if (!aiApiKeyConfigured.value && !aiApiKey.value.trim()) {
    toast.warning('API Key 不能为空')
    return
  }
  if (!aiBaseUrl.value.trim()) {
    toast.warning('API Base URL 不能为空')
    return
  }
  if (!aiModel.value.trim()) {
    toast.warning('模型名称不能为空')
    return
  }

  aiApiKeySaving.value = true
  try {
    // 保存三个配置
    const [keyRes, urlRes, modelRes] = await Promise.all([
      saveSetting({
        settingKey: AI_API_KEY_SETTING,
        settingValue: aiApiKey.value.trim(),
        settingDesc: 'AI服务的API Key（配置后立即生效，无需重启）'
      }),
      saveSetting({
        settingKey: AI_BASE_URL_SETTING,
        settingValue: aiBaseUrl.value.trim(),
        settingDesc: 'AI服务的API Base URL'
      }),
      saveSetting({
        settingKey: AI_MODEL_SETTING,
        settingValue: aiModel.value.trim(),
        settingDesc: 'AI对话模型名称'
      })
    ])

    if (keyRes.code === 200 && urlRes.code === 200 && modelRes.code === 200) {
      toast.success('AI 配置保存成功，已立即生效')
      aiApiKeyConfigured.value = !!aiApiKey.value.trim()
      // 刷新 AI 状态
      await loadAIStatus()
    }
  } catch (e) {
    console.error('保存AI配置失败:', e)
    toast.error('保存AI配置失败')
  } finally {
    aiApiKeySaving.value = false
  }
}

async function handleFetchAIModels() {
  if (aiApiKeySaving.value) {
    toast.info('请等待 AI 配置保存完成后再获取模型')
    return
  }
  if (aiTesting.value) {
    toast.info('请等待 AI 连接测试完成后再获取模型')
    return
  }
  if (!aiApiKeyConfigured.value && !aiApiKey.value.trim()) {
    toast.warning('请先填写 API Key')
    return
  }
  if (!aiBaseUrl.value.trim()) {
    toast.warning('请先填写 API Base URL')
    return
  }

  aiModelLoading.value = true
  aiModelError.value = ''
  aiModelList.value = []
  try {
    // 后端从数据库读取已保存的 API Key / Base URL，先保存当前表单内容确保使用最新配置
    const [keyRes, urlRes] = await Promise.all([
      saveSetting({
        settingKey: AI_API_KEY_SETTING,
        settingValue: aiApiKey.value.trim(),
        settingDesc: 'AI服务的API Key（配置后立即生效，无需重启）'
      }),
      saveSetting({
        settingKey: AI_BASE_URL_SETTING,
        settingValue: aiBaseUrl.value.trim(),
        settingDesc: 'AI服务的API Base URL'
      })
    ])
    if (keyRes.code !== 200 || urlRes.code !== 200) {
      throw new Error('保存 API 配置失败，请稍后重试')
    }
    aiApiKeyConfigured.value = !!aiApiKey.value.trim()

    const response = await listModels()
    const result = (await response.json()) as { code: number; msg?: string; data?: AIModelItem[] | null }
    if (result.code !== 200) {
      aiModelError.value = result.msg || '获取模型失败'
      toast.error(aiModelError.value)
      return
    }
    aiModelList.value = result.data || []
    if (aiModelList.value.length === 0) {
      aiModelError.value = '未获取到可用模型，请检查 Base URL 与 API Key 是否匹配'
      toast.warning(aiModelError.value)
    } else {
      toast.success(`获取到 ${aiModelList.value.length} 个可用模型，请选择`)
    }
  } catch (e) {
    aiModelError.value = getErrorMessage(e, '获取模型失败，请检查 API Key 与 Base URL 后重试')
    toast.error(aiModelError.value)
  } finally {
    aiModelLoading.value = false
  }
}

async function handleTestAIConnection() {
  const message = aiTestMessage.value.trim()
  if (!message) {
    toast.warning('请输入测试消息')
    return
  }
  if (aiApiKeySaving.value) {
    toast.info('请等待 AI 配置保存完成后再测试')
    return
  }
  if (aiTesting.value) return

  aiTesting.value = true
  aiTestCompleted.value = false
  aiTestResponse.value = ''
  aiTestError.value = ''

  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), 60_000)

  try {
    // 不绑定账号或商品，后端会跳过固定资料查询，直接验证当前 AI 配置。
    const response = await chatTestWithAI({
      accountId: null,
      goodsId: '__settings_test__',
      msg: message
    }, controller.signal)
    const result = await consumeAIStream(response, (chunk) => {
      aiTestResponse.value += chunk
    })

    if (!result.trim()) {
      throw new Error('模型未返回内容，请检查模型名称和接口地址')
    }

    // 后端在配置缺失或上游调用失败时会以文本流返回降级提示，
    // 这类响应虽然 HTTP 状态为 200，也应在界面上明确标记为失败。
    const normalizedResult = result.trim()
    const errorMarkers = ['【AI服务错误】', 'AI服务暂未配置']
    const errorIndex = errorMarkers
      .map((marker) => normalizedResult.indexOf(marker))
      .filter((index) => index >= 0)
      .sort((a, b) => a - b)[0]
    if (errorIndex !== undefined) {
      aiTestError.value = normalizedResult.slice(errorIndex)
      aiTestResponse.value = ''
      return
    }

    aiTestCompleted.value = true
  } catch (error: unknown) {
    console.error('测试 AI 模型连接失败:', error)
    const isTimeout = error instanceof Error && error.name === 'AbortError'
    let messageText = '测试请求失败，请稍后重试'
    if (isTimeout) {
      messageText = '模型连接超时（60 秒），请检查接口地址或网络后重试'
    } else if (error instanceof Error) {
      messageText = error.message
    }
    aiTestError.value = messageText.length > 300 ? `${messageText.slice(0, 300)}…` : messageText
    aiTestResponse.value = ''
  } finally {
    window.clearTimeout(timeoutId)
    aiTesting.value = false
  }
}

function handleResetAIConfig() {
  aiApiKey.value = ''
  aiApiKeyConfigured.value = false
  aiBaseUrl.value = DEFAULT_BASE_URL
  aiModel.value = DEFAULT_MODEL
}

async function handleSaveEmbeddingConfig() {
  embeddingSaving.value = true
  try {
    // 保存三个配置（可以为空，空值表示使用 AI 对话配置）
    const [keyRes, urlRes, modelRes] = await Promise.all([
      saveSetting({
        settingKey: EMBEDDING_API_KEY_SETTING,
        settingValue: embeddingApiKey.value.trim(),
        settingDesc: 'Embedding模型API Key（留空则使用AI对话的API Key）'
      }),
      saveSetting({
        settingKey: EMBEDDING_BASE_URL_SETTING,
        settingValue: embeddingBaseUrl.value.trim(),
        settingDesc: 'Embedding模型API Base URL（留空则使用AI对话的Base URL）'
      }),
      saveSetting({
        settingKey: EMBEDDING_MODEL_SETTING,
        settingValue: embeddingModel.value.trim(),
        settingDesc: 'Embedding模型名称'
      })
    ])

    if (keyRes.code === 200 && urlRes.code === 200 && modelRes.code === 200) {
      toast.success('Embedding 配置保存成功，重启服务后生效')
    }
  } catch (e) {
    console.error('保存Embedding配置失败:', e)
    toast.error('保存Embedding配置失败')
  } finally {
    embeddingSaving.value = false
  }
}

function handleResetEmbeddingConfig() {
  embeddingApiKey.value = ''
  embeddingApiKeyConfigured.value = false
  embeddingBaseUrl.value = ''
  embeddingModel.value = DEFAULT_EMBEDDING_MODEL
}


async function loadEmailConfig() {
  try {
    const [hostRes, portRes, userRes, passRes, fromRes, sslRes, wsDisconnectRes, cookieExpireRes, captchaRes] = await Promise.all([
      getSetting({ settingKey: EMAIL_SMTP_HOST_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_PORT_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_USERNAME_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_PASSWORD_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_FROM_KEY }),
      getSetting({ settingKey: EMAIL_SMTP_SSL_KEY }),
      getSetting({ settingKey: EMAIL_WS_DISCONNECT_NOTIFY_KEY }),
      getSetting({ settingKey: EMAIL_COOKIE_EXPIRE_NOTIFY_KEY }),
      getSetting({ settingKey: EMAIL_CAPTCHA_NOTIFY_KEY })
    ])

    if (hostRes.code === 200 && hostRes.data) emailSmtpHost.value = hostRes.data.settingValue || ''
    if (portRes.code === 200 && portRes.data && portRes.data.settingValue) emailSmtpPort.value = portRes.data.settingValue
    if (userRes.code === 200 && userRes.data) emailSmtpUsername.value = userRes.data.settingValue || ''
    if (passRes.code === 200 && passRes.data) {
      emailPasswordConfigured.value = passRes.data.hasSecret === true
      emailSmtpPassword.value = emailPasswordConfigured.value ? '' : (passRes.data.settingValue || '')
    }
    if (fromRes.code === 200 && fromRes.data) emailSmtpFrom.value = fromRes.data.settingValue || ''
    if (sslRes.code === 200 && sslRes.data && sslRes.data.settingValue !== undefined) {
      emailSmtpSsl.value = sslRes.data.settingValue === '1' || sslRes.data.settingValue === 'true'
    } else {
      emailSmtpSsl.value = true
    }
    if (wsDisconnectRes.code === 200 && wsDisconnectRes.data && wsDisconnectRes.data.settingValue) {
      wsDisconnectNotifyEnabled.value = wsDisconnectRes.data.settingValue === '1' || wsDisconnectRes.data.settingValue === 'true'
    }
    if (cookieExpireRes.code === 200 && cookieExpireRes.data && cookieExpireRes.data.settingValue) {
      cookieExpireNotifyEnabled.value = cookieExpireRes.data.settingValue === '1' || cookieExpireRes.data.settingValue === 'true'
    }
    if (captchaRes.code === 200 && captchaRes.data && captchaRes.data.settingValue) {
      captchaNotifyEnabled.value = captchaRes.data.settingValue === '1' || captchaRes.data.settingValue === 'true'
    }

    emailConfigured.value = !!(emailSmtpHost.value && emailSmtpPort.value && emailSmtpUsername.value && (emailPasswordConfigured.value || emailSmtpPassword.value) && emailSmtpFrom.value)
    emailConfigExpanded.value = !emailConfigured.value
  } catch (e) {
    console.error('加载邮箱配置失败:', e)
  }
}

async function handleSaveEmailConfig() {
  emailSaving.value = true
  try {
    const [hostRes, portRes, userRes, passRes, fromRes, sslRes] = await Promise.all([
      saveSetting({ settingKey: EMAIL_SMTP_HOST_KEY, settingValue: emailSmtpHost.value.trim(), settingDesc: 'SMTP服务器地址' }),
      saveSetting({ settingKey: EMAIL_SMTP_PORT_KEY, settingValue: emailSmtpPort.value.trim(), settingDesc: 'SMTP服务器端口' }),
      saveSetting({ settingKey: EMAIL_SMTP_USERNAME_KEY, settingValue: emailSmtpUsername.value.trim(), settingDesc: 'SMTP登录用户名' }),
      saveSetting({ settingKey: EMAIL_SMTP_PASSWORD_KEY, settingValue: emailSmtpPassword.value.trim(), settingDesc: 'SMTP登录密码/授权码' }),
      saveSetting({ settingKey: EMAIL_SMTP_FROM_KEY, settingValue: emailSmtpFrom.value.trim(), settingDesc: '接收通知的收件人邮箱地址' }),
      saveSetting({ settingKey: EMAIL_SMTP_SSL_KEY, settingValue: emailSmtpSsl.value ? '1' : '0', settingDesc: '是否启用SSL（1启用，0关闭）' })
    ])

    if (hostRes.code === 200 && portRes.code === 200 && userRes.code === 200 && passRes.code === 200 && fromRes.code === 200 && sslRes.code === 200) {
      toast.success('邮箱配置保存成功')
      if (emailSmtpPassword.value.trim()) emailPasswordConfigured.value = true
      emailConfigured.value = !!(emailSmtpHost.value && emailSmtpPort.value && emailSmtpUsername.value && (emailPasswordConfigured.value || emailSmtpPassword.value) && emailSmtpFrom.value)
      emailConfigExpanded.value = !emailConfigured.value
    }
  } catch (e) {
    console.error('保存邮箱配置失败:', e)
    toast.error('保存邮箱配置失败')
  } finally {
    emailSaving.value = false
  }
}

async function handleSaveWsDisconnectNotify() {
  try {
    const res = await saveSetting({
      settingKey: EMAIL_WS_DISCONNECT_NOTIFY_KEY,
      settingValue: wsDisconnectNotifyEnabled.value ? '1' : '0',
      settingDesc: 'WebSocket断开且无法重连时邮箱通知开关（1启用，0关闭）'
    })
    if (res.code === 200) {
      toast.success(`闲鱼账号消息监听掉线通知已${wsDisconnectNotifyEnabled.value ? '开启' : '关闭'}`)
    }
  } catch (e) {
    console.error('保存通知开关失败:', e)
    toast.error('保存失败')
  }
}

async function handleSaveCookieExpireNotify() {
  try {
    const res = await saveSetting({
      settingKey: EMAIL_COOKIE_EXPIRE_NOTIFY_KEY,
      settingValue: cookieExpireNotifyEnabled.value ? '1' : '0',
      settingDesc: 'Cookie过期且无法续期时邮箱通知开关（1启用，0关闭）'
    })
    if (res.code === 200) {
      toast.success(`Cookie过期通知已${cookieExpireNotifyEnabled.value ? '开启' : '关闭'}`)
    }
  } catch (e) {
    console.error('保存通知开关失败:', e)
    toast.error('保存失败')
  }
}

async function handleSaveCaptchaNotify() {
  try {
    const res = await saveSetting({
      settingKey: EMAIL_CAPTCHA_NOTIFY_KEY,
      settingValue: captchaNotifyEnabled.value ? '1' : '0',
      settingDesc: '滑块及风控验证邮箱通知开关（1启用，0关闭）'
    })
    if (res.code === 200) {
      toast.success(`滑块验证通知已${captchaNotifyEnabled.value ? '开启' : '关闭'}`)
    }
  } catch (e) {
    console.error('保存通知开关失败:', e)
    toast.error('保存失败')
  }
}

// 多渠道通知配置
async function loadNotifyConfig() {
  try {
    const [wecomRes, dingtalkRes, secretRes, serverchanRes, barkKeyRes, barkServerRes, tgTokenRes, tgChatRes, ntfyTopicRes, ntfyServerRes, ntfyTokenRes] = await Promise.all([
      getSetting({ settingKey: WECOM_WEBHOOK_KEY }),
      getSetting({ settingKey: DINGTALK_WEBHOOK_KEY }),
      getSetting({ settingKey: DINGTALK_SECRET_KEY }),
      getSetting({ settingKey: SERVERCHAN_SENDKEY_KEY }),
      getSetting({ settingKey: BARK_DEVICE_KEY }),
      getSetting({ settingKey: BARK_SERVER_KEY }),
      getSetting({ settingKey: TELEGRAM_BOT_TOKEN_KEY }),
      getSetting({ settingKey: TELEGRAM_CHAT_ID_KEY }),
      getSetting({ settingKey: NTFY_TOPIC_KEY }),
      getSetting({ settingKey: NTFY_SERVER_KEY }),
      getSetting({ settingKey: NTFY_TOKEN_KEY })
    ])
    if (wecomRes.code === 200 && wecomRes.data) wecomWebhook.value = wecomRes.data.settingValue || ''
    if (dingtalkRes.code === 200 && dingtalkRes.data) dingtalkWebhook.value = dingtalkRes.data.settingValue || ''
    if (secretRes.code === 200 && secretRes.data) {
      dingtalkSecretConfigured.value = secretRes.data.hasSecret === true
      dingtalkSecret.value = dingtalkSecretConfigured.value ? '' : (secretRes.data.settingValue || '')
    }
    if (serverchanRes.code === 200 && serverchanRes.data) {
      serverchanSendKeyConfigured.value = serverchanRes.data.hasSecret === true
      serverchanSendKey.value = serverchanSendKeyConfigured.value ? '' : (serverchanRes.data.settingValue || '')
    }
    if (barkKeyRes.code === 200 && barkKeyRes.data) {
      barkDeviceKeyConfigured.value = barkKeyRes.data.hasSecret === true
      barkDeviceKey.value = barkDeviceKeyConfigured.value ? '' : (barkKeyRes.data.settingValue || '')
    }
    if (barkServerRes.code === 200 && barkServerRes.data) barkServer.value = barkServerRes.data.settingValue || ''
    if (tgTokenRes.code === 200 && tgTokenRes.data) {
      telegramBotTokenConfigured.value = tgTokenRes.data.hasSecret === true
      telegramBotToken.value = telegramBotTokenConfigured.value ? '' : (tgTokenRes.data.settingValue || '')
    }
    if (tgChatRes.code === 200 && tgChatRes.data) telegramChatId.value = tgChatRes.data.settingValue || ''
    if (ntfyTopicRes.code === 200 && ntfyTopicRes.data) ntfyTopic.value = ntfyTopicRes.data.settingValue || ''
    if (ntfyServerRes.code === 200 && ntfyServerRes.data) ntfyServer.value = ntfyServerRes.data.settingValue || ''
    if (ntfyTokenRes.code === 200 && ntfyTokenRes.data) {
      ntfyTokenConfigured.value = ntfyTokenRes.data.hasSecret === true
      ntfyToken.value = ntfyTokenConfigured.value ? '' : (ntfyTokenRes.data.settingValue || '')
    }
  } catch (e) {
    console.error('加载通知渠道配置失败:', e)
  }
}

async function handleSaveNotifyConfig() {
  notifySaving.value = true
  try {
    const [wecomRes, dingtalkRes, secretRes, serverchanRes, barkKeyRes, barkServerRes, tgTokenRes, tgChatRes, ntfyTopicRes, ntfyServerRes, ntfyTokenRes] = await Promise.all([
      saveSetting({ settingKey: WECOM_WEBHOOK_KEY, settingValue: wecomWebhook.value.trim(), settingDesc: '企业微信机器人Webhook地址' }),
      saveSetting({ settingKey: DINGTALK_WEBHOOK_KEY, settingValue: dingtalkWebhook.value.trim(), settingDesc: '钉钉机器人Webhook地址' }),
      saveSetting({ settingKey: DINGTALK_SECRET_KEY, settingValue: dingtalkSecret.value.trim(), settingDesc: '钉钉机器人加签密钥（可选）' }),
      saveSetting({ settingKey: SERVERCHAN_SENDKEY_KEY, settingValue: serverchanSendKey.value.trim(), settingDesc: 'Server酱SendKey' }),
      saveSetting({ settingKey: BARK_DEVICE_KEY, settingValue: barkDeviceKey.value.trim(), settingDesc: 'Bark设备Key' }),
      saveSetting({ settingKey: BARK_SERVER_KEY, settingValue: barkServer.value.trim(), settingDesc: 'Bark服务器地址' }),
      saveSetting({ settingKey: TELEGRAM_BOT_TOKEN_KEY, settingValue: telegramBotToken.value.trim(), settingDesc: 'Telegram Bot Token' }),
      saveSetting({ settingKey: TELEGRAM_CHAT_ID_KEY, settingValue: telegramChatId.value.trim(), settingDesc: 'Telegram Chat ID' }),
      saveSetting({ settingKey: NTFY_TOPIC_KEY, settingValue: ntfyTopic.value.trim(), settingDesc: 'ntfy主题' }),
      saveSetting({ settingKey: NTFY_SERVER_KEY, settingValue: ntfyServer.value.trim(), settingDesc: 'ntfy服务器地址' }),
      saveSetting({ settingKey: NTFY_TOKEN_KEY, settingValue: ntfyToken.value.trim(), settingDesc: 'ntfy访问令牌' })
    ])
    if (wecomRes.code === 200 && dingtalkRes.code === 200 && secretRes.code === 200 && serverchanRes.code === 200 && barkKeyRes.code === 200 && barkServerRes.code === 200 && tgTokenRes.code === 200 && tgChatRes.code === 200 && ntfyTopicRes.code === 200 && ntfyServerRes.code === 200 && ntfyTokenRes.code === 200) {
      toast.success('通知渠道配置保存成功')
      if (dingtalkSecret.value.trim()) dingtalkSecretConfigured.value = true
      if (serverchanSendKey.value.trim()) serverchanSendKeyConfigured.value = true
      if (barkDeviceKey.value.trim()) barkDeviceKeyConfigured.value = true
      if (telegramBotToken.value.trim()) telegramBotTokenConfigured.value = true
      if (ntfyToken.value.trim()) ntfyTokenConfigured.value = true
    }
  } catch (e) {
    console.error('保存通知渠道配置失败:', e)
    toast.error('保存通知渠道配置失败')
  } finally {
    notifySaving.value = false
  }
}

async function clearSecretSetting(settingKey: string, onCleared?: () => void) {
  try {
    await showConfirm('确定清空该密钥吗？清空后相关功能将不可用，直到重新配置。')
  } catch {
    return // 用户取消
  }
  try {
    const res = await saveSetting({ settingKey, settingValue: '', clearSecret: true })
    if (res.code === 200) {
      toast.success('密钥已清空')
      onCleared?.()
    }
  } catch (e) {
    console.error('清空密钥失败:', e)
    toast.error('清空密钥失败')
  }
}

const getNotifyPlaceholder = (key: string): string => {
  const item = notifyConfigs.value.find((c) => c.key === key)
  return item ? item.placeholder : ''
}

const getNotifyHint = (key: string): string => {
  const item = notifyConfigs.value.find((c) => c.key === key)
  return item ? item.hint : ''
}

const notifyConfigured = (key: string): boolean => {
  switch (key) {
    case 'wecom': return !!wecomWebhook.value.trim()
    case 'dingtalk': return !!dingtalkWebhook.value.trim()
    case 'serverchan': return serverchanSendKeyConfigured.value || !!serverchanSendKey.value.trim()
    case 'bark': return barkDeviceKeyConfigured.value || !!barkDeviceKey.value.trim()
    case 'telegram': return (telegramBotTokenConfigured.value || !!telegramBotToken.value.trim()) && !!telegramChatId.value.trim()
    case 'ntfy': return ntfyTokenConfigured.value || !!ntfyTopic.value.trim()
    default: return false
  }
}


async function handleTestEmail() {
  emailTesting.value = true
  try {
    const res = await testEmail()
    if (res.code === 200) {
      toast.success('测试邮件发送成功，请检查收件箱')
    } else {
      toast.error(res.msg || '测试邮件发送失败')
    }
  } catch (e: unknown) {
    console.error('测试邮箱失败:', e)
    toast.error(getErrorMessage(e, '测试邮箱失败'))
  } finally {
    emailTesting.value = false
  }
}

// 多渠道测试推送：notifyTesting 记录正在测试的渠道 key
const notifyTesting = ref<string | null>(null)
async function handleTestNotify(channel: string) {
  notifyTesting.value = channel
  try {
    const res = await testNotifyChannel(channel)
    if (res.code === 200) {
      toast.success('测试推送已发送，请查看对应渠道')
    } else {
      toast.error(res.msg || '测试推送发送失败')
    }
  } catch (e: unknown) {
    console.error('测试推送失败:', e)
    toast.error(getErrorMessage(e, '测试推送失败'))
  } finally {
    notifyTesting.value = null
  }
}

// 备份与恢复
const backupModules = ref<BackupModule[]>([])
const backupSelectedModules = ref<string[]>([])
const backupLoaded = ref(false)
const backupExporting = ref(false)
const backupImporting = ref(false)
const backupImportProgress = ref(0)
const backupImportAvailableModules = ref<BackupModule[]>([])
const backupImportSelectedModules = ref<string[]>([])
const backupSchedule = ref<BackupScheduleStatus | null>(null)
const backupScheduleLoading = ref(false)

const logDates = ref<string[]>([])
const logSelectedDate = ref('')
const logDownloading = ref(false)
const logDatesLoaded = ref(false)

async function loadBackupModules() {
  if (backupLoaded.value) return
  try {
    const res = await getBackupModules()
    if (res.code === 200 && res.data) {
      backupModules.value = res.data
      backupSelectedModules.value = res.data.map((m: BackupModule) => m.moduleKey)
      backupLoaded.value = true
    }
  } catch (e) {
    console.error('获取备份模块列表失败:', e)
  }
}

async function loadBackupSchedule() {
  backupScheduleLoading.value = true
  try {
    const res = await getBackupSchedule()
    if (res.code === 200 && res.data) backupSchedule.value = res.data
  } catch (e) {
    console.error('获取自动备份状态失败:', e)
  } finally {
    backupScheduleLoading.value = false
  }
}

async function loadLogDates() {
  if (logDatesLoaded.value) return
  try {
    const res = await getLogDates()
    if (res.code === 200 && res.data) {
      logDates.value = res.data
      const today = new Date().toISOString().slice(0, 10)
      logSelectedDate.value = res.data.includes(today) ? today : (res.data.length > 0 ? res.data[res.data.length - 1]! : '')
      logDatesLoaded.value = true
    }
  } catch (e) {
    console.error('获取日志日期列表失败:', e)
  }
}

async function handleDownloadLog() {
  if (!logSelectedDate.value) return
  logDownloading.value = true
  try {
    const blob = await downloadLog(logSelectedDate.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `logs-${logSelectedDate.value}.zip`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (e) {
    console.error('下载日志失败:', e)
    toast.error('下载日志失败')
  } finally {
    setTimeout(() => { logDownloading.value = false }, 2000)
  }
}

function toggleBackupModule(key: string) {
  const idx = backupSelectedModules.value.indexOf(key)
  if (idx >= 0) {
    backupSelectedModules.value.splice(idx, 1)
  } else {
    backupSelectedModules.value.push(key)
  }
}

function toggleAllBackupModules() {
  if (backupSelectedModules.value.length === backupModules.value.length) {
    backupSelectedModules.value = []
  } else {
    backupSelectedModules.value = backupModules.value.map(m => m.moduleKey)
  }
}

function toggleBackupImportModule(key: string) {
  const idx = backupImportSelectedModules.value.indexOf(key)
  if (idx >= 0) {
    backupImportSelectedModules.value.splice(idx, 1)
  } else if (backupImportAvailableModules.value.some(module => module.moduleKey === key)) {
    backupImportSelectedModules.value.push(key)
  }
}

const backupIncludeSecrets = ref(false)

async function handleExportBackup() {
  if (backupSelectedModules.value.length === 0) {
    toast.warning('请至少选择一个模块')
    return
  }
  if (backupIncludeSecrets.value && !window.confirm('即将导出包含明文 Cookie、Token 和 API 密钥的备份，任何拿到此文件的人都可以直接登录你的账号。确定继续吗？')) {
    return
  }
  backupExporting.value = true
  try {
    // 走流式接口：备份直接落成 Blob，不再先变成一个大字符串塞进 JS 堆。
    // 十万级聊天记录的备份用旧接口很容易把页面卡死甚至崩掉。
    // 流式响应没有总长度，没法算真实百分比，所以不再伪造进度条。
    const blob = await downloadBackupStream({
      modules: backupSelectedModules.value,
      includeSecrets: backupIncludeSecrets.value
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    const now = new Date()
    const ts = `${now.getFullYear()}${String(now.getMonth()+1).padStart(2,'0')}${String(now.getDate()).padStart(2,'0')}_${String(now.getHours()).padStart(2,'0')}${String(now.getMinutes()).padStart(2,'0')}${String(now.getSeconds()).padStart(2,'0')}`
    a.download = `xianyu_backup_${ts}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    // 浏览器可能还没开始读取 Blob，立即 revoke 会让大文件下载中断
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    toast.success('备份导出成功')
  } catch (e: unknown) {
    console.error('导出备份失败:', e)
    toast.error(getErrorMessage(e, '导出失败'))
  } finally {
    backupExporting.value = false
  }
}

const importFileInput = ref<HTMLInputElement | null>(null)
const importJsonData = ref('')
const importFileName = ref('')

function triggerImportFile() {
  importFileInput.value?.click()
}

function handleImportFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  importFileName.value = ''
  importJsonData.value = ''
  backupImportAvailableModules.value = []
  backupImportSelectedModules.value = []
  const reader = new FileReader()
  reader.onload = async (ev) => {
    try {
      const jsonData = String(ev.target?.result ?? '')
      const parsed = JSON.parse(jsonData) as { modules?: unknown }
      if (!parsed.modules || typeof parsed.modules !== 'object' || Array.isArray(parsed.modules)) {
        throw new Error('备份文件缺少 modules 数据')
      }
      await loadBackupModules()
      const fileModuleKeys = new Set(Object.keys(parsed.modules as Record<string, unknown>))
      const availableModules = backupModules.value.filter(module => fileModuleKeys.has(module.moduleKey))
      if (availableModules.length === 0) {
        throw new Error('备份文件中没有当前版本可导入的模块')
      }
      importFileName.value = file.name
      importJsonData.value = jsonData
      backupImportAvailableModules.value = availableModules
      backupImportSelectedModules.value = availableModules.map(module => module.moduleKey)
    } catch (error: unknown) {
      importFileName.value = ''
      importJsonData.value = ''
      backupImportAvailableModules.value = []
      backupImportSelectedModules.value = []
      if (importFileInput.value) importFileInput.value.value = ''
      toast.error(getErrorMessage(error, '备份文件格式错误'))
    }
  }
  reader.onerror = () => {
    if (importFileInput.value) importFileInput.value.value = ''
    toast.error('读取备份文件失败')
  }
  reader.readAsText(file)
}

async function handleImportBackup() {
  if (!importJsonData.value) {
    toast.warning('请先选择备份文件')
    return
  }
  if (backupImportSelectedModules.value.length === 0) {
    toast.warning('请至少选择一个模块')
    return
  }

  try {
    await showConfirm(
      '导入数据将覆盖当前选中模块的已有数据，是否继续？',
      '确认导入'
    )
  } catch {
    return
  }

  backupImporting.value = true
  backupImportProgress.value = 0
  try {
    const total = backupImportSelectedModules.value.length
    const progressStep = 100 / (total + 1)
    for (let i = 0; i < total; i++) {
      backupImportProgress.value = Math.round((i + 1) * progressStep)
      await new Promise(r => setTimeout(r, 100))
    }
    const res = await importBackup({
      jsonData: importJsonData.value,
      modules: backupImportSelectedModules.value
    })
    if (res.code === 200 && res.data) {
      backupImportProgress.value = 100
      const result = res.data
      if (result.failedModules && result.failedModules.length > 0) {
        toast.warning(`导入完成：${result.successCount}/${result.totalCount} 成功，失败模块：${result.failedModules.join(', ')}`)
      } else {
        toast.success(`导入成功：${result.successCount} 个模块`)
      }
      importJsonData.value = ''
      importFileName.value = ''
      backupImportAvailableModules.value = []
      backupImportSelectedModules.value = []
      if (importFileInput.value) importFileInput.value.value = ''
    } else {
      toast.error(res.msg || '导入失败')
    }
  } catch (e: unknown) {
    console.error('导入备份失败:', e)
    toast.error(getErrorMessage(e, '导入失败'))
  } finally {
    backupImporting.value = false
    backupImportProgress.value = 0
  }
}

function handleBackupMenuEnter() {
  loadBackupModules()
  loadBackupSchedule()
  loadLogDates()
}
</script>

<template>
  <div class="settings">
    <!-- 左侧菜单 -->
    <div class="settings__sidebar">
      <div class="settings__sidebar-title">设置</div>
      <div class="settings__menu">
        <div
          v-for="item in menuItems"
          :key="item.key"
          class="settings__menu-item"
          :class="{ 'settings__menu-item--active': activeMenu === item.key }"
          @click="activeMenu = item.key; item.key === 'backup' && handleBackupMenuEnter()"
        >
          <span class="settings__menu-icon"><component :is="item.icon" /></span>
          <span class="settings__menu-label">{{ item.label }}</span>
        </div>
      </div>
    </div>

    <!-- 右侧内容 -->
    <div class="settings__content">
      <!-- 系统账号（包含修改密码和退出登录） -->
      <div v-if="activeMenu === 'account'" class="settings__panel">
        <div class="settings__panel-title">系统账号</div>
        <div v-if="loading" class="settings__loading">
          <div class="settings__spinner"></div>
          <span>加载中...</span>
        </div>
        <div v-else class="settings__info">
          <div class="settings__info-row">
            <span class="settings__info-label">账号</span>
            <span class="settings__info-value">{{ username }}</span>
          </div>
          <div class="settings__info-row">
            <span class="settings__info-label">最后登录时间</span>
            <span class="settings__info-value">{{ lastLoginTime || '-' }}</span>
          </div>
        </div>

        <!-- 修改密码 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div class="settings__section-title">修改密码</div>
            <button
              v-if="!showPasswordForm"
              class="settings__toggle-btn"
              @click="showPasswordForm = true"
            >
              修改
            </button>
          </div>
          <div v-if="showPasswordForm" class="settings__form">
            <div class="settings__field">
              <label class="settings__label">原密码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="oldPassword"
                  :type="showOldPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="请输入原密码"
                  :disabled="changingPassword"
                />
                <button class="settings__eye-btn" @click="showOldPassword = !showOldPassword" tabindex="-1">
                  {{ showOldPassword ? '隐藏' : '显示' }}
                </button>
              </div>
            </div>

            <div class="settings__field">
              <label class="settings__label">新密码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="newPassword"
                  :type="showNewPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="请输入新密码（6-50位）"
                  :disabled="changingPassword"
                />
                <button class="settings__eye-btn" @click="showNewPassword = !showNewPassword" tabindex="-1">
                  {{ showNewPassword ? '隐藏' : '显示' }}
                </button>
              </div>
            </div>

            <div class="settings__field">
              <label class="settings__label">确认新密码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="confirmPassword"
                  :type="showConfirmPassword ? 'text' : 'password'"
                  class="settings__input"
                  placeholder="请再次输入新密码"
                  :disabled="changingPassword"
                  @keydown.enter="handleChangePassword"
                />
                <button class="settings__eye-btn" @click="showConfirmPassword = !showConfirmPassword" tabindex="-1">
                  {{ showConfirmPassword ? '隐藏' : '显示' }}
                </button>
              </div>
            </div>

            <div class="settings__actions">
              <button class="settings__btn settings__btn--secondary" :disabled="changingPassword" @click="showPasswordForm = false">
                取消
              </button>
              <button class="settings__btn settings__btn--primary" :disabled="changingPassword" @click="handleChangePassword">
                {{ changingPassword ? '请稍候...' : '确认修改' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 退出登录 -->
        <div class="settings__section">
          <div class="settings__section-title">退出登录</div>
          <p class="settings__logout-text">退出当前系统账号，退出后需要重新登录</p>
          <button
            class="settings__btn settings__btn--danger"
            :disabled="loggingOut"
            @click="handleLogout"
          >
            {{ loggingOut ? '退出中...' : '退出登录' }}
          </button>
        </div>
      </div>

      <!-- AI 服务配置（包含 Embedding 配置和系统提示词） -->
      <div v-if="activeMenu === 'ai'" class="settings__panel">
        <div class="settings__panel-title">AI 服务配置</div>

        <!-- AI 状态指示 -->
        <div class="settings__ai-status">
          <div class="settings__ai-status-row">
            <span class="settings__info-label">服务状态</span>
            <span class="settings__ai-status-badge" :class="aiStatus.available ? 'settings__ai-status-badge--ok' : 'settings__ai-status-badge--off'">
              {{ aiStatus.available ? '可用' : '不可用' }}
            </span>
          </div>
          <div v-if="aiStatus.message" class="settings__ai-status-row">
            <span class="settings__info-label">状态说明</span>
            <span class="settings__info-value settings__ai-status-msg">{{ aiStatus.message }}</span>
          </div>
          <div v-if="aiStatus.baseUrl" class="settings__ai-status-row">
            <span class="settings__info-label">Base URL</span>
            <span class="settings__info-value">{{ aiStatus.baseUrl }}</span>
          </div>
          <div v-if="aiStatus.model" class="settings__ai-status-row">
            <span class="settings__info-label">模型</span>
            <span class="settings__info-value">{{ aiStatus.model }}</span>
          </div>
        </div>

        <!-- AI 对话配置 -->
        <div class="settings__section">
          <div class="settings__section-title">对话模型配置</div>
          <p class="settings__desc">配置 AI 对话服务，配置后立即生效，无需重启服务</p>
          <div class="settings__form">
            <div class="settings__field">
              <label class="settings__label">
                API Key
                <span class="settings__label-hint">（本项目默认使用 AliApiKey，请从这里获取：<a href="https://bailian.console.aliyun.com/" target="_blank" class="settings__link">https://bailian.console.aliyun.com/</a>）</span>
              </label>
              <div class="settings__input-wrap">
                <input
                  v-model="aiApiKey"
                  :type="showApiKey ? 'text' : 'password'"
                  class="settings__input"
                  :placeholder="aiApiKeyConfigured ? '已配置，留空保持不变' : '请输入 AI API Key'"
                  :disabled="aiApiKeySaving || aiTesting"
                />
                <button class="settings__eye-btn" :disabled="aiApiKeySaving || aiTesting" @click="showApiKey = !showApiKey" tabindex="-1">
                  {{ showApiKey ? '隐藏' : '显示' }}
                </button>
              </div>
              <button
                v-if="aiApiKeyConfigured"
                class="settings__clear-btn"
                :disabled="aiApiKeySaving || aiTesting"
                @click="clearSecretSetting(AI_API_KEY_SETTING, () => { aiApiKeyConfigured = false; aiApiKey = '' })"
              >
                清空已配置的密钥
              </button>
            </div>

            <div class="settings__field">
              <label class="settings__label">API Base URL</label>
              <input
                v-model="aiBaseUrl"
                type="text"
                class="settings__input"
                placeholder="如 https://dashscope.aliyuncs.com/compatible-mode 或 https://open.bigmodel.cn/api/paas/v4"
                :disabled="aiApiKeySaving || aiTesting"
              />
              <p class="settings__field-hint">兼容 OpenAI 格式；带版本号的地址（如智谱 v4）会自动拼接正确的接口路径，无需以 /v1 结尾</p>
            </div>

            <div class="settings__field">
              <label class="settings__label">模型名称</label>
              <div class="settings__input-wrap">
                <input
                  v-model="aiModel"
                  type="text"
                  class="settings__input settings__input--flex"
                  placeholder="AI 对话模型名称"
                  :disabled="aiApiKeySaving || aiTesting || aiModelLoading"
                />
                <button
                  class="settings__btn settings__btn--get-models"
                  :disabled="aiApiKeySaving || aiTesting || aiModelLoading"
                  @click="handleFetchAIModels"
                >
                  {{ aiModelLoading ? '获取中...' : '获取模型' }}
                </button>
              </div>
              <select
                v-if="aiModelList.length"
                v-model="aiModel"
                class="settings__input settings__model-select"
                :disabled="aiApiKeySaving || aiTesting"
                aria-label="选择模型"
              >
                <option value="" disabled>请选择模型</option>
                <option v-for="m in aiModelList" :key="m.id" :value="m.id">
                  {{ m.id }}{{ m.ownedBy ? '（' + m.ownedBy + '）' : '' }}
                </option>
              </select>
              <p v-if="aiModelError" class="settings__field-hint settings__field-hint--error">{{ aiModelError }}</p>
              <p v-else-if="aiModelList.length" class="settings__field-hint">已获取 {{ aiModelList.length }} 个模型，可下拉选择或在输入框中手动填写</p>
            </div>

            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--secondary"
                :disabled="aiApiKeySaving || aiTesting"
                @click="handleResetAIConfig"
              >
                恢复默认
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="aiApiKeySaving || aiTesting"
                @click="handleSaveAIConfig"
              >
                {{ aiApiKeySaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>

          <!-- 连接测试：不修改配置，只读取当前已保存的 AI 设置并流式返回结果 -->
          <div class="settings__ai-test" :class="{ 'settings__ai-test--active': aiTesting, 'settings__ai-test--success': aiTestCompleted, 'settings__ai-test--error': aiTestError }">
            <div class="settings__ai-test-header">
              <div>
                <div class="settings__ai-test-title">测试模型连接</div>
                <p class="settings__ai-test-desc">发送一条测试消息，确认当前模型可以正常返回内容</p>
              </div>
              <span v-if="aiTesting" class="settings__ai-test-status">连接中...</span>
              <span v-else-if="aiTestCompleted" class="settings__ai-test-status">连接正常</span>
              <span v-else-if="aiTestError" class="settings__ai-test-status">连接失败</span>
            </div>

            <textarea
              v-model="aiTestMessage"
              class="settings__ai-test-input"
              id="settings-ai-test-message"
              rows="3"
              maxlength="2000"
              aria-label="测试消息"
              placeholder="输入测试消息"
              :disabled="aiTesting"
              @keydown.ctrl.enter.prevent="handleTestAIConnection"
              @keydown.meta.enter.prevent="handleTestAIConnection"
            ></textarea>

            <div class="settings__ai-test-actions">
              <span class="settings__ai-test-hint">使用当前已保存的配置，测试不会修改上面的内容</span>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="aiTesting || aiApiKeySaving || !aiTestMessage.trim()"
                @click="handleTestAIConnection"
              >
                <IconWifi aria-hidden="true" />
                {{ aiTesting ? '测试中...' : '测试模型连接' }}
              </button>
            </div>

            <div v-if="aiTesting || aiTestResponse" class="settings__ai-test-response" role="status" aria-live="polite">
              <span class="settings__ai-test-response-label">模型响应</span>
              <span class="settings__ai-test-response-text">{{ aiTestResponse || '等待模型响应...' }}</span>
            </div>
            <div v-if="aiTestError" class="settings__ai-test-error" role="alert">
              {{ aiTestError }}
            </div>
          </div>
        </div>

        <!-- Embedding 配置 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div class="settings__section-title">Embedding 模型配置</div>
            <button
              class="settings__toggle-btn"
              @click="showEmbeddingConfig = !showEmbeddingConfig"
            >
              {{ showEmbeddingConfig ? '收起' : '高级配置' }}
            </button>
          </div>
          <p class="settings__desc">
            配置向量嵌入模型（用于 RAG 知识库）。默认共用对话模型的配置。
            <strong>注意：修改后需要重启服务才能生效。</strong>
          </p>
          <div class="settings__form">
            <!-- 折叠内容 -->
            <div v-if="showEmbeddingConfig" class="settings__collapse-content">
              <div class="settings__field">
                <label class="settings__label">API Key <span class="settings__label-hint">(留空则使用对话模型的 API Key)</span></label>
                <div class="settings__input-wrap">
                  <input
                    v-model="embeddingApiKey"
                    :type="showEmbeddingApiKey ? 'text' : 'password'"
                    class="settings__input"
                    :placeholder="embeddingApiKeyConfigured ? '已配置，留空保持不变' : '留空则使用对话模型的 API Key'"
                    :disabled="embeddingSaving"
                  />
                  <button class="settings__eye-btn" @click="showEmbeddingApiKey = !showEmbeddingApiKey" tabindex="-1">
                    {{ showEmbeddingApiKey ? '隐藏' : '显示' }}
                  </button>
                </div>
                <button
                  v-if="embeddingApiKeyConfigured"
                  class="settings__clear-btn"
                  :disabled="embeddingSaving"
                  @click="clearSecretSetting(EMBEDDING_API_KEY_SETTING, () => { embeddingApiKeyConfigured = false; embeddingApiKey = '' })"
                >
                  清空已配置的密钥
                </button>
              </div>

              <div class="settings__field">
                <label class="settings__label">API Base URL <span class="settings__label-hint">(留空则使用对话模型的 Base URL)</span></label>
                <input
                  v-model="embeddingBaseUrl"
                  type="text"
                  class="settings__input"
                  placeholder="留空则使用对话模型的 Base URL"
                  :disabled="embeddingSaving"
                />
              </div>
            </div>

            <div class="settings__field">
              <label class="settings__label">模型名称</label>
              <input
                v-model="embeddingModel"
                type="text"
                class="settings__input"
                placeholder="Embedding 模型名称，如 text-embedding-v3"
                :disabled="embeddingSaving"
              />
            </div>

            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--secondary"
                :disabled="embeddingSaving"
                @click="handleResetEmbeddingConfig"
              >
                恢复默认
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="embeddingSaving"
                @click="handleSaveEmbeddingConfig"
              >
                {{ embeddingSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- AI客服配置 -->
      <div v-if="activeMenu === 'prompt'" class="settings__panel">
        <div class="settings__panel-title">AI客服配置</div>

        <!-- 系统提示词 -->
        <div class="settings__section">
          <div class="settings__section-title">系统提示词</div>
          <p class="settings__desc">配置 AI 智能回复的系统提示词，用于设定 AI 的角色和行为规则</p>
          <div class="settings__form">
            <textarea
              v-model="sysPromptValue"
              class="settings__textarea"
              placeholder="请输入系统提示词"
              :disabled="sysPromptSaving"
              rows="8"
            ></textarea>
            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--secondary"
                :disabled="sysPromptSaving"
                @click="handleResetSysPrompt"
              >
                恢复默认
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="sysPromptSaving"
                @click="handleSaveSysPrompt"
              >
                {{ sysPromptSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 相似度阈值 -->
        <div class="settings__section">
          <div class="settings__section-title">相似度阈值</div>
          <p class="settings__desc">
            配置 RAG 向量搜索的相似度阈值。值越小，匹配越宽松，会返回更多相关度较低的结果；值越大，匹配越严格，只返回高度相关的结果。
          </p>
          <div class="settings__form">
            <div class="settings__field">
              <label class="settings__label">相似度阈值 (0-1)</label>
              <input
                v-model.number="similarityThreshold"
                type="number"
                class="settings__input"
                placeholder="0.1"
                :disabled="similarityThresholdSaving"
                min="0"
                max="1"
                step="0.01"
              />
              <p class="settings__hint">推荐值：0.1（宽松）到 0.5（严格）之间</p>
            </div>
            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--secondary"
                :disabled="similarityThresholdSaving"
                @click="handleResetSimilarityThreshold"
              >
                恢复默认
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="similarityThresholdSaving"
                @click="handleSaveSimilarityThreshold"
              >
                {{ similarityThresholdSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 多渠道通知 -->
      <div v-if="activeMenu === 'notify'" class="settings__panel">
        <div class="settings__panel-title">通知渠道</div>
        <p class="settings__desc">配置企业微信、钉钉、Server酱等渠道后，系统通知（掉线、Cookie过期、卡密预警、发货失败、风控验证）将同时推送到这些渠道。</p>

        <div class="settings__section">
          <div class="settings__section-header">
            <div class="settings__section-title">渠道配置</div>
            <div class="settings__section-header-actions">
              <button
                class="settings__btn"
                @click="notifyHelpOpen = !notifyHelpOpen"
              >
                {{ notifyHelpOpen ? '收起教程' : '使用教程' }}
              </button>
              <button
                class="settings__btn settings__btn--primary"
                :disabled="notifySaving"
                @click="handleSaveNotifyConfig"
              >
                {{ notifySaving ? '保存中...' : '保存全部' }}
              </button>
            </div>
          </div>

          <div v-if="notifyHelpOpen" class="settings__notify-help">
            <p class="settings__notify-help__intro">
              配置任意一个渠道并保存后，系统事件（账号掉线、Cookie 过期、卡密库存预警、自动发货失败、风控验证提醒）都会推送到该渠道。多渠道可同时启用，各渠道互相独立，填错只影响该渠道本身。
            </p>
            <ol class="settings__notify-help__list">
              <li>
                <strong>企业微信</strong>：PC 端打开需要接收通知的微信群 → 右键群 →「添加群机器人」→ 选择新建 → 复制 Webhook 地址（https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx）填入即可。适合公司/团队群接收。
              </li>
              <li>
                <strong>钉钉</strong>：钉钉群 → 群设置 →「机器人」→ 添加机器人 →「自定义（通过 Webhook 接入）」→ 安全设置建议选「加签」并复制 SEC 密钥 → 勾选同意条款 → 复制 Webhook 地址。Webhook 与加签密钥两项都要填。
              </li>
              <li>
                <strong>Server酱</strong>：打开 sct.ftqq.com → 微信扫码登录 → 首页复制 SendKey 填入 → 按提示在微信里关注「方糖」服务号，之后通知以微信消息送达。
              </li>
              <li>
                <strong>Bark（iPhone）</strong>：App Store 安装 Bark → 打开 App 复制示例推送 URL 中「你的 Key」（https://api.day.app/<b>这里是Key</b>/推送内容）→ 填入 Bark 设备 Key。服务器地址留空即用官方服务器。
              </li>
              <li>
                <strong>Telegram</strong>：与 @BotFather 对话发送 /newbot 建立机器人，复制 Bot Token；再给自己的机器人发一条消息后，向 @userinfobot 发送任意内容获取你的 Chat ID（或访问 getUpdates 接口查看）。Token 与 Chat ID 都要填。
              </li>
              <li>
                <strong>ntfy</strong>：手机安装 ntfy App（或打开 ntfy.sh）→ 订阅一个自定义主题名（如 xianyu-alert-xxxx，越独特越安全）→ 把主题名填入此处。自托管服务器改填服务器地址；主题设置了访问控制时再填令牌。
              </li>
            </ol>
            <p class="settings__notify-help__tip">
              提示：保存后可点击各渠道的「发送测试」按钮验证配置是否生效（测试使用已保存的配置，不会修改上面填写的未保存内容）；邮箱通知在「邮箱通知」页单独配置，同样支持发送测试邮件。
            </p>
          </div>

          <!-- 企业微信 -->
          <div class="settings__notify-channel">
            <div class="settings__field">
              <label class="settings__label">
                企业微信
                <span v-if="notifyConfigured('wecom')" class="settings__status-badge settings__status-badge--success">已配置</span>
                <span v-else class="settings__status-badge">未配置</span>
                <button
                  class="settings__btn settings__notify-test-btn"
                  :disabled="notifyTesting === 'wecom'"
                  @click.prevent="handleTestNotify('wecom')"
                >
                  {{ notifyTesting === 'wecom' ? '发送中...' : '发送测试' }}
                </button>
              </label>
              <input
                v-model="wecomWebhook"
                type="text"
                class="settings__input"
                :placeholder="getNotifyPlaceholder('wecom')"
                :disabled="notifySaving"
              />
              <p class="settings__hint">{{ getNotifyHint('wecom') }}</p>
            </div>
          </div>

          <!-- 钉钉 -->
          <div class="settings__notify-channel">
            <div class="settings__field">
              <label class="settings__label">
                钉钉
                <span v-if="notifyConfigured('dingtalk')" class="settings__status-badge settings__status-badge--success">已配置</span>
                <span v-else class="settings__status-badge">未配置</span>
                <button
                  class="settings__btn settings__notify-test-btn"
                  :disabled="notifyTesting === 'dingtalk'"
                  @click.prevent="handleTestNotify('dingtalk')"
                >
                  {{ notifyTesting === 'dingtalk' ? '发送中...' : '发送测试' }}
                </button>
              </label>
              <input
                v-model="dingtalkWebhook"
                type="text"
                class="settings__input"
                :placeholder="getNotifyPlaceholder('dingtalk')"
                :disabled="notifySaving"
              />
              <p class="settings__hint">{{ getNotifyHint('dingtalk') }}</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">钉钉加签密钥（可选）</label>
              <input
                v-model="dingtalkSecret"
                type="text"
                class="settings__input"
                placeholder="SECxxxxxxxxxxxxxxxxxxxx"
                :disabled="notifySaving"
              />
                            <button
                v-if="dingtalkSecretConfigured"
                class="settings__clear-btn"
                :disabled="notifySaving"
                @click="clearSecretSetting(DINGTALK_SECRET_KEY, () => { dingtalkSecretConfigured = false; dingtalkSecret = '' })"
              >
                清空已配置的密钥
              </button>
              <p class="settings__hint">钉钉机器人安全设置选择"加签"时填写，未选择则留空</p>
            </div>
          </div>

          <!-- Server酱 -->
          <div class="settings__notify-channel">
            <div class="settings__field">
              <label class="settings__label">
                Server酱
                <span v-if="notifyConfigured('serverchan')" class="settings__status-badge settings__status-badge--success">已配置</span>
                <span v-else class="settings__status-badge">未配置</span>
                <button
                  class="settings__btn settings__notify-test-btn"
                  :disabled="notifyTesting === 'serverchan'"
                  @click.prevent="handleTestNotify('serverchan')"
                >
                  {{ notifyTesting === 'serverchan' ? '发送中...' : '发送测试' }}
                </button>
              </label>
              <input
                v-model="serverchanSendKey"
                type="text"
                class="settings__input"
                :placeholder="getNotifyPlaceholder('serverchan')"
                :disabled="notifySaving"
              />
                            <button
                v-if="serverchanSendKeyConfigured"
                class="settings__clear-btn"
                :disabled="notifySaving"
                @click="clearSecretSetting(SERVERCHAN_SENDKEY_KEY, () => { serverchanSendKeyConfigured = false; serverchanSendKey = '' })"
              >
                清空已配置的密钥
              </button>
              <p class="settings__hint">{{ getNotifyHint('serverchan') }}</p>
            </div>
          </div>

          <!-- Bark -->
          <div class="settings__notify-channel">
            <div class="settings__field">
              <label class="settings__label">
                Bark
                <span v-if="notifyConfigured('bark')" class="settings__status-badge settings__status-badge--success">已配置</span>
                <span v-else class="settings__status-badge">未配置</span>
                <button
                  class="settings__btn settings__notify-test-btn"
                  :disabled="notifyTesting === 'bark'"
                  @click.prevent="handleTestNotify('bark')"
                >
                  {{ notifyTesting === 'bark' ? '发送中...' : '发送测试' }}
                </button>
              </label>
              <input
                v-model="barkDeviceKey"
                type="text"
                class="settings__input"
                :placeholder="getNotifyPlaceholder('bark')"
                :disabled="notifySaving"
              />
                            <button
                v-if="barkDeviceKeyConfigured"
                class="settings__clear-btn"
                :disabled="notifySaving"
                @click="clearSecretSetting(BARK_DEVICE_KEY, () => { barkDeviceKeyConfigured = false; barkDeviceKey = '' })"
              >
                清空已配置的密钥
              </button>
              <p class="settings__hint">{{ getNotifyHint('bark') }}</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">Bark 服务器地址（可选，自托管时填写）</label>
              <input
                v-model="barkServer"
                type="text"
                class="settings__input"
                placeholder="https://api.day.app"
                :disabled="notifySaving"
              />
              <p class="settings__hint">留空使用官方 Bark 服务器 api.day.app</p>
            </div>
          </div>

          <!-- Telegram -->
          <div class="settings__notify-channel">
            <div class="settings__field">
              <label class="settings__label">
                Telegram
                <span v-if="notifyConfigured('telegram')" class="settings__status-badge settings__status-badge--success">已配置</span>
                <span v-else class="settings__status-badge">未配置</span>
                <button
                  class="settings__btn settings__notify-test-btn"
                  :disabled="notifyTesting === 'telegram'"
                  @click.prevent="handleTestNotify('telegram')"
                >
                  {{ notifyTesting === 'telegram' ? '发送中...' : '发送测试' }}
                </button>
              </label>
              <input
                v-model="telegramBotToken"
                type="text"
                class="settings__input"
                :placeholder="getNotifyPlaceholder('telegram')"
                :disabled="notifySaving"
              />
                            <button
                v-if="telegramBotTokenConfigured"
                class="settings__clear-btn"
                :disabled="notifySaving"
                @click="clearSecretSetting(TELEGRAM_BOT_TOKEN_KEY, () => { telegramBotTokenConfigured = false; telegramBotToken = '' })"
              >
                清空已配置的密钥
              </button>
              <p class="settings__hint">{{ getNotifyHint('telegram') }}</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">Telegram Chat ID</label>
              <input
                v-model="telegramChatId"
                type="text"
                class="settings__input"
                placeholder="123456789"
                :disabled="notifySaving"
              />
              <p class="settings__hint">Bot Token 与 Chat ID 均需填写才能收到推送</p>
            </div>
          </div>

          <!-- ntfy -->
          <div class="settings__notify-channel">
            <div class="settings__field">
              <label class="settings__label">
                ntfy
                <span v-if="notifyConfigured('ntfy')" class="settings__status-badge settings__status-badge--success">已配置</span>
                <span v-else class="settings__status-badge">未配置</span>
                <button
                  class="settings__btn settings__notify-test-btn"
                  :disabled="notifyTesting === 'ntfy'"
                  @click.prevent="handleTestNotify('ntfy')"
                >
                  {{ notifyTesting === 'ntfy' ? '发送中...' : '发送测试' }}
                </button>
              </label>
              <input
                v-model="ntfyTopic"
                type="text"
                class="settings__input"
                :placeholder="getNotifyPlaceholder('ntfy')"
                :disabled="notifySaving"
              />
              <p class="settings__hint">{{ getNotifyHint('ntfy') }}</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">ntfy 服务器地址（可选，自托管时填写）</label>
              <input
                v-model="ntfyServer"
                type="text"
                class="settings__input"
                placeholder="https://ntfy.sh"
                :disabled="notifySaving"
              />
              <p class="settings__hint">留空使用公共服务器 ntfy.sh</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">ntfy 访问令牌（可选）</label>
              <input
                v-model="ntfyToken"
                type="password"
                class="settings__input"
                placeholder="tk_xxxxxxxxxxxx"
                :disabled="notifySaving"
              />
                            <button
                v-if="ntfyTokenConfigured"
                class="settings__clear-btn"
                :disabled="notifySaving"
                @click="clearSecretSetting(NTFY_TOKEN_KEY, () => { ntfyTokenConfigured = false; ntfyToken = '' })"
              >
                清空已配置的密钥
              </button>
              <p class="settings__hint">私有主题需要填写 Bearer Token</p>
            </div>
          </div>
        </div>
      </div>

      <!-- 邮箱通知 -->
      <div v-if="activeMenu === 'email'" class="settings__panel">
        <div class="settings__panel-title">邮箱通知</div>

        <!-- 邮箱配置 -->
        <div class="settings__section">
          <div class="settings__section-header">
            <div class="settings__section-title">邮箱配置</div>
            <div class="settings__section-header-actions">
              <button
                v-if="emailConfigured"
                class="settings__toggle-btn settings__toggle-btn--test"
                :disabled="emailTesting"
                @click="handleTestEmail"
              >
                {{ emailTesting ? '发送中...' : '发送测试邮件' }}
              </button>
              <span v-if="emailConfigured" class="settings__status-badge" :class="emailConfigured ? 'settings__status-badge--success' : ''">
                {{ emailConfigured ? '已配置' : '未配置' }}
              </span>
              <button
                v-if="emailConfigured"
                class="settings__toggle-btn"
                @click="emailConfigExpanded = !emailConfigExpanded"
              >
                {{ emailConfigExpanded ? '收起' : '展开' }}
              </button>
            </div>
          </div>
          <p class="settings__desc">配置SMTP邮箱服务，用于接收系统通知（如滑块验证提醒）。发件人固定使用SMTP用户名，收件人邮箱用于接收通知。</p>

          <div v-if="emailConfigExpanded || !emailConfigured" class="settings__form">
            <div class="settings__field">
              <label class="settings__label">SMTP 服务器</label>
              <input
                v-model="emailSmtpHost"
                type="text"
                class="settings__input"
                placeholder="如 smtp.qq.com"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">SMTP 端口</label>
              <input
                v-model="emailSmtpPort"
                type="text"
                class="settings__input"
                placeholder="465"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">用户名</label>
              <input
                v-model="emailSmtpUsername"
                type="text"
                class="settings__input"
                placeholder="邮箱账号"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">密码/授权码</label>
              <div class="settings__input-wrap">
                <input
                  v-model="emailSmtpPassword"
                  :type="showEmailPassword ? 'text' : 'password'"
                  class="settings__input"
                  :placeholder="emailPasswordConfigured ? '已配置，留空保持不变' : '邮箱密码或SMTP授权码'"
                  :disabled="emailSaving"
                />
                <button class="settings__eye-btn" @click="showEmailPassword = !showEmailPassword" tabindex="-1">
                  {{ showEmailPassword ? '隐藏' : '显示' }}
                </button>
              </div>
              <button
                v-if="emailPasswordConfigured"
                class="settings__clear-btn"
                :disabled="emailSaving"
                @click="clearSecretSetting(EMAIL_SMTP_PASSWORD_KEY, () => { emailPasswordConfigured = false; emailSmtpPassword = ''; emailConfigured = !!(emailSmtpHost && emailSmtpPort && emailSmtpUsername && emailSmtpFrom) })"
              >
                清空已配置的密钥
              </button>
              <p class="settings__hint">QQ邮箱需使用授权码，非登录密码</p>
            </div>
            <div class="settings__field">
              <label class="settings__label">收件人邮箱</label>
              <input
                v-model="emailSmtpFrom"
                type="text"
                class="settings__input"
                placeholder="接收通知的邮箱地址"
                :disabled="emailSaving"
              />
            </div>
            <div class="settings__field">
              <label class="settings__label">启用 SSL</label>
              <label class="settings__switch">
                <input type="checkbox" v-model="emailSmtpSsl" :disabled="emailSaving" />
                <span class="settings__switch-track"></span>
                <span class="settings__switch-thumb"></span>
              </label>
            </div>
            <div class="settings__actions">
              <button
                class="settings__btn settings__btn--primary"
                :disabled="emailSaving"
                @click="handleSaveEmailConfig"
              >
                {{ emailSaving ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 通知开关 -->
        <div class="settings__section">
          <div class="settings__section-title">通知开关</div>
          <p class="settings__desc">选择哪些事件需要通过邮件通知您</p>

          <table class="settings__notify-table">
            <thead>
              <tr>
                <th class="settings__notify-th">通知类型</th>
                <th class="settings__notify-th">说明</th>
                <th class="settings__notify-th">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr class="settings__notify-tr">
                <td class="settings__notify-td">闲鱼账号消息监听掉线通知</td>
                <td class="settings__notify-td settings__notify-td--desc">WebSocket连接失败时发送邮件通知</td>
                <td class="settings__notify-td">
                  <label class="settings__switch">
                    <input
                      type="checkbox"
                      v-model="wsDisconnectNotifyEnabled"
                      :disabled="!emailConfigured"
                      @change="handleSaveWsDisconnectNotify"
                    />
                    <span class="settings__switch-track"></span>
                    <span class="settings__switch-thumb"></span>
                  </label>
                </td>
              </tr>
              <tr class="settings__notify-tr">
                <td class="settings__notify-td">滑块验证通知</td>
                <td class="settings__notify-td settings__notify-td--desc">账号被风控暂停时立即发送邮件，投递失败会在后续探测时重试</td>
                <td class="settings__notify-td">
                  <label class="settings__switch">
                    <input
                      type="checkbox"
                      v-model="captchaNotifyEnabled"
                      :disabled="!emailConfigured"
                      @change="handleSaveCaptchaNotify"
                    />
                    <span class="settings__switch-track"></span>
                    <span class="settings__switch-thumb"></span>
                  </label>
                </td>
              </tr>
              <tr class="settings__notify-tr">
                <td class="settings__notify-td">Cookie过期通知</td>
                <td class="settings__notify-td settings__notify-td--desc">Cookie过期且无法自动续期时发送邮件通知</td>
                <td class="settings__notify-td">
                  <label class="settings__switch">
                    <input
                      type="checkbox"
                      v-model="cookieExpireNotifyEnabled"
                      :disabled="!emailConfigured"
                      @change="handleSaveCookieExpireNotify"
                    />
                    <span class="settings__switch-track"></span>
                    <span class="settings__switch-thumb"></span>
                  </label>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="!emailConfigured" class="settings__hint" style="color:var(--warning-500);margin-top:8px;">请先配置邮箱后再开启通知</p>
        </div>
      </div>


      <!-- API 兜底发货 -->
      <!-- API 兜底发货 -->
      <ApiFallbackPanel v-if="activeMenu === 'delivery'" />
      <!-- 代理池 -->
      <ProxyPanel v-if="activeMenu === 'proxy'" />
      <!-- 备份与恢复 -->
      <div v-if="activeMenu === 'backup'" class="settings__panel">
        <div class="settings__panel-title">备份与恢复</div>

        <div class="settings__backup-schedule" role="status" aria-live="polite">
          <div class="settings__backup-schedule-head">
            <div>
              <div class="settings__section-title">自动备份</div>
              <p class="settings__desc">
                {{ backupSchedule?.enabled ? `已启用，每 ${Math.round(backupSchedule.intervalMillis / 3600000)} 小时自动保存全部数据` : '自动备份已禁用' }}
              </p>
            </div>
            <span class="settings__status-badge" :class="{ 'settings__status-badge--success': backupSchedule?.enabled }">
              {{ backupScheduleLoading ? '读取中...' : (backupSchedule?.enabled ? '运行中' : '已关闭') }}
            </span>
          </div>
          <div v-if="backupSchedule" class="settings__backup-schedule-grid">
            <div>
              <span class="settings__info-label">最近备份</span>
              <strong>{{ backupSchedule.latestBackupAt ? formatTime(backupSchedule.latestBackupAt) : '尚未执行' }}</strong>
            </div>
            <div>
              <span class="settings__info-label">下次备份</span>
              <strong>{{ backupSchedule.nextBackupAt ? formatTime(backupSchedule.nextBackupAt) : '-' }}</strong>
            </div>
            <div>
              <span class="settings__info-label">保留份数</span>
              <strong>{{ backupSchedule.retentionCount }} 份</strong>
            </div>
          </div>
          <p v-if="backupSchedule?.lastError" class="settings__backup-schedule-error">
            上次备份失败：{{ backupSchedule.lastError }}
          </p>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">选择备份模块</div>
          <p class="settings__desc">选择需要导出的数据模块，默认全部选择</p>
          <div class="settings__backup-modules">
            <div class="settings__backup-module-all">
              <label class="settings__checkbox-label" @click.prevent="toggleAllBackupModules">
                <span class="settings__checkbox" :class="{ 'settings__checkbox--checked': backupSelectedModules.length === backupModules.length && backupModules.length > 0 }">
                  <span v-if="backupSelectedModules.length === backupModules.length && backupModules.length > 0" class="settings__checkbox-tick">
                    <IconCheck aria-hidden="true" />
                  </span>
                </span>
                全选
              </label>
            </div>
            <div v-for="mod in backupModules" :key="mod.moduleKey" class="settings__backup-module-item">
              <label class="settings__checkbox-label" @click.prevent="toggleBackupModule(mod.moduleKey)">
                <span class="settings__checkbox" :class="{ 'settings__checkbox--checked': backupSelectedModules.includes(mod.moduleKey) }">
                  <span v-if="backupSelectedModules.includes(mod.moduleKey)" class="settings__checkbox-tick">
                    <IconCheck aria-hidden="true" />
                  </span>
                </span>
                {{ mod.moduleName }}
              </label>
            </div>
          </div>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">导出备份</div>
          <p class="settings__desc">将选中模块的数据导出为 JSON 文件</p>
          <label class="settings__toggle-row" style="display:flex;align-items:center;gap:8px;margin-bottom:12px;font-size:13px;color:var(--c-text-2, var(--text-2));">
            <input type="checkbox" v-model="backupIncludeSecrets" />
            <span>包含明文敏感字段（Cookie / Token / API 密钥）</span>
          </label>
          <!-- 流式响应没有 Content-Length，无法计算真实百分比，用不确定态进度条 -->
          <div v-if="backupExporting" class="settings__progress-wrap">
            <div class="settings__progress-bar">
              <div class="settings__progress-fill settings__progress-fill--indeterminate"></div>
            </div>
            <span class="settings__progress-text">导出中</span>
          </div>
          <div class="settings__actions">
            <button
              class="settings__btn settings__btn--primary"
              :disabled="backupExporting || backupSelectedModules.length === 0"
              @click="handleExportBackup"
            >
              {{ backupExporting ? '导出中...' : '导出备份' }}
            </button>
          </div>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">导入恢复</div>
          <p class="settings__desc">从 JSON 备份文件中恢复数据（将覆盖当前选中模块的已有数据）</p>
          <input
            ref="importFileInput"
            type="file"
            accept=".json"
            class="settings__file-input"
            @change="handleImportFileChange"
          />
          <div class="settings__import-file-row">
            <button
              class="settings__btn settings__btn--secondary"
              :disabled="backupImporting"
              @click="triggerImportFile"
            >
              选择文件
            </button>
            <span v-if="importFileName" class="settings__import-file-name">{{ importFileName }}</span>
            <span v-else class="settings__import-file-hint">未选择文件</span>
          </div>
          <div v-if="backupImportAvailableModules.length > 0" class="settings__backup-modules">
            <div
              v-for="mod in backupImportAvailableModules"
              :key="`import-${mod.moduleKey}`"
              class="settings__backup-module-item"
            >
              <label class="settings__checkbox-label" @click.prevent="toggleBackupImportModule(mod.moduleKey)">
                <span
                  class="settings__checkbox"
                  :class="{ 'settings__checkbox--checked': backupImportSelectedModules.includes(mod.moduleKey) }"
                >
                  <span v-if="backupImportSelectedModules.includes(mod.moduleKey)" class="settings__checkbox-tick">
                    <IconCheck aria-hidden="true" />
                  </span>
                </span>
                {{ mod.moduleName }}
              </label>
            </div>
          </div>
          <div v-if="backupImporting" class="settings__progress-wrap">
            <div class="settings__progress-bar">
              <div class="settings__progress-fill" :style="{ width: backupImportProgress + '%' }"></div>
            </div>
            <span class="settings__progress-text">{{ backupImportProgress }}%</span>
          </div>
          <div class="settings__actions">
            <button
              class="settings__btn settings__btn--danger"
              :disabled="backupImporting || !importJsonData || backupImportSelectedModules.length === 0"
              @click="handleImportBackup"
            >
              {{ backupImporting ? '导入中...' : '导入恢复' }}
            </button>
          </div>
        </div>

        <div class="settings__section">
          <div class="settings__section-title">日志打包下载</div>
          <p class="settings__desc">选择日期，将该天的日志文件打包为 ZIP 下载</p>
          <div class="settings__log-pack-row">
            <select v-model="logSelectedDate" class="settings__log-select">
              <option v-for="d in logDates" :key="d" :value="d">{{ d }}</option>
            </select>
            <button
              class="settings__btn settings__btn--primary"
              :disabled="!logSelectedDate || logDownloading"
              @click="handleDownloadLog"
            >
              {{ logDownloading ? '打包中...' : '下载日志' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 关于 -->
      <!-- 关于 -->
      <AboutPanel v-if="activeMenu === 'about'" />
    </div>
  </div>
</template>

<style src="./settings.css"></style>
