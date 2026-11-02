package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.config.PlaywrightManager;
import com.feijimiao.xianyuassistant.constants.OperationConstants;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.exception.XianyuServiceBusyException;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.utils.SessionCookieJar;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cookie刷新服务实现
 * 参考Python代码的Cookie刷新逻辑
 *
 * 核心改进（对齐Python的requests.Session行为）：
 * 1. 使用SessionCookieJar自动管理Cookie（模拟requests.Session）
 *    - OkHttp自动解析响应Set-Cookie并合并到jar
 *    - 后续请求自动携带最新Cookie，无需手动设置Cookie头
 * 2. 保留最新策略：新Cookie值覆盖旧Cookie
 * 3. 添加并发锁，防止多线程同时刷新同一账号的Cookie
 */
@Slf4j
@Service
public class CookieRefreshServiceImpl implements CookieRefreshService {
    private static final String HAS_LOGIN_URL = "https://passport.goofish.com/newlogin/hasLogin.do";
    private static final String GOOFISH_IM_URL = "https://www.goofish.com/im";
    private static final String GOOFISH_AUTH_PROBE_URL = "https://www.goofish.com/bought";
    private static final String GOOFISH_COOKIE_DOMAIN = ".goofish.com";
    private static final String TAOBAO_COOKIE_DOMAIN = ".taobao.com";

    @Autowired
    private XianyuCookieMapper cookieMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.service.EmailNotifyService emailNotifyService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    private static final long BROWSER_REFRESH_COOLDOWN_MS = 30 * 60 * 1000L;
    private final Map<Long, Long> lastBrowserRefreshTime = new ConcurrentHashMap<>();

    public CookieRefreshServiceImpl() {
    }

    /**
     * 获取账号级别的锁对象
     */
    private Object getRefreshLock(Long accountId) {
        return refreshLocks.computeIfAbsent(accountId, k -> new Object());
    }

    @Override
    public boolean checkLoginStatus(Long accountId) {
        synchronized (getRefreshLock(accountId)) {
            return doCheckLoginStatus(accountId, true); // 记录操作日志
        }
    }

    @Override
    public boolean checkLoginStatusQuietly(Long accountId) {
        synchronized (getRefreshLock(accountId)) {
            return doCheckLoginStatus(accountId, false); // 不记录操作日志
        }
    }

    /**
     * 执行hasLogin检查（带重试机制）
     * 参考Python XianyuApis.hasLogin方法
     *
     * 核心改造：使用SessionCookieJar自动管理Cookie + 添加重试机制
     * - OkHttp自动从CookieJar加载Cookie，无需手动设置Cookie头
     * - OkHttp自动解析响应Set-Cookie并回调saveFromResponse
     * - hasLogin成功后从jar获取更新后的Cookie持久化到数据库
     * - 最多重试2次（retry_count < 2），与Python一致
     * 
     * @param accountId 账号ID
     * @param logOperation 是否记录操作日志（true=主动保活，false=被动检查）
     */
    private boolean doCheckLoginStatus(Long accountId, boolean logOperation) {
        return doCheckLoginStatusWithRetry(accountId, 0, logOperation);
    }

