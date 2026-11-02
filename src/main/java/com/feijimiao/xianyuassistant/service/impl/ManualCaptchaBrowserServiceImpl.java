package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.config.PlaywrightManager;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.ManualCaptchaBrowserService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.utils.CookieUtils;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.ScreenshotType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class ManualCaptchaBrowserServiceImpl implements ManualCaptchaBrowserService {

    private static final long VERIFICATION_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final double PAGE_NAVIGATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(35);
    private static final long POLL_INTERVAL_MILLIS = 750L;
    private static final long PAGE_HEALTH_CHECK_INTERVAL_MILLIS = 1500L;
    private static final long CAPTCHA_ERROR_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final Pattern CAPTCHA_ERROR_CODE_PATTERN =
            Pattern.compile("error\\s*:\\s*([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final String GOOFISH_DOMAIN = ".goofish.com";
    private static final String TAOBAO_DOMAIN = ".taobao.com";

    private final AccountService accountService;
    private final WebSocketTokenService tokenService;
    private final WebSocketService webSocketService;
    private final PlaywrightManager playwrightManager;
    // Keep the active marker tied to a concrete session. A bare account flag can
    // survive a closed page and make the next launch return without a sessionId.
    private final Map<Long, String> activeSessionIds = new ConcurrentHashMap<>();
    private final Map<Long, Long> captchaErrorCooldownUntil = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> launchLocks = new ConcurrentHashMap<>();
    private final Map<String, RemoteSession> remoteSessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "manual-captcha-browser");
        thread.setDaemon(true);
        return thread;
    });

    public ManualCaptchaBrowserServiceImpl(
            AccountService accountService,
            WebSocketTokenService tokenService,
            WebSocketService webSocketService,
            PlaywrightManager playwrightManager) {
        this.accountService = accountService;
        this.tokenService = tokenService;
        this.webSocketService = webSocketService;
        this.playwrightManager = playwrightManager;
    }

    @Override
    public LaunchResult openVerificationWindow(Long accountId) {
        if (accountId == null) {
            return launch(false, "账号ID不能为空", null, null);
        }
        Long cooldownUntil = captchaErrorCooldownUntil.get(accountId);
        if (cooldownUntil != null) {
            long remaining = cooldownUntil - System.currentTimeMillis();
            if (remaining > 0) {
                long seconds = Math.max(1L, (remaining + 999L) / 1000L);
                return launch(false, "平台验证页已被作废，账号正在冷却，请等待约 " + seconds
                        + " 秒后再重试；不要连续点击重新打开", null, null);
            }
            captchaErrorCooldownUntil.remove(accountId, cooldownUntil);
        }
        String verificationUrl = tokenService.getPendingCaptchaUrl(accountId);
        if (verificationUrl == null) {
            // The platform challenge URL is short-lived. Re-probe once so a stale
            // local hold can be replaced with a fresh official verification URL.
            tokenService.clearCaptchaWait(accountId);
            try {
                if (webSocketService.startWebSocket(accountId)) {
                    return launch(true, "验证状态已恢复，WebSocket连接已启动", null, null);
                }
            } catch (com.feijimiao.xianyuassistant.exception.CaptchaRequiredException e) {
                verificationUrl = e.getCaptchaUrl();
            } catch (Exception e) {
                log.warn("【账号{}】重新生成滑块验证链接失败: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
        }
        if (verificationUrl == null || verificationUrl.isBlank()) {
            return launch(false, "验证链接已过期，请重新点击启动连接生成验证链接", null, null);
        }
        String cookieText = accountService.getCookieByAccountId(accountId);
        String unb = accountService.getXianyuUserId(accountId);
        if (cookieText == null || cookieText.isBlank() || unb == null || unb.isBlank()) {
            return launch(false, "未找到当前账号的有效登录凭据，请先扫码更新", null, null);
        }
        ReentrantLock launchLock = launchLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock(true));
        launchLock.lock();
        try {
            String existingSessionId = activeSessionIds.get(accountId);
            if (existingSessionId != null) {
                RemoteSession existing = remoteSessions.get(existingSessionId);
                if (existing != null && existing.active) {
                    return launch(true, "同会话验证窗口已经打开，请在当前页面完成滑块",
                            existing.sessionId, existing.screenshot);
                }
                // The browser page may have closed before its worker reached the
                // finally block. Remove the stale marker and create a fresh session.
                activeSessionIds.remove(accountId, existingSessionId);
            }

            String resolvedVerificationUrl = verificationUrl;
            String sessionId = UUID.randomUUID().toString();
            RemoteSession session = new RemoteSession(sessionId, accountId);
            remoteSessions.put(sessionId, session);
            activeSessionIds.put(accountId, sessionId);
            try {
                executor.execute(() -> runVerification(session, unb, cookieText, resolvedVerificationUrl));
            } catch (RuntimeException e) {
                remoteSessions.remove(sessionId);
                activeSessionIds.remove(accountId, sessionId);
                log.error("【账号{}】提交远程验证任务失败: errorType={}", accountId,
                        e.getClass().getSimpleName());
                return launch(false, "服务器验证任务启动失败，请稍后重试", null, null);
            }
            return launch(true, "同会话验证窗口已打开，请在当前页面完成滑块", sessionId, null);
        } finally {
            launchLock.unlock();
        }
    }

    private LaunchResult launch(boolean accepted, String message, String sessionId, String screenshot) {
        RemoteSession session = sessionId == null ? null : remoteSessions.get(sessionId);
        return new LaunchResult(accepted, message, sessionId, screenshot,
                session == null ? 1280 : session.viewportWidth,
                session == null ? 800 : session.viewportHeight);
    }

    private void runVerification(RemoteSession session, String unb, String cookieText, String verificationUrl) {
        Long accountId = session.accountId;
        try (BrowserContext context = playwrightManager.createContext()) {
                session.message = "正在启动服务器浏览器";
                Browser browser = context.browser();
                session.browser = browser;
                session.context = context;
                Map<String, String> originalCookies = parseCookieText(cookieText);
                context.addCookies(buildBrowserCookies(originalCookies));
                String originalVerificationCookie = originalCookies.get("x5sec");

                Page page = context.newPage();
                session.message = "正在加载验证页面";
                page.navigate(verificationUrl,
                        new Page.NavigateOptions()
                                .setTimeout(PAGE_NAVIGATION_TIMEOUT_MILLIS)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                session.page = page;
                session.viewportWidth = 1280;
                session.viewportHeight = 800;
                session.screenshot = capture(page);
                log.info("【账号{}】远程同会话人工验证页面已打开，sessionId={}", accountId, session.sessionId);

                long deadline = System.currentTimeMillis() + VERIFICATION_TIMEOUT_MILLIS;
                long nextPageHealthCheckAt = 0L;
                while (System.currentTimeMillis() < deadline && !context.isClosed() && !page.isClosed()) {
                    long now = System.currentTimeMillis();
                    if (now >= nextPageHealthCheckAt) {
                        nextPageHealthCheckAt = now + PAGE_HEALTH_CHECK_INTERVAL_MILLIS;
                        String errorCode = captchaErrorCode(page);
                        if (errorCode != null) {
                            captchaErrorCooldownUntil.put(accountId,
                                    System.currentTimeMillis() + CAPTCHA_ERROR_COOLDOWN_MILLIS);
                            // Keep the persistent captcha alert. Never clear it or request
                            // another token while the platform has rejected this challenge.
                            tokenService.restoreCaptchaWait(accountId);
                            session.active = false;
                            session.screenshot = null;
                            session.refreshing = false;
                            session.message = "平台验证页已失效（" + errorCode
                                    + "），当前账号进入 5 分钟冷却。请等待冷却结束后再重试，"
                                    + "不要连续点击重新打开。";
                            log.warn("【账号{}】验证页返回平台错误页 code={}，停止自动刷新并进入冷却",
                                    accountId, errorCode);
                            return;
                        }
                    }
                    session.screenshot = capture(page);
                    Map<String, String> browserCookies = readBrowserCookies(context);
                    if (session.userInteracted
                            && verificationCompleted(originalCookies, browserCookies, verificationUrl, page.url())) {
                        // A visual success is only a probe trigger. The platform can
                        // keep x5sec unchanged, so the real Token endpoint decides
                        // whether the captcha was accepted.
                        if (persistVerifiedCookies(accountId, unb, originalCookies, browserCookies)) {
                            log.info("【账号{}】同会话滑块验证完成，Cookie已同步并已触发重连", accountId);
                            session.completed = true;
                            session.message = "验证完成，连接正在恢复";
                            return;
                        }
                        // The challenge may take a moment to propagate. Keep polling
                        // with the newest cookie baseline instead of exiting forever.
                        originalCookies.clear();
                        originalCookies.putAll(browserCookies);
                        originalVerificationCookie = browserCookies.get("x5sec");
                        verificationUrl = page.url();
                    }
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                }
                log.warn("【账号{}】同会话人工验证窗口已关闭或超时，保留等待验证状态", accountId);
                session.active = false;
                session.screenshot = null;
                session.message = "验证窗口已关闭或超时";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.active = false;
            session.screenshot = null;
            session.message = "验证会话已中断";
        } catch (Exception e) {
            log.error("【账号{}】同会话人工验证失败: errorType={}, message={}",
                    accountId, e.getClass().getSimpleName(), e.getMessage(), e);
            session.active = false;
            session.screenshot = null;
            session.message = "远程验证页面启动失败：" + (e.getMessage() == null ? "请重新打开" : e.getMessage());
        } finally {
            session.active = false;
            activeSessionIds.remove(accountId, session.sessionId);
            if (!session.completed) {
                executor.execute(() -> {
                    try { Thread.sleep(30_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    remoteSessions.remove(session.sessionId);
                });
            }
        }
    }

    private String captchaErrorCode(Page page) {
        try {
            String visibleText = page.locator("body").innerText();
            return captchaErrorCode(visibleText);
        } catch (Exception e) {
            return null;
        }
    }

    static String captchaErrorCode(String visibleText) {
        if (visibleText == null || visibleText.isBlank()) {
            return null;
        }
        String normalized = visibleText.toLowerCase(Locale.ROOT);
        if (!normalized.contains("oops... something's wrong")
                && !normalized.contains("please refresh and try again")
                && !normalized.contains("error:")) {
            return null;
        }
        Matcher matcher = CAPTCHA_ERROR_CODE_PATTERN.matcher(visibleText);
        return matcher.find() ? matcher.group(1) : "平台错误";
    }

    static boolean isCaptchaErrorPageText(String visibleText) {
        return captchaErrorCode(visibleText) != null;
    }

    private String capture(Page page) {
        try {
            return Base64.getEncoder().encodeToString(page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(80)));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public SessionSnapshot getSession(Long accountId, String sessionId) {
        RemoteSession session = remoteSessions.get(sessionId);
        if (session == null || !accountId.equals(session.accountId)) {
            return new SessionSnapshot(false, false, "验证会话不存在或已过期", null, 1280, 800);
        }
        synchronized (session) {
            if (session.active && !session.refreshing && session.page != null && !session.page.isClosed()) {
                String screenshot = capture(session.page);
                if (screenshot != null) session.screenshot = screenshot;
            }
            return new SessionSnapshot(session.active, session.completed, session.message,
                    session.screenshot, session.viewportWidth, session.viewportHeight);
        }
    }

    @Override
    public boolean handleMouseEvent(Long accountId, String sessionId, String eventType, double x, double y) {
        RemoteSession session = remoteSessions.get(sessionId);
        if (session == null || !accountId.equals(session.accountId) || !session.active
                || session.completed || session.page == null) return false;
        synchronized (session) {
            try {
                if ("down".equals(eventType)) {
                    session.userInteracted = true;
                    session.page.mouse().move(x, y);
                    session.page.mouse().down();
                } else if ("move".equals(eventType)) {
                    session.page.mouse().move(x, y);
                } else if ("up".equals(eventType)) {
                    session.page.mouse().move(x, y);
                    session.page.mouse().up();
                } else return false;
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    public void closeSession(Long accountId, String sessionId) {
        RemoteSession session = remoteSessions.remove(sessionId);
        if (session == null || !accountId.equals(session.accountId)) return;
        session.active = false;
        session.screenshot = null;
        activeSessionIds.remove(accountId, sessionId);
        try { if (session.page != null && !session.page.isClosed()) session.page.close(); } catch (Exception ignored) { }
        try { if (session.context != null) session.context.close(); } catch (Exception ignored) { }
        // The Browser instance is shared by PlaywrightManager; only close this session context.
    }

    private static final class RemoteSession {
        final String sessionId;
        final Long accountId;
        volatile Page page;
        volatile Browser browser;
        volatile BrowserContext context;
        volatile String screenshot;
        volatile String message = "正在打开验证页面";
        volatile boolean active = true;
        volatile boolean completed;
        volatile boolean userInteracted;
        volatile boolean refreshing;
        volatile int viewportWidth = 1280;
        volatile int viewportHeight = 800;
        RemoteSession(String sessionId, Long accountId) { this.sessionId = sessionId; this.accountId = accountId; }
    }

    private boolean persistVerifiedCookies(
            Long accountId,
            String expectedUnb,
            Map<String, String> originalCookies,
            Map<String, String> browserCookies) {
        try {
            String browserUnb = browserCookies.get("unb");
            if (browserUnb != null && !browserUnb.isBlank() && !expectedUnb.equals(browserUnb)) {
                throw new IllegalStateException("验证窗口登录账号与当前账号不一致");
            }
            Map<String, String> mergedCookies = new LinkedHashMap<>(originalCookies);
            mergedCookies.putAll(browserCookies);
            String mergedCookieText = CookieUtils.formatCookies(mergedCookies);
            if (!accountService.updateAccountCookie(accountId, expectedUnb, mergedCookieText)) {
                throw new IllegalStateException("验证后的Cookie写回失败");
            }

            // Only a successful token probe clears the persistent captcha state.
            if (!webSocketService.recoverAfterManualCaptcha(accountId)) {
                tokenService.restoreCaptchaWait(accountId);
                log.info("【账号{}】验证Cookie已同步，但平台Token仍未放行，继续等待验证", accountId);
                return false;
            }
            return true;
        } catch (Exception e) {
            tokenService.restoreCaptchaWait(accountId);
            log.warn("【账号{}】验证Cookie同步或恢复失败: errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    static boolean verificationCompleted(
            Map<String, String> originalCookies,
            Map<String, String> browserCookies,
            String originalUrl,
            String currentUrl) {
        if (verificationCompleted(
                originalCookies == null ? null : originalCookies.get("x5sec"),
                browserCookies,
                originalUrl,
                currentUrl)) {
            return true;
        }
        return !isVerificationUrl(currentUrl)
                && hasRiskCookieChanged(originalCookies, browserCookies);
    }

    /** x5sec is not guaranteed to change after a successful slider. */
    private static boolean hasRiskCookieChanged(
            Map<String, String> originalCookies,
            Map<String, String> browserCookies) {
        if (originalCookies == null || browserCookies == null) {
            return false;
        }
        Set<String> riskCookieNames = Set.of(
                "x5sec", "tfstk", "_m_h5_tk", "_m_h5_tk_enc", "cookie2",
                "sgcookie", "cna", "isg", "l", "xlly_s", "_samesite_flag_");
        for (String name : riskCookieNames) {
            if (!java.util.Objects.equals(originalCookies.get(name), browserCookies.get(name))) {
                return true;
            }
        }
        return false;
    }

    static boolean verificationCompleted(
            String originalX5sec,
            Map<String, String> browserCookies,
            String originalUrl,
            String currentUrl) {
        String currentX5sec = browserCookies.get("x5sec");
        if (currentX5sec != null && !currentX5sec.isBlank()
                && !currentX5sec.equals(originalX5sec)) {
            return true;
        }
        return isVerificationUrl(originalUrl) && !isVerificationUrl(currentUrl)
                && currentX5sec != null && !currentX5sec.isBlank()
                && currentUrl != null && currentUrl.toLowerCase(Locale.ROOT).contains("goofish.com");
    }

    static boolean isVerificationUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.contains("punish")
                || normalized.contains("captcha")
                || normalized.contains("validate")
                || normalized.contains("x5secdata");
    }

    static Map<String, String> parseCookieText(String cookieText) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieText == null || cookieText.isBlank()) {
            return cookies;
        }
        for (String part : cookieText.split(";\\s*")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (!name.isBlank() && !value.isBlank()) {
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    private static List<Cookie> buildBrowserCookies(Map<String, String> cookies) {
        List<Cookie> browserCookies = new ArrayList<>();
        cookies.forEach((name, value) -> {
            browserCookies.add(new Cookie(name, value).setDomain(GOOFISH_DOMAIN).setPath("/"));
            browserCookies.add(new Cookie(name, value).setDomain(TAOBAO_DOMAIN).setPath("/"));
        });
        return browserCookies;
    }

    private static Map<String, String> readBrowserCookies(BrowserContext context) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (Cookie cookie : context.cookies()) {
            if (cookie.name != null && !cookie.name.isBlank()
                    && cookie.value != null && !cookie.value.isBlank()) {
                cookies.put(cookie.name, cookie.value);
            }
        }
        return cookies;
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }
}
