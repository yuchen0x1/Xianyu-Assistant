import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { showConfirm } from '@/utils/confirm'

describe('showConfirm', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    document.body.replaceChildren()
  })

  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
    document.body.replaceChildren()
  })

  it('renders an accessible dialog and restores focus after confirmation', async () => {
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()

    const promise = showConfirm('确认删除商品？', '删除商品')
    const dialog = document.querySelector<HTMLElement>('[role="alertdialog"]')
    const confirmButton = Array.from(document.querySelectorAll('button'))
      .find((button) => button.textContent === '确定')

    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(document.getElementById(dialog?.getAttribute('aria-labelledby') ?? '')?.textContent)
      .toBe('删除商品')
    expect(document.getElementById(dialog?.getAttribute('aria-describedby') ?? '')?.textContent)
      .toBe('确认删除商品？')
    expect(document.activeElement).toBe(confirmButton)

    confirmButton?.click()
    await expect(promise).resolves.toBeUndefined()
    vi.advanceTimersByTime(150)

    expect(document.querySelector('[role="alertdialog"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })

  it('rejects and removes the dialog when Escape is pressed', async () => {
    const promise = showConfirm('放弃本次修改？')
    const rejection = expect(promise).rejects.toBe('cancel')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))

    await rejection
    vi.advanceTimersByTime(150)
    expect(document.querySelector('[role="alertdialog"]')).toBeNull()
  })
})
