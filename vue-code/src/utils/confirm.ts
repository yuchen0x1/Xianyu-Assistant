let confirmDialogSequence = 0

export function showConfirm(
  message: string,
  title: string = '确认',
  options: { variant?: 'default' | 'danger' } = {}
): Promise<void> {
  return new Promise((resolve, reject) => {
    const previouslyFocused = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    const dialogId = ++confirmDialogSequence
    const titleId = `confirm-title-${dialogId}`
    const messageId = `confirm-message-${dialogId}`

    const overlay = document.createElement('div')
    overlay.style.cssText = `
      position:fixed;inset:0;z-index:var(--z-modal, 70);
      background:color-mix(in srgb, var(--bg-inverse, #0f172a) 24%, transparent);
      backdrop-filter:blur(28px) saturate(1.8);-webkit-backdrop-filter:blur(28px) saturate(1.8);
      display:flex;align-items:center;justify-content:center;
      animation:confirm-in .2s ease forwards;
    `

    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'alertdialog')
    dialog.setAttribute('aria-modal', 'true')
    dialog.setAttribute('aria-labelledby', titleId)
    dialog.setAttribute('aria-describedby', messageId)
    dialog.style.cssText = `
      background:var(--glass-bg-strong, var(--bg-elevated, #fff));
      backdrop-filter:var(--glass-blur, blur(20px));-webkit-backdrop-filter:var(--glass-blur, blur(20px));
      border:1px solid var(--glass-border-strong, var(--border-2, #cbd5e1));
      border-radius:var(--radius-lg, 16px);padding:0;width:320px;max-width:90vw;
      box-shadow:var(--shadow-lg, 0 16px 48px rgba(0,0,0,0.16));
      overflow:hidden;color:var(--text-1, #0f172a);
      font-family:var(--font-sans, -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif);
    `

    const content = document.createElement('div')
    content.style.cssText = 'padding:20px 20px 4px;text-align:center;'

    const titleElement = document.createElement('div')
    titleElement.id = titleId
    titleElement.style.cssText = 'font-size:17px;font-weight:600;color:var(--text-1, #1c1c1e);margin-bottom:8px;'
    titleElement.textContent = title

    const messageElement = document.createElement('div')
    messageElement.id = messageId
    messageElement.style.cssText = 'font-size:14px;color:var(--text-2, #475569);line-height:1.5;white-space:pre-line;overflow-wrap:anywhere;'
    messageElement.textContent = message

    content.append(titleElement, messageElement)

    const actions = document.createElement('div')
    actions.style.cssText = 'display:flex;border-top:0.5px solid var(--border-1, #e2e8f0);margin-top:16px;'

    const cancelButton = document.createElement('button')
    cancelButton.type = 'button'
    cancelButton.textContent = '取消'
    cancelButton.style.cssText = 'flex:1;border:none;background:transparent;font-size:16px;font-weight:500;color:var(--text-2, #475569);cursor:pointer;padding:12px 0;-webkit-tap-highlight-color:transparent;font-family:inherit;'

    const confirmButton = document.createElement('button')
    confirmButton.type = 'button'
    confirmButton.textContent = '确定'
    confirmButton.style.cssText = options.variant === 'danger'
      ? 'flex:1;border:none;border-left:0.5px solid var(--border-1);background:transparent;font-size:16px;font-weight:700;color:var(--danger-500);cursor:pointer;padding:12px 0;-webkit-tap-highlight-color:transparent;font-family:inherit;'
      : 'flex:1;border:none;border-left:0.5px solid var(--border-1);background:transparent;font-size:16px;font-weight:600;color:var(--brand-600);cursor:pointer;padding:12px 0;-webkit-tap-highlight-color:transparent;font-family:inherit;'

    actions.append(cancelButton, confirmButton)
    dialog.append(content, actions)
    overlay.appendChild(dialog)
    document.body.appendChild(overlay)

    let settled = false
    const cleanup = () => {
      document.removeEventListener('keydown', handleKeydown)
      overlay.style.animation = 'confirm-out .15s ease forwards'
      setTimeout(() => {
        overlay.remove()
        previouslyFocused?.focus()
      }, 150)
    }
    const settle = (confirmed: boolean) => {
      if (settled) return
      settled = true
      cleanup()
      if (confirmed) resolve()
      else reject('cancel')
    }
    const handleKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') settle(false)
    }

    cancelButton.addEventListener('click', () => settle(false))
    confirmButton.addEventListener('click', () => settle(true))
    overlay.addEventListener('click', (event) => {
      if (event.target === overlay) settle(false)
    })
    document.addEventListener('keydown', handleKeydown)
    confirmButton.focus()
  })
}
