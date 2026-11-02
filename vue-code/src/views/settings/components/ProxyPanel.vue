<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getSetting, saveSetting } from '@/api/setting'
import { toast } from '@/utils/toast'
import { showConfirm } from '@/utils/confirm'

defineOptions({ name: 'ProxyPanel' })

const PROXY_ENABLED_KEY = 'proxy_enabled'
const PROXY_LIST_KEY = 'proxy_list'
const PROXY_BLACKLIST_TTL_KEY = 'proxy_blacklist_ttl_seconds'
const PROXY_MAX_RETRIES_KEY = 'proxy_max_retries'

interface ProxyEntry {
  host: string
  port: number | string
  type: string
  username: string
  password: string
}

const proxyEnabled = ref(false)
const proxyList = ref<ProxyEntry[]>([])
const proxyListConfigured = ref(false)
const proxyListEdited = ref(false)
const proxyBlacklistTtl = ref(300)
const proxyMaxRetries = ref(3)
const proxySaving = ref(false)
const proxyLoaded = ref(false)

async function loadProxyConfig() {
  try {
    const [enabledRes, listRes, ttlRes, retriesRes] = await Promise.all([
      getSetting({ settingKey: PROXY_ENABLED_KEY }),
      getSetting({ settingKey: PROXY_LIST_KEY }),
      getSetting({ settingKey: PROXY_BLACKLIST_TTL_KEY }),
      getSetting({ settingKey: PROXY_MAX_RETRIES_KEY })
    ])
    if (enabledRes.code === 200 && enabledRes.data) {
      proxyEnabled.value = enabledRes.data.settingValue === '1' || enabledRes.data.settingValue === 'true'
    }
    if (listRes.code === 200 && listRes.data) {
      const raw = listRes.data.settingValue || '[]'
      proxyListConfigured.value = listRes.data.hasSecret === true || raw === '********'
      proxyListEdited.value = false
      if (proxyListConfigured.value) {
        // 列表整体已掩码，不在表单中展示明文条目；保持空列表，保存时由后端保留原值
        proxyList.value = []
      } else {
        try {
          const parsed = JSON.parse(raw)
          if (Array.isArray(parsed)) {
            proxyList.value = parsed.map((p: { host?: string; port?: number | string; type?: string; username?: string; password?: string }) => ({
              host: p.host || '',
              port: p.port || '',
              type: p.type || 'HTTP',
              username: p.username || '',
              password: p.password || ''
            }))
          }
        } catch {
          proxyList.value = []
        }
      }
    }
    if (ttlRes.code === 200 && ttlRes.data && ttlRes.data.settingValue) {
      proxyBlacklistTtl.value = parseInt(ttlRes.data.settingValue, 10) || 300
    }
    if (retriesRes.code === 200 && retriesRes.data && retriesRes.data.settingValue) {
      proxyMaxRetries.value = parseInt(retriesRes.data.settingValue, 10) || 3
    }
  } catch (e) {
    console.error('加载代理池配置失败:', e)
  } finally {
    proxyLoaded.value = true
  }
}

function addProxyEntry() {
  proxyList.value.push({ host: '', port: '', type: 'HTTP', username: '', password: '' })
  proxyListEdited.value = true
}

function removeProxyEntry(index: number) {
  proxyList.value.splice(index, 1)
  proxyListEdited.value = true
}

async function handleSaveProxyConfig() {
  // 校验并清理代理条目
  const cleaned: ProxyEntry[] = []
  for (const [i, entry] of proxyList.value.entries()) {
    const host = (entry.host || '').trim()
    const portNum = parseInt(String(entry.port), 10)
    if (!host) {
      toast.warning(`第 ${i + 1} 个代理的主机不能为空`)
      return
    }
    if (!portNum || portNum <= 0 || portNum > 65535) {
      toast.warning(`第 ${i + 1} 个代理的端口不合法（1-65535）`)
      return
    }
    cleaned.push({
      host,
      port: portNum,
      type: (entry.type || 'HTTP').toUpperCase() === 'SOCKS' ? 'SOCKS' : 'HTTP',
      username: (entry.username || '').trim(),
      password: entry.password || ''
    })
  }

  const listJson = JSON.stringify(cleaned)
  const preserveProxyList = proxyListConfigured.value && !proxyListEdited.value && proxyList.value.length === 0
  if (!preserveProxyList && proxyListConfigured.value && proxyListEdited.value && proxyList.value.length > 0) {
    await showConfirm('已配置的代理列表将被新列表覆盖，确定继续吗？')
  }
  proxySaving.value = true
  try {
    const saveCalls = [
      saveSetting({
        settingKey: PROXY_ENABLED_KEY,
        settingValue: proxyEnabled.value ? '1' : '0',
        settingDesc: '代理池总开关：1启用，0关闭。默认关闭，配置代理列表后开启生效，无需重启'
      }),
      ...(preserveProxyList
        ? []
        : [
            saveSetting({
              settingKey: PROXY_LIST_KEY,
              settingValue: listJson,
              settingDesc: '代理列表（JSON数组）：如 [{"host":"1.2.3.4","port":8080,"type":"HTTP"}]；type 可选 HTTP/SOCKS'
            })
          ]),
      saveSetting({
        settingKey: PROXY_BLACKLIST_TTL_KEY,
        settingValue: String(proxyBlacklistTtl.value),
        settingDesc: '代理黑名单存活秒数：失败代理加入黑名单后的自动恢复时间，默认 300 秒'
      }),
      saveSetting({
        settingKey: PROXY_MAX_RETRIES_KEY,
        settingValue: String(proxyMaxRetries.value),
        settingDesc: '代理池单次请求最大尝试次数（含首次）：网络层失败时切换下一个代理重试，默认 3'
      })
    ]
    const results = await Promise.all(saveCalls)
    if (results.every((r) => r.code === 200)) {
      toast.success('代理池配置已保存，已立即生效')
      if (!preserveProxyList) {
        proxyListConfigured.value = proxyList.value.length > 0
      }
      proxyListEdited.value = false
    }
  } catch (e) {
    console.error('保存代理池配置失败:', e)
    toast.error('保存代理池配置失败')
  } finally {
    proxySaving.value = false
  }
}