    /**
     * 执行hasLogin检查（带重试计数）
     * 参考Python: hasLogin(retry_count=0)
     * 
     * @param accountId 账号ID
     * @param retryCount 重试次数
     * @param logOperation 是否记录操作日志
     */
    private boolean doCheckLoginStatusWithRetry(Long accountId, int retryCount, boolean logOperation) {
        if (retryCount >= 2) {
            log.error("【账号{}】Login检查失败，重试次数过多", accountId);
            return false;
        }

        try {
            String logPrefix = logOperation ? "主动保活" : "被动检查";
            log.info("【账号{}】开始{}登录状态... (重试次数: {}/2)", accountId, logPrefix, retryCount);

            XianyuCookie cookie = cookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );

            if (cookie == null || cookie.getCookieText() == null) {
                log.warn("【账号{}】未找到Cookie", accountId);
                return false;
            }

            String oldCookieStr = cookie.getCookieText();
            Map<String, String> cookies = XianyuSignUtils.parseCookies(oldCookieStr);

            SessionCookieJar cookieJar = new SessionCookieJar(oldCookieStr);

            FormBody.Builder formBuilder = new FormBody.Builder();
            formBuilder.add("appName", "xianyu");
            formBuilder.add("fromSite", "77");
            formBuilder.add("hid", cookies.getOrDefault("unb", ""));
            formBuilder.add("ltl", "true");
            formBuilder.add("appEntrance", "web");
            formBuilder.add("_csrf_token", cookies.getOrDefault("XSRF-TOKEN", ""));
            formBuilder.add("umidToken", "");
            formBuilder.add("hsiz", cookies.getOrDefault("cookie2", ""));
            formBuilder.add("bizParams", "taobaoBizLoginFrom=web");
            formBuilder.add("mainPage", "false");
            formBuilder.add("isMobile", "false");
            formBuilder.add("lang", "zh_CN");
            formBuilder.add("returnUrl", "");
            formBuilder.add("isIframe", "true");
            formBuilder.add("documentReferer", "https://www.goofish.com/");
            formBuilder.add("defaultView", "hasLogin");
            formBuilder.add("umidTag", "SERVER");
            formBuilder.add("deviceId", cookies.getOrDefault("cna", ""));

            Request request = new Request.Builder()
                    .url(HAS_LOGIN_URL)
                    .header("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://passport.goofish.com/")
                    .header("Origin", "https://passport.goofish.com")
                    .post(formBuilder.build())
                    .build();

            OkHttpClient httpClient = cookieJar.createHttpClient();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("【账号{}】检查登录状态失败: HTTP {}, 准备重试... (重试次数: {}/2)", 
                            accountId, response.code(), retryCount + 1);
                    Thread.sleep(500);
                    return doCheckLoginStatusWithRetry(accountId, retryCount + 1, logOperation);
                }

                String responseBody = response.body().string();
                log.debug("【账号{}】hasLogin响应已接收，HTTP状态: {}，响应长度: {}",
                        accountId, response.code(), responseBody.length());

                // 检测风控（参考Python实现）
                boolean requiresUserValidation = responseBody != null
                        && responseBody.contains("FAIL_SYS_USER_VALIDATE");
                boolean serverBusy = responseBody != null && responseBody.contains("被挤爆啦");
                if (serverBusy) {
                    log.warn("【账号{}】hasLogin接口暂时繁忙，本轮不重试且不改变账号或Cookie状态", accountId);
                    throw new XianyuServiceBusyException();
                }
                boolean isRiskControl = responseBody != null && (
                    requiresUserValidation ||
                    responseBody.contains("RGV587_ERROR") ||
                    responseBody.contains("FAIL_SYS_RGV587_ERROR"));

                if (isRiskControl) {
                    log.error("【账号{}】hasLogin触发风控，响应长度: {}", accountId, responseBody.length());
                    log.error("【账号{}】系统目前无法自动解决，请进入闲鱼网页版-点击消息-过滑块-复制最新的Cookie", accountId);
                    
                    // 风控验证不等于Cookie失效；保留Cookie才能在重启后继续低频探测。
                    markAccountAsCookieRefreshAbnormal(
                            accountId, "hasLogin触发风控验证，需要人工处理滑块");

                    // 记录操作日志
                    operationLogService.log(accountId,
                            OperationConstants.Type.VERIFY,
                            OperationConstants.Module.COOKIE,
                            "hasLogin触发风控验证，需要人工处理滑块",
                            OperationConstants.Status.FAIL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, "触发风控", null);

                    // 发送邮件通知
                    try {
                        XianyuAccount account = accountMapper.selectById(accountId);
                        String accountNote = account != null ? account.getAccountNote() : null;
                        if (emailNotifyService != null) {
                            emailNotifyService.sendCaptchaRequiredEmail(accountId, accountNote, "hasLogin时触发风控验证");
                        }
                    } catch (Exception e) {
                        log.error("【账号{}】发送风控验证邮件通知失败", accountId, e);
                    }

                    throw new CaptchaRequiredException("");
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) responseMap.get("content");

