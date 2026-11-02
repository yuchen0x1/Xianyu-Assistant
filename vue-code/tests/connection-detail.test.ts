import { createApp, nextTick, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ConnectionDetail from "@/views/connection/components/ConnectionDetail.vue";

const websocketApi = vi.hoisted(() => ({
  getConnectionStatus: vi.fn(),
  startConnection: vi.fn(),
  stopConnection: vi.fn(),
  clearCaptchaWait: vi.fn(),
  openCaptchaBrowser: vi.fn(),
  updateCookie: vi.fn(),
}));

const operationLogApi = vi.hoisted(() => ({
  queryOperationLogs: vi.fn(),
}));

vi.mock("@/api/websocket", () => websocketApi);
vi.mock("@/api/operation-log", () => operationLogApi);
vi.mock("@/views/connection/components/CredentialModal.vue", () => ({
  default: { template: "<div />" },
}));
vi.mock("@/views/connection/components/ManualUpdateCookieModal.vue", () => ({
  default: { template: "<div />" },
}));
vi.mock("@/views/connection/components/QRUpdateDialog.vue", () => ({
  default: { template: "<div />" },
}));
vi.mock("@/views/connection/components/CaptchaGuideDialog.vue", () => ({
  default: { template: "<div />" },
}));

const baseStatus = {
  xianyuAccountId: 3,
  connected: true,
  status: "已连接",
  captchaPending: false,
  reconnecting: false,
  cookieStatus: 1,
  autoDeliveryOn: true,
  autoReplyOn: true,
};

type StatusResponse = {
  code: number;
  data: typeof baseStatus;
};

let app: App<Element> | null = null;
let host: HTMLDivElement | null = null;

async function flushUi() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function mountDetailWithExistingMocks() {
  operationLogApi.queryOperationLogs.mockResolvedValue({
    code: 200,
    data: { logs: [] },
  });

  host = document.createElement("div");
  document.body.appendChild(host);
  app = createApp(ConnectionDetail, { accountId: 3, accountName: "账号_3" });
  app.mount(host);
  await flushUi();
  return host;
}

async function mountDetail(status = baseStatus) {
  websocketApi.getConnectionStatus.mockResolvedValue({ code: 200, data: status });
  return mountDetailWithExistingMocks();
}

function findStatusCard(root: ParentNode, title: string): HTMLElement {
  const card = Array.from(root.querySelectorAll<HTMLElement>(".status-card")).find(
    (item) => item.querySelector(".status-card__title")?.textContent?.trim() === title,
  );
  if (!card) throw new Error(`未找到状态卡片: ${title}`);
  return card;
}

beforeEach(() => {
  vi.clearAllMocks();
  websocketApi.openCaptchaBrowser.mockResolvedValue({ code: 200, data: "opened" });
});

afterEach(() => {
  app?.unmount();
  app = null;
  host?.remove();
  host = null;
  vi.useRealTimers();
  delete (document as Document & { visibilityState?: string }).visibilityState;
});

describe("ConnectionDetail", () => {
  it.each([
    ["等待官方验证", { ...baseStatus, connected: false, captchaPending: true }],
    ["WebSocket 已断开", { ...baseStatus, connected: false }],
  ])("自动回复已开启但%s时显示为当前暂停", async (_scenario, status) => {
    const root = await mountDetail(status);
    const card = findStatusCard(root, "自动回复");

    expect(card.classList.contains("status-card--warning")).toBe(true);
    expect(card.querySelector(".status-card__desc")?.textContent?.trim()).toBe("已开启，当前暂停");
  });

  it("等待官方验证时不自动打开服务器验证窗口", async () => {
    const root = await mountDetail({ ...baseStatus, connected: false, captchaPending: true });

    expect(websocketApi.openCaptchaBrowser).not.toHaveBeenCalled();
    expect(root.textContent).toContain("扫码更新");
  });

  it("窗口重新获得焦点时立即刷新连接状态", async () => {
    await mountDetail();
    websocketApi.getConnectionStatus.mockClear();

    window.dispatchEvent(new Event("focus"));
    await flushUi();

    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(1);
  });

  it("等待官方验证时回到页面只刷新状态", async () => {
    await mountDetail({ ...baseStatus, captchaPending: true });
    websocketApi.getConnectionStatus.mockClear();
    websocketApi.clearCaptchaWait.mockClear();

    window.dispatchEvent(new Event("focus"));
    await flushUi();

    expect(websocketApi.clearCaptchaWait).not.toHaveBeenCalled();
    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(1);
  });

  it("页面重新可见时立即刷新连接状态", async () => {
    await mountDetail();
    websocketApi.getConnectionStatus.mockClear();
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "visible",
    });

    document.dispatchEvent(new Event("visibilitychange"));
    await flushUi();

    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(1);
  });

  it("页面恢复时合并短时间内连续触发的可见与焦点事件", async () => {
    await mountDetail();
    websocketApi.getConnectionStatus.mockClear();
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "visible",
    });

    document.dispatchEvent(new Event("visibilitychange"));
    window.dispatchEvent(new Event("focus"));
    await flushUi();

    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(1);
  });

  it("同一次回前台的可见与焦点事件即使相隔超过旧时间窗也只刷新一次", async () => {
    vi.useFakeTimers();
    await mountDetail();
    websocketApi.getConnectionStatus.mockClear();
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "hidden",
    });
    document.dispatchEvent(new Event("visibilitychange"));

    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "visible",
    });
    document.dispatchEvent(new Event("visibilitychange"));
    await flushUi();
    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(150);
    window.dispatchEvent(new Event("focus"));
    await flushUi();

    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(1);
  });

  it("较早请求晚返回时不会覆盖较新的连接状态", async () => {
    const earlierResponse = deferred<StatusResponse>();
    const latestResponse = deferred<StatusResponse>();
    websocketApi.getConnectionStatus
      .mockReturnValueOnce(earlierResponse.promise)
      .mockReturnValueOnce(latestResponse.promise);
    const root = await mountDetailWithExistingMocks();

    window.dispatchEvent(new Event("focus"));
    await flushUi();
    expect(websocketApi.getConnectionStatus).toHaveBeenCalledTimes(2);

    latestResponse.resolve({
      code: 200,
      data: { ...baseStatus, connected: false },
    });
    await flushUi();
    expect(
      findStatusCard(root, "自动回复").querySelector(".status-card__desc")?.textContent?.trim(),
    ).toBe("已开启，当前暂停");

    earlierResponse.resolve({ code: 200, data: baseStatus });
    await flushUi();

    const card = findStatusCard(root, "自动回复");
    expect(card.classList.contains("status-card--warning")).toBe(true);
    expect(card.querySelector(".status-card__desc")?.textContent?.trim()).toBe("已开启，当前暂停");
  });

  it("刷新连接状态失败时保留最后一次成功状态", async () => {
    const root = await mountDetail();
    expect(findStatusCard(root, "自动回复")).toBeTruthy();
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    websocketApi.getConnectionStatus.mockRejectedValueOnce(new Error("network unavailable"));

    window.dispatchEvent(new Event("focus"));
    await flushUi();

    const card = findStatusCard(root, "自动回复");
    expect(card.classList.contains("status-card--success")).toBe(true);
    expect(card.querySelector(".status-card__desc")?.textContent?.trim()).toBe("已开启");
  });

  it("组件卸载后忽略仍在等待的连接状态响应", async () => {
    const pendingResponse = deferred<StatusResponse>();
    let responseDataReads = 0;
    websocketApi.getConnectionStatus.mockReturnValueOnce(pendingResponse.promise);
    await mountDetailWithExistingMocks();

    app?.unmount();
    app = null;
    pendingResponse.resolve({
      code: 200,
      get data() {
        responseDataReads++;
        return baseStatus;
      },
    });
    await flushUi();

    expect(responseDataReads).toBe(0);
  });
});
