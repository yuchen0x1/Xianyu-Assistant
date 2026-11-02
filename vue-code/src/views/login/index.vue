<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { checkUserExists, login, register } from '@/api/auth'
import { setAuthToken, isLoggedIn } from '@/utils/request'
import { loadPermissions, type PermissionState } from '@/composables/usePermissions'
import { resolveLandingPath } from '@/router'

defineOptions({ name: 'LoginView' })

const route = useRoute()
const router = useRouter()

// 'checking' -> 'login' -> 'register'
const mode = ref<'checking' | 'login' | 'register'>('checking')
const loading = ref(false)
const pageError = ref('')
const initializationFailed = ref(false)
// 已登录但权限信息取不到时，提示条上的按钮应重试进首页而不是重新检测账号
const permissionRetry = ref(false)

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const fieldErrors = reactive({
  username: '',
  password: '',
  confirmPassword: '',
})

const showPassword = ref(false)
const showConfirmPassword = ref(false)

type FieldName = keyof typeof fieldErrors

function clearFieldError(field: FieldName) {
  fieldErrors[field] = ''
  pageError.value = ''
}

function clearErrors() {
  fieldErrors.username = ''
  fieldErrors.password = ''
  fieldErrors.confirmPassword = ''
  pageError.value = ''
}

function getErrorMessage(error: unknown, fallback: string) {
  if (!(error instanceof Error) || !error.message) {
    return fallback
  }
  const isGenericTransportError = error.message === 'Network Error'
    || /^Request failed with status code \d+$/i.test(error.message)
    || /^(?:Failed to fetch|Load failed)$/i.test(error.message)
    || /timeout(?: of \d+ms)? exceeded/i.test(error.message)
  if (isGenericTransportError) return fallback
  return error.message
}

async function detectUserMode() {
  mode.value = 'checking'
  pageError.value = ''
  initializationFailed.value = false
  try {
    const res = await checkUserExists()
    if ((res.code === 200 || res.code === 0) && res.data) {
      // exists=true -> 有用户 -> 登录; exists=false -> 无用户 -> 注册
      mode.value = res.data.exists ? 'login' : 'register'
    } else {
      mode.value = 'login'
      initializationFailed.value = true
      pageError.value = res.msg || res.message || '无法确认账号状态，请重新检测'
    }
  } catch (e) {
    mode.value = 'login'
    initializationFailed.value = true
    pageError.value = getErrorMessage(e, '无法连接本机服务，请确认服务已启动后重新检测')
  }
}

/**
 * 登录成功后进入控制台。
 *
 * 先确认权限接口可用再跳转：旧实现直接 `window.location.href = '/dashboard'`，
 * 权限接口一旦 5xx，守卫会把用户打回 `/login`，登录页又整页重载回 `/dashboard`，
 * 形成白屏闪动的刷新死循环。现在失败就停在本页提示，由用户手动重试。
 */
async function goHome() {
  permissionRetry.value = false
  pageError.value = ''
  let state: PermissionState
  try {
    state = await loadPermissions()
  } catch {
    mode.value = 'login'
    permissionRetry.value = true
    pageError.value = '已登录，但权限信息加载失败，服务可能暂时不可用。请稍后重试。'
    return
  }
  // 直接在本地算落地页：若交给守卫兜底，零权限时会被打回 /login，
  // 而那时本组件已挂载，onMounted 不会重跑，用户看不到任何提示。
  const target = resolveLandingPath(state)
  if (!target) {
    mode.value = 'login'
    permissionRetry.value = false
    pageError.value = '当前账号没有被分配任何可用权限，请联系管理员授权。'
    return
  }
  await router.replace(target)
}

onMounted(() => {
  const reason = typeof route.query.reason === 'string' ? route.query.reason : ''
  if (reason && isLoggedIn()) {
    // 守卫主动带 reason 过来：只提示，不再自动跳转，避免与守卫互相踢
    mode.value = 'login'
    permissionRetry.value = reason === 'perm_unavailable'
    pageError.value = reason === 'perm_unavailable'
      ? '无法获取权限信息，服务可能暂时不可用。请稍后重试。'
      : '当前账号没有被分配任何可用权限，请联系管理员授权。'
    return
  }
  if (isLoggedIn()) {
    void goHome()
    return
  }
  void detectUserMode()
})

