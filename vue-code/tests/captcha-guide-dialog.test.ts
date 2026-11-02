import { createApp, nextTick } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import CaptchaGuideDialog from '@/views/connection/components/CaptchaGuideDialog.vue';

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('CaptchaGuideDialog', () => {
  it('opens the official QR update flow without launching a browser tab', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    const confirm = vi.fn();
    const host = document.createElement('div');
    document.body.appendChild(host);
    const app = createApp(CaptchaGuideDialog, {
      modelValue: true,
      captchaUrl: 'https://h5api.m.goofish.com/punish?x5secdata=test',
      onConfirm: confirm,
    });
    app.mount(host);
    await nextTick();

    const button = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
      .find((item) => item.textContent?.includes('打开扫码更新'));
    button?.click();
    await nextTick();

    expect(button).toBeTruthy();
    expect(openSpy).not.toHaveBeenCalled();
    expect(confirm).toHaveBeenCalledWith(
      'https://h5api.m.goofish.com/punish?x5secdata=test',
    );
    app.unmount();
  });
});