onMounted(loadProxyConfig)
</script>

<template>
  <div class="settings__panel">
    <div class="settings__panel-title">代理池</div>
    <div class="settings__section">
      <div class="settings__section-title">总开关</div>
      <p class="settings__desc">开启后，所有 mtop 接口调用（商品发布 / 下架 / 同步等）将通过代理池发起，按轮询策略选择代理，网络层失败的代理会进入黑名单并在 TTL 后自动恢复。默认关闭，配置代理列表后开启即生效，无需重启。</p>
      <div class="settings__field">
        <label class="settings__label">启用代理池</label>
        <label class="settings__switch">
          <input type="checkbox" v-model="proxyEnabled" :disabled="proxySaving" />
          <span class="settings__switch-track"></span>
          <span class="settings__switch-thumb"></span>
        </label>
      </div>
    </div>

    <div class="settings__section">
      <div class="settings__section-title">代理列表</div>
      <p class="settings__desc">支持 HTTP / SOCKS 代理；用户名密码为可选（用于需认证的代理）。每行一条。</p>
      <div v-if="proxyList.length === 0" class="settings__hint">{{ proxyListConfigured ? '已配置代理列表（出于安全考虑已隐藏），修改并保存后将覆盖现有列表' : '尚未配置代理，点击下方"添加代理"开始。' }}</div>
      <div v-for="(entry, idx) in proxyList" :key="idx" class="settings__proxy-row">
        <input
          v-model="entry.host"
          type="text"
          class="settings__input settings__proxy-host"
          placeholder="主机 IP / 域名"
          :disabled="proxySaving"
        />
        <input
          v-model="entry.port"
          type="number"
          class="settings__input settings__proxy-port"
          placeholder="端口"
          :disabled="proxySaving"
        />
        <select v-model="entry.type" class="settings__input settings__proxy-type" :disabled="proxySaving">
          <option value="HTTP">HTTP</option>
          <option value="SOCKS">SOCKS</option>
        </select>
        <input
          v-model="entry.username"
          type="text"
          class="settings__input settings__proxy-user"
          placeholder="用户名（可选）"
          :disabled="proxySaving"
        />
        <input
          v-model="entry.password"
          type="password"
          class="settings__input settings__proxy-pass"
          placeholder="密码（可选）"
          :disabled="proxySaving"
        />
        <button
          class="settings__btn settings__btn--danger settings__proxy-remove"
          :disabled="proxySaving"
          @click="removeProxyEntry(idx)"
        >
          删除
        </button>
      </div>
      <div class="settings__actions">
        <button class="settings__btn settings__btn--secondary" :disabled="proxySaving" @click="addProxyEntry">
          添加代理
        </button>
      </div>
    </div>

    <div class="settings__section">
      <div class="settings__section-title">轮换与失败保护</div>
      <div class="settings__field">
        <label class="settings__label">黑名单存活秒数（TTL）</label>
        <input
          v-model.number="proxyBlacklistTtl"
          type="number"
          min="10"
          class="settings__input settings__proxy-ttl"
          :disabled="proxySaving"
        />
        <p class="settings__hint">代理网络层失败后加入黑名单的自动恢复时间，默认 300 秒。</p>
      </div>
      <div class="settings__field">
        <label class="settings__label">单次请求最大尝试次数（含首次）</label>
        <input
          v-model.number="proxyMaxRetries"
          type="number"
          min="1"
          max="10"
          class="settings__input settings__proxy-retries"
          :disabled="proxySaving"
        />
        <p class="settings__hint">网络层失败时切换下一个代理重试的上限；全部代理在黑名单中时自动回退直连。</p>
      </div>
      <div class="settings__actions">
        <button
          class="settings__btn settings__btn--primary"
          :disabled="proxySaving"
          @click="handleSaveProxyConfig"
        >
          {{ proxySaving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>