async function handleLogin() {
  clearErrors()
  initializationFailed.value = false
  if (!username.value.trim()) fieldErrors.username = '请输入账号'
  if (!password.value) fieldErrors.password = '请输入密码'
  if (fieldErrors.username || fieldErrors.password) return

  loading.value = true
  try {
    const res = await login({ username: username.value.trim(), password: password.value })
    if ((res.code === 200 || res.code === 0) && res.data?.token) {
      setAuthToken(res.data.token, res.data.username)
      await goHome()
    } else {
      pageError.value = res.msg || res.message || '登录失败，请检查账号和密码'
    }
  } catch (e) {
    pageError.value = getErrorMessage(e, '登录失败，无法连接本机服务')
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  clearErrors()
  const normalizedUsername = username.value.trim()
  if (!normalizedUsername) {
    fieldErrors.username = '请输入账号'
  } else if (normalizedUsername.length < 3 || normalizedUsername.length > 20) {
    fieldErrors.username = '账号长度需要在 3 到 20 个字符之间'
  }
  if (!password.value) {
    fieldErrors.password = '请输入密码'
  } else if (password.value.length < 8 || password.value.length > 50) {
    fieldErrors.password = '密码长度需要在 8 到 50 个字符之间'
  }
  if (!confirmPassword.value) {
    fieldErrors.confirmPassword = '请再次输入密码'
  } else if (password.value !== confirmPassword.value) {
    fieldErrors.confirmPassword = '两次输入的密码不一致'
  }
  if (fieldErrors.username || fieldErrors.password || fieldErrors.confirmPassword) return

  loading.value = true
  try {
    const res = await register({
      username: normalizedUsername,
      password: password.value,
      confirmPassword: confirmPassword.value
    })
    if ((res.code === 200 || res.code === 0) && res.data?.token) {
      setAuthToken(res.data.token, res.data.username)
      await goHome()
    } else {
      pageError.value = res.msg || res.message || '创建账号失败，请稍后重试'
    }
  } catch (e) {
    pageError.value = getErrorMessage(e, '创建账号失败，无法连接本机服务')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <!-- Logo -->
      <div class="login-logo">
        <img class="login-logo-icon" src="/brand-mark.svg" alt="XianYu Seller Console" />
        <div class="login-logo-copy">
          <strong>XianYu Seller Console</strong>
          <small>Local Seller Operations Console</small>
        </div>
      </div>

      <!-- Loading -->
      <div v-if="mode === 'checking'" class="login-loading" role="status" aria-live="polite">
        <div class="login-spinner" aria-hidden="true"></div>
        <span>正在检查账号状态...</span>
      </div>

      <!-- Login Form -->
      <form v-else-if="mode === 'login'" class="login-form" novalidate @submit.prevent="handleLogin">
        <h2 class="login-title">登录</h2>
        <p class="login-subtitle">请输入账号密码登录</p>

        <div v-if="pageError" class="login-alert" role="alert" aria-live="assertive">
          <span>{{ pageError }}</span>
          <button v-if="initializationFailed" type="button" @click="detectUserMode">重新检测</button>
          <button v-else-if="permissionRetry" type="button" @click="goHome">重试</button>
        </div>

        <div class="login-field">
          <label class="login-label" for="login-username">账号</label>
          <div class="login-input-wrap">
            <input
              id="login-username"
              v-model="username"
              type="text"
              class="login-input"
              placeholder="请输入账号"
              autocomplete="username"
              :disabled="loading"
              :aria-invalid="Boolean(fieldErrors.username)"
              :aria-describedby="fieldErrors.username ? 'login-username-error' : undefined"
              @input="clearFieldError('username')"
            />
          </div>
          <p v-if="fieldErrors.username" id="login-username-error" class="login-field-error">{{ fieldErrors.username }}</p>
        </div>

        <div class="login-field">
          <label class="login-label" for="login-password">密码</label>
          <div class="login-input-wrap login-input-wrap--with-action">
            <input
              id="login-password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              class="login-input"
              placeholder="请输入密码"
              autocomplete="current-password"
              :disabled="loading"
              :aria-invalid="Boolean(fieldErrors.password)"
              :aria-describedby="fieldErrors.password ? 'login-password-error' : undefined"
              @input="clearFieldError('password')"
            />
            <button
              class="login-eye-btn"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <p v-if="fieldErrors.password" id="login-password-error" class="login-field-error">{{ fieldErrors.password }}</p>
        </div>

        <button type="submit" class="login-btn" :disabled="loading">
          <span v-if="loading" class="login-btn-spinner" aria-hidden="true"></span>
          {{ loading ? '请稍候...' : '登录' }}
        </button>
      </form>

      <!-- Register Form -->
      <form v-else-if="mode === 'register'" class="login-form" novalidate @submit.prevent="handleRegister">
        <h2 class="login-title">创建账号</h2>
        <p class="login-subtitle">首次使用，请创建管理员账号</p>

        <div v-if="pageError" class="login-alert" role="alert" aria-live="assertive">{{ pageError }}</div>

        <div class="login-field">
          <label class="login-label" for="register-username">账号</label>
          <div class="login-input-wrap">
            <input
              id="register-username"
              v-model="username"
              type="text"
              class="login-input"
              placeholder="请输入账号"
              autocomplete="username"
              maxlength="20"
              :disabled="loading"
              :aria-invalid="Boolean(fieldErrors.username)"
              :aria-describedby="fieldErrors.username ? 'register-username-error' : undefined"
              @input="clearFieldError('username')"
            />
          </div>
          <p v-if="fieldErrors.username" id="register-username-error" class="login-field-error">{{ fieldErrors.username }}</p>
        </div>

        <div class="login-field">
          <label class="login-label" for="register-password">密码</label>
          <div class="login-input-wrap login-input-wrap--with-action">
            <input
              id="register-password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              class="login-input"
              placeholder="请输入密码"
              autocomplete="new-password"
              maxlength="50"
              :disabled="loading"
              :aria-invalid="Boolean(fieldErrors.password)"
              :aria-describedby="fieldErrors.password ? 'register-password-error' : undefined"
              @input="clearFieldError('password')"
            />
            <button
              class="login-eye-btn"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <p v-if="fieldErrors.password" id="register-password-error" class="login-field-error">{{ fieldErrors.password }}</p>
        </div>

        <div class="login-field">
          <label class="login-label" for="register-confirm-password">确认密码</label>
          <div class="login-input-wrap login-input-wrap--with-action">
            <input
              id="register-confirm-password"
              v-model="confirmPassword"
              :type="showConfirmPassword ? 'text' : 'password'"
              class="login-input"
              placeholder="请再次输入密码"
              autocomplete="new-password"
              maxlength="50"
              :disabled="loading"
              :aria-invalid="Boolean(fieldErrors.confirmPassword)"
              :aria-describedby="fieldErrors.confirmPassword ? 'register-confirm-password-error' : undefined"
              @input="clearFieldError('confirmPassword')"
            />
            <button
              class="login-eye-btn"
              type="button"
              :aria-label="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
              :title="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
              @click="showConfirmPassword = !showConfirmPassword"
            >
              {{ showConfirmPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <p v-if="fieldErrors.confirmPassword" id="register-confirm-password-error" class="login-field-error">{{ fieldErrors.confirmPassword }}</p>
        </div>

        <button type="submit" class="login-btn" :disabled="loading">
          <span v-if="loading" class="login-btn-spinner" aria-hidden="true"></span>
          {{ loading ? '请稍候...' : '创建账号' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  z-index: 1;
  display: flex;
  min-height: 100%;
  align-items: center;
  justify-content: center;
  padding: 28px 18px;
  isolation: isolate;
  overflow: hidden;
  background:
    linear-gradient(115deg, color-mix(in srgb, var(--brand-400) 13%, transparent), transparent 42%),
    linear-gradient(300deg, color-mix(in srgb, var(--teal-400) 10%, transparent), transparent 44%),
    linear-gradient(210deg, color-mix(in srgb, var(--rose-400) 7%, transparent), transparent 38%),
    var(--gradient-login-deep);
}

.login-page::before,
.login-page::after {
  display: none;
}

.login-card {
  width: 100%;
  max-width: 420px;
  padding: 34px 32px 30px;
  position: relative;
  overflow: hidden;
  background: var(--glass-bg-strong);
  border: 1px solid var(--glass-border-strong);
  border-radius: var(--radius-lg);
  box-shadow: var(--glass-shadow-float);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
}

.login-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--brand-600), var(--info-500));
}

.login-card::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  box-shadow: var(--glass-inner-shadow);
  pointer-events: none;
}

.login-logo {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  margin-bottom: 28px;
}

.login-logo-icon {
  width: 42px;
  height: 42px;
  display: block;
  object-fit: contain;
  border-radius: var(--radius-md);
  box-shadow: 0 8px 24px color-mix(in srgb, var(--brand-600) 22%, transparent);
}

.login-logo-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.login-logo-copy strong {
  color: var(--text-1);
  font-size: 16px;
  font-weight: 750;
  line-height: 1.3;
}

.login-logo-copy small {
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.3;
}

.login-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  padding: 40px 0;
  color: var(--text-2);
  font-size: 13px;
}

.login-spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-2);
  border-top-color: var(--brand-600);
  border-radius: 50%;
  animation: login-spin 0.6s linear infinite;
}

