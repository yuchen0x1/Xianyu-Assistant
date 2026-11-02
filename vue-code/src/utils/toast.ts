let container: HTMLDivElement | null = null
const activeToasts = new Map<string, HTMLDivElement>()

function getContainer() {
  if (!container || !container.isConnected) {
    container = document.createElement('div')
    container.className = 'toast-container'
    container.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);z-index:var(--z-toast, 80);display:flex;flex-direction:column;gap:8px;pointer-events:none;'
    container.setAttribute('role', 'status')
    container.setAttribute('aria-live', 'polite')
    container.setAttribute('aria-atomic', 'false')
    container.setAttribute('aria-relevant', 'additions text')
    document.body.appendChild(container)
  }
  return container
}

type ToastType = 'success' | 'error' | 'warning' | 'info'

const BG_MAP: Record<ToastType, string> = {
  success: 'var(--success-600, #15803d)',
  error: 'var(--danger-600, #b91c1c)',
  warning: 'var(--warning-600, #b45309)',
  info: 'var(--text-2, #475569)',
}

const ICON_MAP: Record<ToastType, string> = {
  success: '\u2713',
  error: '\u00d7',
  warning: '!',
  info: 'i',
}

function show(message: string, type: ToastType = 'info', duration: number = 2500) {
  const toastKey = `${type}:${message}`
  const existingToast = activeToasts.get(toastKey)
  if (existingToast?.isConnected) return
  activeToasts.delete(toastKey)

  const el = document.createElement('div')
  el.style.cssText = `
    display:flex;align-items:flex-start;gap:8px;
    width:max-content;max-width:min(520px,calc(100vw - 32px));
    padding:10px 14px;border-radius:8px;
    background:${BG_MAP[type]};
    backdrop-filter:blur(20px) saturate(1.8);-webkit-backdrop-filter:blur(20px) saturate(1.8);
    color:#fff;font-size:14px;font-weight:500;
    box-shadow:0 8px 32px rgba(0,0,0,0.18),0 2px 8px rgba(0,0,0,0.08);
    pointer-events:auto;white-space:normal;overflow-wrap:anywhere;line-height:1.45;
    animation:toast-in .25s cubic-bezier(.34,1.56,.64,1) forwards;
    font-family:var(--font-sans, -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif);
  `
  const icon = document.createElement('span')
  icon.textContent = ICON_MAP[type]
  icon.setAttribute('aria-hidden', 'true')
  icon.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;flex:0 0 16px;width:16px;height:16px;margin-top:2px;font-size:15px;font-weight:700;line-height:1;'

  const text = document.createElement('span')
  text.textContent = message
  text.style.cssText = 'min-width:0;white-space:pre-wrap;overflow-wrap:anywhere;'

  el.append(icon, text)
  getContainer().appendChild(el)
  activeToasts.set(toastKey, el)

  const readableDuration = Math.max(duration, Math.min(8000, 2000 + message.length * 40))
  setTimeout(() => {
    el.style.animation = 'toast-out .2s ease forwards'
    setTimeout(() => {
      el.remove()
      if (activeToasts.get(toastKey) === el) {
        activeToasts.delete(toastKey)
      }
    }, 200)
  }, readableDuration)
}

export const toast = {
  success: (msg: string) => show(msg, 'success'),
  error: (msg: string) => show(msg, 'error'),
  warning: (msg: string) => show(msg, 'warning'),
  info: (msg: string) => show(msg, 'info'),
}