                if (content != null && Boolean.TRUE.equals(content.get("success"))) {
                    log.info("【账号{}】✅ 登录状态有效", accountId);

                    String newCookieStr = cookieJar.getCookieString();
                    boolean cookieChanged = !newCookieStr.equals(oldCookieStr);

                    if (cookieChanged) {
                        Map<String, String> oldCookieMap = XianyuSignUtils.parseCookies(oldCookieStr);
                        Map<String, String> newCookieMap = XianyuSignUtils.parseCookies(newCookieStr);
                        String oldMh5tk = oldCookieMap.get("_m_h5_tk");
                        String newMh5tk = newCookieMap.get("_m_h5_tk");
                        boolean mh5tkUpdated = (newMh5tk != null && !newMh5tk.equals(oldMh5tk));

                        // 关键字段保护：hasLogin 返回的 Cookie 若丢失 unb/_m_h5_tk，
                        // 说明响应不完整，禁止用残缺 Cookie 覆盖数据库中完整的登录凭据。
                        if (!XianyuSignUtils.hasCriticalCookieFields(newCookieStr)) {
                            log.warn("【账号{}】hasLogin 返回的 Cookie 缺少关键字段(unb/_m_h5_tk)，放弃覆盖数据库中的完整 Cookie", accountId);
                            // hasLogin 的 success=true 是登录态有效的权威结果。即使增量 Set-Cookie 不完整，
                            // 也必须恢复状态为有效，否则 WebSocket 与商品 API 会因 cookie_status=2 被永久阻断。
                            java.time.LocalDateTime updatedTime = java.time.LocalDateTime.now();
                            cookieMapper.update(null,
                                    new LambdaUpdateWrapper<XianyuCookie>()
                                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                                            .set(XianyuCookie::getCookieStatus, 1)
                                            .set(XianyuCookie::getUpdatedTime, updatedTime)
                            );
                            log.info("【账号{}】✅ 登录状态有效（保留原 Cookie，并恢复Cookie状态）", accountId);
                            return true;
                        }

                        java.time.LocalDateTime updatedTime = java.time.LocalDateTime.now();
                        cookie.setCookieText(newCookieStr);
                        cookie.setCookieStatus(1);
                        cookie.setUpdatedTime(updatedTime);
                        // 仅当 _m_h5_tk 为合法 token 格式时才更新，防止密文/损坏内容嵌套写入
                        if (mh5tkUpdated && XianyuSignUtils.isValidMh5tk(newMh5tk)) {
                            cookie.setMH5Tk(newMh5tk);
                        } else {
                            // 本次不更新 _m_h5_tk：置空跳过该字段，防止把读取到的嵌套密文再次写回膨胀
                            cookie.setMH5Tk(null);
                        }
                        cookieMapper.updateById(cookie);

                        if (mh5tkUpdated) {
                            log.info("【账号{}】_m_h5_tk已从hasLogin响应中更新", accountId);
                        }
                        log.info("【账号{}】✅ Cookie已通过SessionCookieJar自动更新到数据库", accountId);
                    } else {
                        if (cookie.getCookieStatus() == null || cookie.getCookieStatus() != 1) {
                            java.time.LocalDateTime updatedTime = java.time.LocalDateTime.now();
                            cookieMapper.update(null,
                                    new LambdaUpdateWrapper<XianyuCookie>()
                                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                                            .set(XianyuCookie::getCookieStatus, 1)
                                            .set(XianyuCookie::getUpdatedTime, updatedTime)
                            );
                            log.info("【账号{}】✅ Cookie状态已更新为有效", accountId);
                        }
                        log.info("【账号{}】Cookie无变化，登录态仍然有效", accountId);
                    }

                    // 只有在主动保活时才记录操作日志
                    if (logOperation) {
                        operationLogService.log(accountId,
                                OperationConstants.Type.UPDATE,
                                OperationConstants.Module.COOKIE,
                                "Cookie自动刷新成功",
                                OperationConstants.Status.SUCCESS,
                                OperationConstants.TargetType.COOKIE,
                                String.valueOf(accountId),
                                null, null, null, null);
                    }

                    return true;
                } else {
                    log.warn("【账号{}】⚠️ 登录状态无效，准备重试... (重试次数: {}/2)", accountId, retryCount + 1);

                    // 只有在主动保活时才记录操作日志
                    if (logOperation) {
                        operationLogService.log(accountId,
                                OperationConstants.Type.VERIFY,
                                OperationConstants.Module.COOKIE,
                                "登录状态检查失败，准备重试",
                                OperationConstants.Status.FAIL,
                                OperationConstants.TargetType.COOKIE,
                                String.valueOf(accountId),
                                null, null, "登录状态无效", null);
                    }

                    Thread.sleep(500);
                    return doCheckLoginStatusWithRetry(accountId, retryCount + 1, logOperation);
                }
            }

        } catch (CaptchaRequiredException | XianyuServiceBusyException e) {
            throw e;
        } catch (Exception e) {
            log.error("【账号{}】检查登录状态异常，准备重试... (重试次数: {}/2)", accountId, retryCount + 1, e);

            // 只有在主动保活时才记录操作日志
            if (logOperation) {
                operationLogService.log(accountId,
                        OperationConstants.Type.VERIFY,
                        OperationConstants.Module.COOKIE,
                        "检查登录状态异常，准备重试: " + e.getMessage(),
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, e.getMessage(), null);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return doCheckLoginStatusWithRetry(accountId, retryCount + 1, logOperation);
        }
    }

    @Override
    public boolean refreshCookie(Long accountId) {
        synchronized (getRefreshLock(accountId)) {
            try {
                log.info("【账号{}】开始刷新Cookie...", accountId);

                // 通过hasLogin接口刷新Cookie
                boolean success = doCheckLoginStatus(accountId, true); // 主动刷新，记录日志
                if (!success) {
                    log.warn("【账号{}】hasLogin刷新失败，开始触发浏览器兜底刷新Cookie", accountId);
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "hasLogin刷新失败，开始触发浏览器兜底刷新Cookie",
                            OperationConstants.Status.PARTIAL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, null, null);
                    success = refreshCookieWithBrowser(accountId);
                }

                if (success) {
                    log.info("【账号{}】✅ Cookie刷新成功", accountId);
                    updateAccountStatusToNormal(accountId, "Cookie刷新成功，账号状态恢复正常");

                    // 记录操作日志
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "Cookie刷新成功",
                            OperationConstants.Status.SUCCESS,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, null, null);
                } else {
                    log.error("【账号{}】❌ Cookie刷新失败，需要手动更新", accountId);
                    markAccountAsCookieRefreshAbnormal(accountId, "hasLogin和浏览器兜底刷新均失败，需要手动处理Cookie");

                    // 记录操作日志
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "Cookie刷新失败，需要手动更新，账号已标记为异常待处理",
                            OperationConstants.Status.FAIL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, "hasLogin和浏览器兜底刷新均失败", null);
                }

                return success;

            } catch (XianyuServiceBusyException e) {
                log.warn("【账号{}】闲鱼服务暂时繁忙，本轮Cookie刷新延后且不改变账号状态", accountId);
                return false;
            } catch (CaptchaRequiredException e) {
                throw e;
            } catch (Exception e) {
                log.error("【账号{}】刷新Cookie失败", accountId, e);
                markAccountAsCookieRefreshAbnormal(accountId, "刷新Cookie异常: " + e.getMessage());

                // 记录操作日志
                operationLogService.log(accountId,
                        OperationConstants.Type.REFRESH,
                        OperationConstants.Module.COOKIE,
                        "刷新Cookie异常: " + e.getMessage(),
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, e.getMessage(), null);

                return false;
            }
        }
    }

    @Override
    public boolean isBrowserRefreshInCooldown(Long accountId) {
        Long lastTime = lastBrowserRefreshTime.get(accountId);
        return lastTime != null
                && (System.currentTimeMillis() - lastTime) < BROWSER_REFRESH_COOLDOWN_MS;
    }

    @Override
    public boolean refreshCookieWithBrowser(Long accountId) {
        if (isBrowserRefreshInCooldown(accountId)) {
            long remainingMinutes = (BROWSER_REFRESH_COOLDOWN_MS
                    - (System.currentTimeMillis() - lastBrowserRefreshTime.get(accountId))) / 60000;
            log.warn("【账号{}】浏览器兜底刷新冷却中，剩余{}分钟，跳过本次", accountId, remainingMinutes);
            return false;
        }

        XianyuCookie cookie = cookieMapper.selectOne(
                new LambdaQueryWrapper<XianyuCookie>()
                        .eq(XianyuCookie::getXianyuAccountId, accountId)
                        .orderByDesc(XianyuCookie::getCreatedTime)
                        .last("LIMIT 1")
        );
        if (cookie == null || cookie.getCookieText() == null || cookie.getCookieText().isBlank()) {
            log.warn("【账号{}】浏览器兜底刷新失败，未找到可用Cookie", accountId);
            markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新失败：未找到可用Cookie");
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, "未找到可用Cookie", null);
            return false;
        }

        Map<String, String> existingCookies = XianyuSignUtils.parseCookies(cookie.getCookieText());
        if (existingCookies.isEmpty()) {
            log.warn("【账号{}】浏览器兜底刷新失败，Cookie内容为空", accountId);
            markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新失败：Cookie内容为空");
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, "Cookie内容为空", null);
            return false;
        }

        operationLogService.log(accountId,
                OperationConstants.Type.REFRESH,
                OperationConstants.Module.COOKIE,
                "开始浏览器兜底刷新Cookie",
                OperationConstants.Status.SUCCESS,
                OperationConstants.TargetType.COOKIE,
                String.valueOf(accountId),
                null, null, null, null);

        lastBrowserRefreshTime.put(accountId, System.currentTimeMillis());

        try (BrowserContext context = playwrightManager.createContext()) {
            List<Cookie> browserCookies = buildBrowserCookies(existingCookies);
            context.addCookies(browserCookies);

            Page page = context.newPage();
            log.info("【账号{}】浏览器兜底刷新Cookie，开始访问 {}", accountId, GOOFISH_IM_URL);
            page.navigate(GOOFISH_IM_URL,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            completeRememberedLogin(page, accountId);
            log.info("【账号{}】浏览器会话刷新，访问强鉴权页面 {}", accountId, GOOFISH_AUTH_PROBE_URL);
            page.navigate(GOOFISH_AUTH_PROBE_URL,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(15000));

            List<Cookie> refreshedCookies = context.cookies(List.of(
                    GOOFISH_IM_URL,
                    "https://passport.goofish.com",
                    XianyuProtocolProfile.H5_ORIGIN,
                    "https://www.taobao.com"
            ));
            String refreshedCookieText = buildCookieText(refreshedCookies);
            if (refreshedCookieText.isBlank()) {
                log.warn("【账号{}】浏览器兜底刷新未获取到新的Cookie", accountId);
                markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新失败：浏览器未返回Cookie");
                operationLogService.log(accountId,
                        OperationConstants.Type.REFRESH,
                        OperationConstants.Module.COOKIE,
                        "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, "浏览器未返回Cookie", null);
                return false;
            }

            Map<String, String> refreshedCookieMap = XianyuSignUtils.parseCookies(refreshedCookieText);
            String newMh5Tk = refreshedCookieMap.get("_m_h5_tk");
            if (!hasCompleteSession(refreshedCookieMap)) {
                log.warn("【账号{}】浏览器刷新未恢复完整Session，缺少关键Cookie，跳过持久化", accountId);
                markAccountAsCookieRefreshAbnormal(accountId, "浏览器刷新后Session仍不完整，请重新扫码登录");
                return false;
            }

            cookie.setCookieText(refreshedCookieText);
            cookie.setCookieStatus(1);
            if (newMh5Tk != null && !newMh5Tk.isBlank()) {
                cookie.setMH5Tk(newMh5Tk);
            }
            cookieMapper.updateById(cookie);

            log.info("【账号{}】浏览器兜底刷新Cookie成功，Cookie长度: {}", accountId, refreshedCookieText.length());
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie成功",
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, null, null);
            return true;
        } catch (Exception e) {
            log.error("【账号{}】浏览器兜底刷新Cookie失败", accountId, e);
            markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新异常: " + e.getMessage());
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, e.getMessage(), null);
            return false;
        }
    }

    private boolean hasCompleteSession(Map<String, String> cookies) {
        return cookies != null
                && hasCookieValue(cookies, "unb")
                && hasCookieValue(cookies, "_m_h5_tk")
                && hasCookieValue(cookies, "cookie2");
    }

    private void completeRememberedLogin(Page page, Long accountId) {
        try {
            com.microsoft.playwright.Locator loginBox = page.locator("#alibaba-login-box");
            if (loginBox.count() == 0 || !loginBox.isVisible()) {
                return;
            }
            com.microsoft.playwright.FrameLocator frame = loginBox.contentFrame();
            com.microsoft.playwright.Locator quickEnter = frame.getByText(
                    "快速进入", new com.microsoft.playwright.FrameLocator.GetByTextOptions().setExact(true));
            if (quickEnter.count() > 0 && quickEnter.first().isVisible()) {
                quickEnter.first().click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000));
                loginBox.waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                        .setTimeout(10000));
                log.info("【账号{}】已点击Passport快速进入，继续恢复完整Session", accountId);
            } else {
                log.warn("【账号{}】登录框无可用快速进入按钮，需要重新扫码登录", accountId);
            }
        } catch (Exception e) {
            log.warn("【账号{}】快速进入免密恢复失败，需要重新扫码登录: errorType={}",
                    accountId, e.getClass().getSimpleName());
        }
    }

    private boolean hasCookieValue(Map<String, String> cookies, String name) {
        String value = cookies.get(name);
        return value != null && !value.isBlank();
    }

    private void markAccountAsCookieRefreshAbnormal(Long accountId, String reason) {
        try {
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                operationLogService.log(accountId,
                        OperationConstants.Type.UPDATE,
                        OperationConstants.Module.ACCOUNT,
                        "Cookie刷新失败后更新账号状态失败",
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.ACCOUNT,
                        String.valueOf(accountId),
                        null, null, "账号不存在，原因: " + reason, null);
                return;
            }
            if (Objects.equals(account.getStatus(), -2)) {
                operationLogService.log(accountId,
                        OperationConstants.Type.UPDATE,
                        OperationConstants.Module.ACCOUNT,
                        "Cookie刷新失败，账号已处于异常待处理状态",
                        OperationConstants.Status.PARTIAL,
                        OperationConstants.TargetType.ACCOUNT,
                        String.valueOf(accountId),
                        null, null, reason, null);
                return;
            }

            account.setStatus(-2);
            accountMapper.updateById(account);
            log.warn("【账号{}】浏览器兜底刷新失败后，账号状态已更新为-2（异常待处理）", accountId);
            operationLogService.log(accountId,
                    OperationConstants.Type.UPDATE,
                    OperationConstants.Module.ACCOUNT,
                    "Cookie刷新失败，账号状态已标记为异常待处理(-2)",
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.ACCOUNT,
                    String.valueOf(accountId),
                    null, null, reason, null);
        } catch (Exception e) {
            log.error("【账号{}】Cookie刷新失败后更新账号状态异常", accountId, e);
            operationLogService.log(accountId,
                    OperationConstants.Type.UPDATE,
                    OperationConstants.Module.ACCOUNT,
                    "Cookie刷新失败后更新账号状态异常",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.ACCOUNT,
                    String.valueOf(accountId),
                    null, null, e.getMessage(), null);
        }
    }

    private void updateAccountStatusToNormal(Long accountId, String reason) {
        try {
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account != null && Objects.equals(account.getStatus(), -2)) {
                account.setStatus(1);
                accountMapper.updateById(account);
                log.info("【账号{}】Cookie刷新成功后，账号状态已恢复为1（正常）", accountId);
                operationLogService.log(accountId,
                        OperationConstants.Type.UPDATE,
                        OperationConstants.Module.ACCOUNT,
                        "Cookie刷新成功，账号状态已恢复正常",
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.ACCOUNT,
                        String.valueOf(accountId),
                        null, null, reason, null);
            }
        } catch (Exception e) {
            log.error("【账号{}】Cookie刷新成功后恢复账号状态失败", accountId, e);
            operationLogService.log(accountId,
                    OperationConstants.Type.UPDATE,
                    OperationConstants.Module.ACCOUNT,
                    "Cookie刷新成功后恢复账号状态失败",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.ACCOUNT,
                    String.valueOf(accountId),
                    null, null, e.getMessage(), null);
        }
    }

    private List<Cookie> buildBrowserCookies(Map<String, String> cookieMap) {
        List<Cookie> browserCookies = new ArrayList<>();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            browserCookies.add(new Cookie(name, value).setDomain(GOOFISH_COOKIE_DOMAIN).setPath("/"));
            browserCookies.add(new Cookie(name, value).setDomain(TAOBAO_COOKIE_DOMAIN).setPath("/"));
        }
        return browserCookies;
    }

    private String buildCookieText(List<Cookie> cookies) {
        Map<String, String> cookieMap = new LinkedHashMap<>();
        for (Cookie cookie : cookies) {
            if (cookie.name == null || cookie.name.isBlank() || cookie.value == null || cookie.value.isBlank()) {
                continue;
            }
            cookieMap.put(cookie.name, cookie.value);
        }
        return clearDuplicateCookies(XianyuSignUtils.formatCookies(cookieMap));
    }

    @Override
    public String clearDuplicateCookies(String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) {
            return cookieStr;
        }

        Map<String, String> cookies = new LinkedHashMap<>();
        String[] parts = cookieStr.split(";\\s*");

        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            int idx = part.indexOf('=');
            if (idx > 0) {
                String key = part.substring(0, idx);
                String value = part.substring(idx + 1);
                cookies.putIfAbsent(key, value);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return sb.toString();
    }



    @Override
    public void removeAccountState(Long accountId) {
        refreshLocks.remove(accountId);
        lastBrowserRefreshTime.remove(accountId);
    }
}