import { computed, ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'xianyu_theme_mode'
const mode = ref<ThemeMode>('system')
const systemDark = ref(false)
let media: MediaQueryList | null = null
let initialized = false

function readStoredMode(): ThemeMode {
  const value = localStorage.getItem(STORAGE_KEY)
  if (value === 'light' || value === 'dark' || value === 'system') return value
  return 'system'
}

function applyTheme() {
  const resolved = mode.value === 'system'
    ? (systemDark.value ? 'dark' : 'light')
    : mode.value
  document.documentElement.dataset.theme = resolved
  document.documentElement.style.colorScheme = resolved
}

function onMediaChange(event: MediaQueryListEvent) {
  systemDark.value = event.matches
  if (mode.value === 'system') applyTheme()
}

export function initTheme() {
  if (initialized || typeof window === 'undefined') return
  initialized = true
  mode.value = readStoredMode()
  media = window.matchMedia('(prefers-color-scheme: dark)')
  systemDark.value = media.matches
  media.addEventListener('change', onMediaChange)
  applyTheme()
}

export function useTheme() {
  initTheme()

  const resolvedTheme = computed(() => {
    if (mode.value === 'system') return systemDark.value ? 'dark' : 'light'
    return mode.value
  })

  const setThemeMode = (next: ThemeMode) => {
    mode.value = next
    localStorage.setItem(STORAGE_KEY, next)
    applyTheme()
  }

  const toggleTheme = () => {
    setThemeMode(resolvedTheme.value === 'dark' ? 'light' : 'dark')
  }

  watch(mode, applyTheme)

  return {
    mode,
    resolvedTheme,
    setThemeMode,
    toggleTheme,
  }
}