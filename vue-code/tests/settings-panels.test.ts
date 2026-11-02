import { createApp, nextTick, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const settingApi = vi.hoisted(() => ({
  getSetting: vi.fn(),
  saveSetting: vi.fn(),
}));

vi.mock("@/api/setting", () => settingApi);

import AboutPanel from "@/views/settings/components/AboutPanel.vue";
import ApiFallbackPanel from "@/views/settings/components/ApiFallbackPanel.vue";
import ProxyPanel from "@/views/settings/components/ProxyPanel.vue";

let app: App<Element> | null = null;
let host: HTMLDivElement | null = null;

async function flushUi() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function mountPanel(component: typeof AboutPanel | typeof ApiFallbackPanel | typeof ProxyPanel) {
  host = document.createElement("div");
  document.body.appendChild(host);
  app = createApp(component);
  app.mount(host);
  return host;
}

function settingResponse(value: string) {
  return Promise.resolve({ code: 200, data: { settingValue: value }, msg: "ok" });
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  app?.unmount();
  app = null;
  host?.remove();
  host = null;
});

describe("settings panels", () => {
  it("AboutPanel 渲染版本信息与安全提醒", () => {
    const root = mountPanel(AboutPanel);
    expect(root.textContent).toContain("XianYu Seller Console");
    expect(root.textContent).toContain("账号与数据使用提醒");
  });

  it("ApiFallbackPanel 挂载时加载已有配置", async () => {
    settingApi.getSetting.mockImplementation(({ settingKey }: { settingKey: string }) =>
      settingKey === "api_delivery_fallback_enabled" ? settingResponse("1") : settingResponse("3,7")
    );

    const root = mountPanel(ApiFallbackPanel);
    await flushUi();

    expect(settingApi.getSetting).toHaveBeenCalledTimes(2);
    const checkbox = root.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
    const textInput = root.querySelector('input[type="text"]') as HTMLInputElement;
    expect(textInput.value).toBe("3,7");
  });

  it("ProxyPanel 空配置显示引导文案，添加代理生成输入行", async () => {
    settingApi.getSetting.mockImplementation(({ settingKey }: { settingKey: string }) => {
      if (settingKey === "proxy_enabled") return settingResponse("0");
      if (settingKey === "proxy_list") return settingResponse("[]");
      if (settingKey === "proxy_blacklist_ttl_seconds") return settingResponse("300");
      return settingResponse("3");
    });

    const root = mountPanel(ProxyPanel);
    await flushUi();

    expect(root.textContent).toContain("尚未配置代理");
    const addButton = Array.from(root.querySelectorAll("button")).find((b) => b.textContent?.trim() === "添加代理");
    expect(addButton).toBeTruthy();
    addButton!.click();
    await flushUi();
    expect(root.querySelectorAll(".settings__proxy-row")).toHaveLength(1);
  });
});