@keyframes login-spin {
  to { transform: rotate(360deg); }
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.login-title {
  margin: 0;
  color: var(--text-1);
  font-size: 24px;
  font-weight: 780;
  line-height: 1.25;
  letter-spacing: 0;
  text-align: left;
}

.login-subtitle {
  margin: -10px 0 2px;
  color: var(--text-2);
  font-size: 13px;
  text-align: left;
}

.login-alert {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  color: var(--danger-600);
  font-size: 13px;
  line-height: 1.45;
  overflow-wrap: anywhere;
  background: var(--danger-50);
  border: 1px solid color-mix(in srgb, var(--danger-500) 24%, transparent);
  border-radius: 8px;
}

.login-alert button {
  flex: 0 0 auto;
  padding: 2px 0;
  color: var(--danger-600);
  font: inherit;
  font-weight: 700;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.login-alert button:hover {
  text-decoration: underline;
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.login-label {
  color: var(--text-2);
  font-size: 13px;
  font-weight: 600;
}

.login-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.login-input {
  width: 100%;
  height: 46px;
  padding: 0 14px;
  color: var(--text-1);
  font-size: 14px;
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  outline: none;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard),
    background-color var(--duration-fast) var(--ease-standard);
  box-sizing: border-box;
}

.login-input:focus {
  border-color: var(--brand-600);
  background: var(--glass-bg);
  box-shadow: 0 0 0 4px var(--ring-brand), 0 8px 24px color-mix(in srgb, var(--brand-500) 10%, transparent);
}

.login-input[aria-invalid='true'] {
  border-color: var(--danger-500);
}

.login-input[aria-invalid='true']:focus {
  box-shadow: 0 0 0 4px var(--ring-danger);
}

.login-input::placeholder {
  color: var(--text-3);
}

.login-input:disabled {
  opacity: 0.5;
}

.login-input-wrap--with-action .login-input {
  padding-right: 70px;
}

.login-field-error {
  margin: 0;
  color: var(--danger-600);
  font-size: 12px;
  line-height: 1.4;
}

.login-eye-btn {
  position: absolute;
  right: 8px;
  min-width: 44px;
  min-height: 30px;
  padding: 4px 7px;
  color: var(--text-2);
  font-size: 12px;
  background: transparent;
  border: 0;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.login-eye-btn:hover {
  color: var(--text-1);
  background: var(--bg-hover);
}

.login-btn {
  width: 100%;
  height: 46px;
  color: #fff;
  font-size: 14px;
  font-weight: 750;
  background: linear-gradient(105deg, var(--brand-600), var(--info-500));
  border: 0;
  border-radius: 8px;
  cursor: pointer;
  transition:
    transform var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard),
    filter var(--duration-fast) var(--ease-standard);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 4px;
  box-shadow: 0 10px 24px color-mix(in srgb, var(--brand-600) 24%, transparent);
}

.login-btn:hover {
  filter: brightness(1.03);
  box-shadow: 0 14px 30px color-mix(in srgb, var(--brand-600) 30%, transparent);
}

.login-btn:active {
  transform: translateY(1px);
}

.login-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}

.login-btn-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: login-spin 0.6s linear infinite;
}

@media (max-width: 768px) {
  .login-card {
    padding: 30px 24px 26px;
  }
}

@media (max-width: 480px) {
  .login-page {
    align-items: flex-start;
    padding: 24px 14px;
  }

  .login-card {
    margin-top: max(24px, 8vh);
    padding: 26px 18px 22px;
    border-radius: var(--radius-md);
  }

  .login-logo-icon {
    width: 38px;
    height: 38px;
    font-size: 17px;
  }

  .login-title {
    font-size: 22px;
  }

  .login-input,
  .login-btn {
    height: 44px;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .login-card {
    background: var(--surface-solid);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}
</style>
