package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;

import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import com.feijimiao.xianyuassistant.security.CredentialCipher;
import com.feijimiao.xianyuassistant.utils.XianyuAuthFailureGuard;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket Token服务实现
 * 参考Python XianyuAutoAgent的get_token/hasLogin方法
 *
 * 核心逻辑（与Python完全对齐）：
 * 1. 使用OkHttp发送token请求（而非RestTemplate），确保能正确获取Set-Cookie响应头
 * 2. 模拟Python requests.Session的有状态Cookie管理：
 *    - 每次请求前，从数据库读取Cookie构建请求头
 *    - 每次请求后，从响应Set-Cookie中更新Cookie到数据库
 *    - 重试时使用更新后的Cookie（新_m_h5_tk）
 * 3. hasLogin刷新后，必须从数据库读取新Cookie，确保签名使用新_m_h5_tk
 *
 * 与Python的关键对应关系：
 * - Python self.session.cookies → Java 从数据库读取/更新Cookie
 * - Python self.session.post → Java OkHttp发送请求+手动管理Cookie
 * - Python self.clear_duplicate_cookies → Java mergeCookies + clearDuplicateCookies
 */
@Slf4j
@Service
public class WebSocketTokenServiceImpl implements WebSocketTokenService {

    @Autowired
    private XianyuCookieMapper xianyuCookieMapper;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper xianyuAccountMapper;

    @Autowired
    private CookieRefreshService cookieRefreshService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private EmailNotifyService emailNotifyService;

    @Autowired
    private XianyuAuthFailureGuard authFailureGuard = new XianyuAuthFailureGuard();

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.monitor.AlertService alertService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Token API地址
     */
    private static final String TOKEN_API_URL = XianyuProtocolProfile.H5_BASE_URL
            + XianyuProtocolProfile.TOKEN_API + "/" + XianyuProtocolProfile.H5_API_VERSION + "/";

    /**
     * Token 有效期（20小时，参考 Python 的 TOKEN_REFRESH_INTERVAL）
     */
    private static final long TOKEN_VALID_DURATION = 20 * 60 * 60 * 1000; // 20小时

    /**
     * 记录正在等待验证的账号和验证URL
     * Key: accountId, Value: captchaUrl
     */
    private final Map<Long, String> pendingCaptchaAccounts = new ConcurrentHashMap<>();

    /**
     * 记录验证URL的创建时间，用于超时清理
     * Key: accountId, Value: timestamp
     */
    private final Map<Long, Long> captchaTimestamps = new ConcurrentHashMap<>();

    // 同一次滑块事件只告警一次；手动清除或成功取回Token后重置。
    private final Set<Long> captchaAlertedAccounts = ConcurrentHashMap.newKeySet();

    // 邮件发送异步执行；同一账号同时只允许一个告警任务在途。
    private final Set<Long> captchaAlertInFlightAccounts = ConcurrentHashMap.newKeySet();

    /**
     * 验证URL有效期（5分钟）
     */
    private static final long CAPTCHA_TIMEOUT = 5 * 60 * 1000;

    /**
     * Token获取失败重试最大次数（参考Python: retry_count >= 2）
     */
    private static final int MAX_TOKEN_RETRY_COUNT = 2;

    /**
     * Cookie过期时hasLogin重试最大次数
     */
    private static final int MAX_COOKIE_RETRY_COUNT = 2;

    /**
     * 重试间隔基础值（毫秒）
     */
    private static final long RETRY_INTERVAL_BASE = 500;

    /**
     * 重试间隔随机范围（毫秒）
     */
    private static final long RETRY_INTERVAL_RANDOM = 1000;

    /**
     * 每个账号的Token获取锁，防止并发获取
     */
    private final Map<Long, Object> tokenLocks = new ConcurrentHashMap<>();

    /**
     * 共享的OkHttpClient（用于发送token API请求）
     */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * 获取账号级别的锁对象
     */
    private Object getTokenLock(Long accountId) {
        return tokenLocks.computeIfAbsent(accountId, k -> new Object());
    }

    @Override
    public String getAccessToken(Long accountId) {
        synchronized (getTokenLock(accountId)) {
            return getAccessTokenWithRetry(accountId, 0, false, true, false);
        }
    }

    /**
     * 从数据库获取最新的Cookie字符串
     */
    private String getLatestCookieFromDb(Long accountId) {
        try {
            XianyuCookie cookie = xianyuCookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );
            if (cookie != null && cookie.getCookieText() != null) {
                return CredentialCipher.decrypt(cookie.getCookieText());
            }
        } catch (Exception e) {
            log.error("【账号{}】从数据库获取最新Cookie失败", accountId, e);
        }
        return null;
    }

    /**
     * 获取AccessToken（带重试机制）
     * 参考Python XianyuApis.get_token方法
     *
     * 核心改进（与Python对齐）：
     * 1. 使用OkHttp发送请求，确保能正确获取Set-Cookie响应头
     * 2. 每次重试都从数据库重新读取最新Cookie（可能已被Set-Cookie更新）
     * 3. API失败后从响应Set-Cookie更新数据库Cookie，再重试（模拟Python session行为）
     *
     * @param accountId 账号ID
     * @param retryCount 当前重试次数
     * @return accessToken
     */
    private String getAccessTokenWithRetry(
            Long accountId, int retryCount, boolean forceRefresh, boolean allowRetries) {
        return getAccessTokenWithRetry(accountId, retryCount, forceRefresh, allowRetries, false);
    }

    private String getAccessTokenWithRetry(
            Long accountId,
            int retryCount,
            boolean forceRefresh,
            boolean allowRetries,
            boolean bypassCaptchaHold) {
        return getAccessTokenWithRetry(
                accountId, retryCount, forceRefresh, allowRetries, bypassCaptchaHold, 0);
    }

    private String getAccessTokenWithRetry(
            Long accountId,
            int retryCount,
            boolean forceRefresh,
            boolean allowRetries,
            boolean bypassCaptchaHold,
            int hasLoginRefreshCount) {
        try {
            if (!bypassCaptchaHold && authFailureGuard.isCoolingDown(accountId)) {
                long remainingSeconds = Math.max(1L,
                        (authFailureGuard.remainingCooldownMillis(accountId) + 999L) / 1000L);
                log.warn("【账号{}】认证失败保护冷却中，跳过WebSocket Token请求，剩余约{}秒",
                        accountId, remainingSeconds);
                return null;
            }

            // 0. 检查是否正在等待验证
            if (!bypassCaptchaHold && pendingCaptchaAccounts.containsKey(accountId)) {
                Long timestamp = captchaTimestamps.get(accountId);
                if (timestamp != null && System.currentTimeMillis() - timestamp < CAPTCHA_TIMEOUT) {
                    String captchaUrl = pendingCaptchaAccounts.get(accountId);
                    log.debug("【账号{}】正在等待滑块验证，跳过重复请求", accountId);
                    throw new CaptchaRequiredException(captchaUrl);
                } else {
                    log.info("【账号{}】验证超时，清除等待状态", accountId);
                    pendingCaptchaAccounts.remove(accountId);
                    captchaTimestamps.remove(accountId);
                }
            }

            // 1. 【关键】每次都从数据库重新读取最新Cookie
            String cookiesStr = getLatestCookieFromDb(accountId);
            if (cookiesStr == null || cookiesStr.isEmpty()) {
                log.error("【账号{}】获取Cookie失败，无法获取Token", accountId);
                return null;
            }

            // 2. 先从数据库检查是否有有效的 Token
            // 同一账号可能存在多条Cookie记录（历史遗留），必须取最新一条，否则 selectOne 命中多条会抛异常
            XianyuCookie cookieEntity = xianyuCookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );
            String persistedMh5tk = cookieEntity != null ? CredentialCipher.decrypt(cookieEntity.getMH5Tk()) : null;

            if (!forceRefresh && cookieEntity != null && cookieEntity.getWebsocketToken() != null
                    && cookieEntity.getTokenExpireTime() != null) {
                long now = System.currentTimeMillis();
                if (cookieEntity.getTokenExpireTime() > now) {
                    long remainingHours = (cookieEntity.getTokenExpireTime() - now) / (60 * 60 * 1000);
                    log.info("【账号{}】使用数据库中的accessToken（剩余有效期: {}小时）",
                            accountId, remainingHours);
                    authFailureGuard.recordSuccess(accountId);
                    pendingCaptchaAccounts.remove(accountId);
                    captchaTimestamps.remove(accountId);
                    captchaAlertedAccounts.remove(accountId);
                    updateAccountStatusToNormal(accountId);
                    return cookieEntity.getWebsocketToken();
                } else {
                    log.info("【账号{}】数据库中的Token已过期，需要重新获取", accountId);
                }
            }

            log.info("【账号{}】开始获取新的accessToken... (重试次数: {})", accountId, retryCount);
            if (bypassCaptchaHold) {
                log.info("【账号{}】用户确认已完成滑块，跳过本地等待保持并立即探测平台状态", accountId);
            }

            // 3. 生成时间戳
            String timestamp = String.valueOf(System.currentTimeMillis());

            // 4. 使用数据库中最新的Cookie来解析_m_h5_tk
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookiesStr);
            String mh5tk = cookies.get("_m_h5_tk");
            if ((mh5tk == null || mh5tk.isBlank())
                    && XianyuSignUtils.isValidMh5tk(persistedMh5tk)) {
                cookies.put("_m_h5_tk", persistedMh5tk);
                mh5tk = persistedMh5tk;
                cookiesStr = XianyuSignUtils.formatCookies(cookies);
                log.warn("【账号{}】Cookie解析缺少_m_h5_tk，使用数据库独立凭据字段补全Token请求", accountId);
            }
            if (!cookies.containsKey("unb")) {
                String persistedUnb = accountService.getXianyuUserId(accountId);
                if (persistedUnb != null && !persistedUnb.isBlank()) {
                    cookies.put("unb", persistedUnb);
                    cookiesStr = XianyuSignUtils.formatCookies(cookies);
                    log.warn("【账号{}】Cookie解析缺少unb，使用已验证账号身份补全Token请求", accountId);
                }
            }
            String token = "";
            if (mh5tk != null && mh5tk.contains("_")) {
                token = mh5tk.split("_")[0];
            }
            log.info("【账号{}】签名所需_m_h5_tk状态: {}", accountId,
                    token.isEmpty() ? "缺失" : "可用");

            // 5. 构建data参数
            String deviceId = getDeviceId(accountId, cookies);
            String dataVal = String.format("{\"appKey\":\"%s\",\"deviceId\":\"%s\"}",
                    XianyuProtocolProfile.WEBSOCKET_APP_KEY, deviceId);

            // 6. 生成签名
            String sign = XianyuSignUtils.generateSign(timestamp, token, dataVal);

            // 7. 构建URL参数
            StringBuilder urlBuilder = new StringBuilder(TOKEN_API_URL);
            urlBuilder.append("?");
            appendUrlParam(urlBuilder, "jsv", XianyuProtocolProfile.H5_JSV);
            appendUrlParam(urlBuilder, "appKey", XianyuProtocolProfile.H5_APP_KEY);
            appendUrlParam(urlBuilder, "t", timestamp);
            appendUrlParam(urlBuilder, "sign", sign);
            appendUrlParam(urlBuilder, "v", XianyuProtocolProfile.H5_API_VERSION);
            appendUrlParam(urlBuilder, "type", "originaljson");
            appendUrlParam(urlBuilder, "accountSite", "xianyu");
            appendUrlParam(urlBuilder, "dataType", "json");
            appendUrlParam(urlBuilder, "timeout", "20000");
            appendUrlParam(urlBuilder, "api", XianyuProtocolProfile.TOKEN_API);
            appendUrlParam(urlBuilder, "sessionOption", "AutoLoginOnly");
            appendUrlParam(urlBuilder, "spm_cnt", "a21ybx.im.0.0");
            appendUrlParam(urlBuilder, "spm_pre", "a21ybx.item.want.1.14ad3da6ALVq3n");
            appendUrlParam(urlBuilder, "log_id", "14ad3da6ALVq3n");
            String fullUrl = urlBuilder.toString();
            if (fullUrl.endsWith("&")) {
                fullUrl = fullUrl.substring(0, fullUrl.length() - 1);
            }

            // 8. 构建请求体（application/x-www-form-urlencoded）
            String formData = "data=" + URLEncoder.encode(dataVal, "UTF-8");

            // 9. 构建请求头
            Request.Builder requestBuilder = new Request.Builder()
                    .url(fullUrl)
                    .post(RequestBody.create(formData, MediaType.parse("application/x-www-form-urlencoded")))
                    .header("Host", XianyuProtocolProfile.H5_HOST)
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .header("user-agent", XianyuProtocolProfile.WEB_USER_AGENT)
                    .header("accept", "application/json")
                    .header("sec-ch-ua", XianyuProtocolProfile.WEB_SEC_CH_UA)
                    .header("sec-ch-ua-mobile", "?0")
                    .header("origin", XianyuProtocolProfile.WEB_ORIGIN)
                    .header("sec-fetch-site", "same-site")
                    .header("sec-fetch-mode", "cors")
                    .header("sec-fetch-dest", "empty")
                    .header("referer", XianyuProtocolProfile.WEB_REFERER)
                    .header("accept-language", XianyuProtocolProfile.WEB_ACCEPT_LANGUAGE)
                    .header("priority", "u=1, i")
                    .header("Cookie", cookiesStr);

            log.info("【账号{}】============", accountId);
            log.info("【账号{}】1、Token请求体已生成，data长度: {}", accountId, dataVal.length());
            log.info("【账号{}】2、发送Token POST请求", accountId);

            // 10. 发送请求（OkHttp能正确返回Set-Cookie头）
            try (Response httpResponse = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!httpResponse.isSuccessful()) {
                    log.error("【账号{}】获取accessToken失败：HTTP {}", accountId, httpResponse.code());
                    return handleTokenFailure(
                            accountId, retryCount, null, "HTTP " + httpResponse.code(),
                            forceRefresh, allowRetries, bypassCaptchaHold, hasLoginRefreshCount);
                }

                String responseBody = httpResponse.body() != null ? httpResponse.body().string() : "";

                // 【关键改进】处理响应中的Set-Cookie（参考Python: session自动处理 + clear_duplicate_cookies）
                // OkHttp能正确返回Set-Cookie头，这是与RestTemplate的关键区别
                Headers responseHeaders = httpResponse.headers();
                List<String> setCookieHeaders = responseHeaders.values("Set-Cookie");

                if (!setCookieHeaders.isEmpty()) {
                    log.info("【账号{}】检测到响应中的Set-Cookie，数量: {}", accountId, setCookieHeaders.size());
                    String updatedCookieStr = updateCookiesFromResponse(accountId, cookiesStr, setCookieHeaders);
                    if (updatedCookieStr != null && !updatedCookieStr.equals(cookiesStr)) {
                        log.info("【账号{}】Cookie已从响应Set-Cookie中更新，_m_h5_tk可能已更新", accountId);
                    } else {
                        log.info("【账号{}】Set-Cookie未改变Cookie内容", accountId);
                    }
                } else {
                    log.info("【账号{}】响应中无Set-Cookie", accountId);
                }

                log.info("【账号{}】3、Token响应已接收，HTTP状态: {}，响应长度: {}",
                        accountId, httpResponse.code(), responseBody.length());
                log.info("【账号{}】============", accountId);

                if (responseBody == null || responseBody.isEmpty()) {
                    log.error("【账号{}】获取accessToken失败：响应为空", accountId);
                    return handleTokenFailure(
                            accountId, retryCount, null, "响应为空",
                            forceRefresh, allowRetries, bypassCaptchaHold, hasLoginRefreshCount);
                }

                // 11. 解析响应
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

                // 检查ret字段
                Object retObj = responseMap.get("ret");
                if (retObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> retList = (java.util.List<String>) retObj;
                    log.info("【账号{}】Token响应状态项数量: {}", accountId, retList.size());

                    boolean success = retList.stream().anyMatch(ret -> ret.contains("SUCCESS::调用成功"));

                    if (success) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dataMap = (Map<String, Object>) responseMap.get("data");
                        if (dataMap != null && dataMap.containsKey("accessToken")) {
                            String accessToken = (String) dataMap.get("accessToken");

                            // 保存 token 到数据库
                            saveTokenToDatabase(accountId, accessToken, true);

                            pendingCaptchaAccounts.remove(accountId);
                            captchaTimestamps.remove(accountId);
                            captchaAlertedAccounts.remove(accountId);
                            captchaAlertInFlightAccounts.remove(accountId);
                            authFailureGuard.recordSuccess(accountId);

                            // 更新账号状态为正常（1）
                            updateAccountStatusToNormal(accountId);

                            log.info("【账号{}】accessToken获取成功并已保存到数据库", accountId);
                            log.debug("【账号{}】accessToken长度: {}", accountId, accessToken.length());

                            operationLogService.log(accountId,
                                com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                                com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                                "WebSocket Token获取成功",
                                com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                                com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                                String.valueOf(accountId),
                                null, null, null, null);

                            return accessToken;
                        }
                    }

                    // 检查是否需要滑块验证
                    boolean needCaptcha = retList.stream().anyMatch(ret -> ret.contains("FAIL_SYS_USER_VALIDATE"));
                    log.info("【账号{}】是否需要滑块验证: {}", accountId, needCaptcha);

                    if (needCaptcha) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dataMap = (Map<String, Object>) responseMap.get("data");
                        log.info("【账号{}】滑块验证响应数据字段数量: {}", accountId,
                                dataMap != null ? dataMap.size() : 0);
                        handleCaptchaRequired(accountId, dataMap);
                    }

                    // RGV风控同样进入持久化人工验证事件，避免普通重连继续请求。
                    boolean needRiskControl = retList.stream()
                            .anyMatch(ret -> ret.contains("RGV587_ERROR"));
                    if (needRiskControl) {
                        log.error("【账号{}】触发风控，响应状态项数量: {}", accountId, retList.size());
                        handleCaptchaRequired(accountId, Map.of());
                    }

                    boolean serverBusy = retList.stream().anyMatch(ret -> ret.contains("被挤爆啦"));
                    if (serverBusy) {
                        log.warn("【账号{}】Token接口当前繁忙，本轮不重试，交由上层退避", accountId);
                        return null;
                    }
                }

                log.error("【账号{}】获取accessToken失败，响应长度: {}", accountId, responseBody.length());

                // Token获取失败，进入失败处理流程
                return handleTokenFailure(
                        accountId, retryCount, responseBody, "Token API调用失败",
                        forceRefresh, allowRetries, bypassCaptchaHold, hasLoginRefreshCount);
            }

        } catch (CaptchaRequiredException e) {
            throw e;
        } catch (com.feijimiao.xianyuassistant.exception.CookieExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("【账号{}】获取accessToken异常", accountId, e);
            return null;
        }
    }

    private void handleCaptchaRequired(Long accountId, Map<String, Object> dataMap) {
        Object captchaUrlValue = dataMap != null ? dataMap.get("url") : null;
        String captchaUrl = captchaUrlValue instanceof String ? (String) captchaUrlValue : "";
        pendingCaptchaAccounts.put(accountId, captchaUrl);
        captchaTimestamps.put(accountId, System.currentTimeMillis());
        updateAccountStatusToCaptchaRequired(accountId);

        if (captchaUrl.isBlank()) {
            log.warn("【账号{}】检测到滑块验证，但服务端未返回验证URL", accountId);
        } else {
            log.warn("【账号{}】检测到滑块验证，验证地址已保存但不会写入日志", accountId);
        }
        log.warn("【账号{}】需要人工完成滑块验证，请访问: http://localhost:8080/websocket-manual-captcha.html", accountId);
        log.warn("【账号{}】账号状态已更新为-2（需要验证）", accountId);

        authFailureGuard.recordFailure(accountId, "FAIL_SYS_USER_VALIDATE");
        // 风控告警（去重/抑制由 AlertService 持久化处理）
        if (alertService != null) {
            try {
                alertService.checkAccountRisk(accountId, "FAIL_SYS_USER_VALIDATE");
            } catch (Exception e) {
                log.warn("【账号{}】风控告警持久化失败: errorType={}", accountId, e.getClass().getSimpleName());
            }
        }
        throw new CaptchaRequiredException(captchaUrl);
    }

    /**
     * URL参数追加辅助方法
     */
    private void appendUrlParam(StringBuilder sb, String key, String value) {
        try {
            sb.append(key).append("=").append(URLEncoder.encode(value, "UTF-8")).append("&");
        } catch (Exception e) {
            log.error("URL编码失败: key={}", key, e);
        }
    }

    /**
     * 获取设备ID
     */
    private String getDeviceId(Long accountId, Map<String, String> cookies) {
        com.feijimiao.xianyuassistant.entity.XianyuAccount account = xianyuAccountMapper.selectById(accountId);
        if (account != null && account.getDeviceId() != null) {
            return account.getDeviceId();
        }
        String unb = cookies.get("unb");
        if (unb != null) {
            return accountService.getOrGenerateDeviceId(accountId, unb);
        }
        return "device_" + accountId;
    }

    /**
     * 处理Token获取失败的情况
     * 参考Python XianyuApis.get_token的失败处理逻辑：
     *
     * Python逻辑：
     * 1. 非SUCCESS时，先检查响应Set-Cookie并更新cookies（clear_duplicate_cookies）
     * 2. retry_count < 2时，直接重试（此时session已有新cookie）
     * 3. retry_count >= 2时，调用hasLogin刷新Cookie，成功后重置retry_count重新获取token
     * 4. 检测风控（RGV587_ERROR或"被挤爆啦"），提示用户手动处理
     */
    private String handleTokenFailure(
            Long accountId,
            int retryCount,
            String response,
            String reason,
            boolean forceRefresh,
            boolean allowRetries) {
        return handleTokenFailure(
                accountId, retryCount, response, reason, forceRefresh, allowRetries, false);
    }

    private String handleTokenFailure(
            Long accountId,
            int retryCount,
            String response,
            String reason,
            boolean forceRefresh,
            boolean allowRetries,
            boolean bypassCaptchaHold) {
        return handleTokenFailure(
                accountId, retryCount, response, reason, forceRefresh, allowRetries,
                bypassCaptchaHold, 0);
    }

    private String handleTokenFailure(
            Long accountId,
            int retryCount,
            String response,
            String reason,
            boolean forceRefresh,
            boolean allowRetries,
            boolean bypassCaptchaHold,
            int hasLoginRefreshCount) {

        // 检测风控（参考Python实现）
        boolean isRiskControl = response != null && (
            response.contains("RGV587_ERROR") ||
            response.contains("FAIL_SYS_RGV587_ERROR") ||
            response.contains("FAIL_SYS_USER_VALIDATE"));

        if (isRiskControl) {
            log.error("【账号{}】触发风控，响应长度: {}", accountId,
                    response != null ? response.length() : 0);
            handleCaptchaRequired(accountId, Map.of());
        }

        if (response != null && response.contains("被挤爆啦")) {
            log.warn("【账号{}】Token接口当前繁忙，本轮不重试，交由上层退避", accountId);
            return null;
        }

        boolean isSessionExpired = response != null && (
            response.contains("FAIL_SYS_SESSION_EXPIRED") ||
            response.contains("FAIL_SYS_TOKEN_EXOIRED") ||
            response.contains("FAIL_SYS_TOKEN_EXPIRED") ||
            response.contains("令牌过期"));

        if (isSessionExpired) {
            log.warn("【账号{}】检测到Session/令牌过期，尝试通过hasLogin自动续期...", accountId);
            // 不立即标记为过期，先尝试自动续期
            // updateCookieStatus(accountId, 2);

            operationLogService.log(accountId,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                "Session过期，尝试自动续期",
                com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                String.valueOf(accountId),
                null, null, "Session过期", null);

            // 直接尝试通过hasLogin刷新，而不是抛出异常
            return allowRetries
                    ? refreshTokenAfterSessionExpired(
                            accountId, hasLoginRefreshCount, forceRefresh, bypassCaptchaHold)
                    : null;
        }

        if (!allowRetries) {
            log.warn("【账号{}】单次Token探测失败({})，等待下一个低频探测周期", accountId, reason);
            return null;
        }

        if (retryCount < MAX_TOKEN_RETRY_COUNT) {
            log.warn("【账号{}】Token获取失败({})，准备重试... (重试次数: {}/{})",
                    accountId, reason, retryCount + 1, MAX_TOKEN_RETRY_COUNT);

            try {
                long randomInterval = RETRY_INTERVAL_BASE + new java.util.Random().nextLong(RETRY_INTERVAL_RANDOM);
                Thread.sleep(randomInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return getAccessTokenWithRetry(
                    accountId, retryCount + 1, forceRefresh, true,
                    bypassCaptchaHold, hasLoginRefreshCount);
        }

        log.warn("【账号{}】Token获取重试已达上限，尝试通过hasLogin刷新Cookie...", accountId);
        return refreshTokenViaHasLogin(
                accountId, hasLoginRefreshCount, forceRefresh, true, bypassCaptchaHold);
    }

    /**
     * Session 级失效不能只调用 hasLogin：当 cookie2/sgcookie 失效时，
     * 轻量接口可能仍返回成功，但后续 Token 请求会继续得到 SESSION_EXPIRED。
     * 使用浏览器访问强鉴权页面补齐完整 Session，并限制整个恢复链最多两轮。
     */
    private String refreshTokenAfterSessionExpired(
            Long accountId, int refreshCount, boolean forceRefresh, boolean bypassCaptchaHold) {
        if (refreshCount >= MAX_COOKIE_RETRY_COUNT) {
            return refreshTokenViaHasLogin(
                    accountId, MAX_COOKIE_RETRY_COUNT, forceRefresh, true, bypassCaptchaHold);
        }

        log.info("【账号{}】Session级失效，使用浏览器强鉴权页面恢复Cookie（第{}次）",
                accountId, refreshCount + 1);
        try {
            if (cookieRefreshService.isBrowserRefreshInCooldown(accountId)) {
                // 冷却中说明上一次浏览器兜底刚刚成功，Cookie已是最新状态；
                // 跳过浏览器步骤直接重试Token，不能把冷却跳过当成恢复失败。
                log.info("【账号{}】浏览器兜底刷新冷却中（近期已成功刷新），直接重试Token", accountId);
            } else if (!cookieRefreshService.refreshCookieWithBrowser(accountId)) {
                log.warn("【账号{}】浏览器Session恢复失败，不再重复请求Token", accountId);
                return refreshTokenViaHasLogin(
                        accountId, MAX_COOKIE_RETRY_COUNT, forceRefresh, true, bypassCaptchaHold);
            }
            return getAccessTokenWithRetry(
                    accountId, 0, forceRefresh, true, bypassCaptchaHold, refreshCount + 1);
        } catch (CaptchaRequiredException e) {
            throw e;
        } catch (com.feijimiao.xianyuassistant.exception.CookieExpiredException e) {
            // 内层已确认Cookie彻底过期，直接上抛；
            // 吞掉后逐层重入致命路径会导致重复告警和认证失败计数翻倍。
            throw e;
        } catch (Exception e) {
            log.warn("【账号{}】浏览器Session恢复异常，不再重复请求Token: errorType={}",
                    accountId, e.getClass().getSimpleName());
            return refreshTokenViaHasLogin(
                    accountId, MAX_COOKIE_RETRY_COUNT, forceRefresh, true, bypassCaptchaHold);
        }
    }

    /**
     * 通过hasLogin刷新Cookie后重新获取Token
     * 参考Python: get_token中retry_count >= 2时的逻辑
     *
     * Python逻辑：
     * if retry_count >= 2:
     *     if self.hasLogin():  # hasLogin会自动更新session cookies
     *         return self.get_token(device_id, 0)  # 重置重试次数
     *     else:
     *         sys.exit(1)  # Cookie彻底失效
     */
    private String refreshTokenViaHasLogin(
            Long accountId, int hasLoginRetryCount, boolean forceRefresh, boolean allowRetries) {
        return refreshTokenViaHasLogin(accountId, hasLoginRetryCount, forceRefresh, allowRetries, false);
    }

    private String refreshTokenViaHasLogin(
            Long accountId,
            int hasLoginRetryCount,
            boolean forceRefresh,
            boolean allowRetries,
            boolean bypassCaptchaHold) {
        if (hasLoginRetryCount >= MAX_COOKIE_RETRY_COUNT) {
            // 误判保护：闲鱼Token接口偶发短暂返回SESSION_EXPIRED，此时hasLogin仍有效，
            // 同一Cookie在数十秒后即可自愈（生产日志实测18~41秒）。
            // 宣布Cookie彻底失效前，先做有间隔的最终探测：
            // 只有登录态确认消失、或探测全部失败时才进入过期告警流程。
            String recoveredToken = confirmTokenBeforeExpire(accountId, bypassCaptchaHold);
            if (recoveredToken != null) {
                log.info("【账号{}】Token最终确认阶段自愈恢复，取消Cookie过期判定", accountId);
                return recoveredToken;
            }
            log.error("【账号{}】hasLogin刷新重试次数已达上限，Cookie已彻底过期，无法自动续期", accountId);
            authFailureGuard.recordFailure(accountId, "FAIL_SYS_SESSION_EXPIRED");
            // 账号风控告警（去重/抑制由 AlertService 持久化处理）
            if (alertService != null) {
                try {
                    alertService.checkAccountRisk(accountId, "SESSION_EXPIRED");
                } catch (Exception e) {
                    log.warn("【账号{}】会话过期告警持久化失败: errorType={}", accountId, e.getClass().getSimpleName());
                }
            }
            // 确认无法自动续期后，才标记为过期并触发邮件通知
            updateCookieStatus(accountId, 2, true);

            operationLogService.log(accountId,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                "WebSocket Token获取失败：Cookie过期且自动刷新失败",
                com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                String.valueOf(accountId),
                null, null, "Cookie过期且自动刷新失败", null);

            throw new com.feijimiao.xianyuassistant.exception.CookieExpiredException(
                    "Cookie已过期且自动刷新失败，请手动更新Cookie后重试");
        }

        log.info("【账号{}】开始通过hasLogin刷新Cookie... (重试次数: {}/{})",
                accountId, hasLoginRetryCount, MAX_COOKIE_RETRY_COUNT);

        try {
            // 调用hasLogin刷新Cookie（参考Python的hasLogin方法）
            boolean refreshSuccess = cookieRefreshService.refreshCookie(accountId);

            if (refreshSuccess) {
                log.info("【账号{}】hasLogin成功，登录态有效，准备重新获取Token（重置重试计数）", accountId);

                try {
                    // 随机间隔500-1500ms，避免固定间隔被识别为机器人
                    long randomInterval = RETRY_INTERVAL_BASE + new java.util.Random().nextLong(RETRY_INTERVAL_RANDOM);
                    Thread.sleep(randomInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // hasLogin成功后从数据库读取最新Cookie
                String newCookieStr = getLatestCookieFromDb(accountId);
                if (newCookieStr != null && !newCookieStr.isEmpty()) {
                    Map<String, String> newCookies = XianyuSignUtils.parseCookies(newCookieStr);
                    String newMh5tk = newCookies.get("_m_h5_tk");
                    log.info("【账号{}】hasLogin后获取到最新Cookie，长度: {}，_m_h5_tk存在: {}",
                            accountId, newCookieStr.length(), newMh5tk != null);
                    // 重置retryCount为0，重新开始获取token流程
                    return getAccessTokenWithRetry(
                            accountId, 0, forceRefresh, allowRetries, bypassCaptchaHold,
                            hasLoginRetryCount + 1);
                } else {
                    log.error("【账号{}】hasLogin后获取刷新后的Cookie失败", accountId);
                }
            } else {
                log.warn("【账号{}】hasLogin失败", accountId);
            }
        } catch (CaptchaRequiredException e) {
            log.warn("【账号{}】hasLogin后重新获取Token时触发滑块验证，停止自动重试，等待人工处理", accountId);
            throw e;
        } catch (com.feijimiao.xianyuassistant.exception.CookieExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("【账号{}】hasLogin刷新过程发生异常", accountId, e);
        }

        // hasLogin失败，重试
        return refreshTokenViaHasLogin(
                accountId, hasLoginRetryCount + 1, forceRefresh, allowRetries, bypassCaptchaHold);
    }

    /**
     * 宣布Cookie彻底失效前的最终确认参数：
     * 每次探测前先等待一个间隔，登录态消失则立即放弃，避免把平台瞬时故障误报为Cookie过期。
     */
    private static final int EXPIRE_CONFIRM_PROBE_COUNT = 3;
    private static final long EXPIRE_CONFIRM_PROBE_INTERVAL_MS = 20_000L;

    /**
     * 过期告警前的最终确认：探测期间hasLogin仍有效则继续等待重试，
     * Token恢复即返回（不触发告警）。探测使用allowRetries=false的单次请求，不会递归进入失败处理。
     */
    private String confirmTokenBeforeExpire(Long accountId, boolean bypassCaptchaHold) {
        for (int i = 1; i <= EXPIRE_CONFIRM_PROBE_COUNT; i++) {
            try {
                Thread.sleep(EXPIRE_CONFIRM_PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!cookieRefreshService.checkLoginStatusQuietly(accountId)) {
                log.warn("【账号{}】最终确认阶段登录态检查失败，Cookie确认已失效", accountId);
                return null;
            }
            String token = getAccessTokenWithRetry(
                    accountId, 0, true, false, bypassCaptchaHold, MAX_COOKIE_RETRY_COUNT);
            if (token != null) {
                log.info("【账号{}】最终确认第{}次探测成功，Cookie并未过期（平台瞬时故障）", accountId, i);
                return token;
            }
            log.warn("【账号{}】最终确认第{}次Token探测仍失败（登录态有效），继续等待重试",
                    accountId, i);
        }
        return null;
    }

    /**
     * 从响应的Set-Cookie中更新Cookie
     * 参考Python: requests.Session自动处理Set-Cookie + clear_duplicate_cookies
     *
     * 关键：token API在返回FAIL_SYS_SESSION_EXPIRED时，响应的Set-Cookie中会包含新的_m_h5_tk
     * Python的requests.Session会自动保存这些Cookie，所以下次请求时签名是正确的
     * Java需要手动处理，并且必须确保Set-Cookie头能被正确读取（OkHttp可以）
     *
     * @param accountId 账号ID
     * @param currentCookieStr 当前Cookie字符串
     * @param setCookieHeaders 响应中的Set-Cookie列表
     * @return 更新后的Cookie字符串
     */
    private String updateCookiesFromResponse(Long accountId, String currentCookieStr, List<String> setCookieHeaders) {
        try {
            log.debug("【账号{}】准备合并Set-Cookie，数量: {}", accountId, setCookieHeaders.size());

            String newCookieStr = mergeCookies(currentCookieStr, setCookieHeaders);

            // 清理重复Cookie
            newCookieStr = cookieRefreshService.clearDuplicateCookies(newCookieStr);

            // 检查是否有新的_m_h5_tk
            Map<String, String> oldCookies = XianyuSignUtils.parseCookies(currentCookieStr);
            Map<String, String> newCookies = XianyuSignUtils.parseCookies(newCookieStr);

            String oldMh5tk = oldCookies.get("_m_h5_tk");
            String newMh5tk = newCookies.get("_m_h5_tk");

            boolean mh5tkUpdated = (newMh5tk != null && !newMh5tk.equals(oldMh5tk));
            if (mh5tkUpdated) {
                log.info("【账号{}】_m_h5_tk已从响应中更新", accountId);
            } else {
                log.info("【账号{}】_m_h5_tk未变化（可能Set-Cookie中没有新的_m_h5_tk）", accountId);
            }

            // 更新数据库中的Cookie（实体方式，走 TypeHandler 自动加密）
            if (!newCookieStr.equals(currentCookieStr)) {
                // 关键字段保护：合并后的 Cookie 若丢失 unb/_m_h5_tk，
                // 说明响应不完整，禁止覆盖数据库中完整的登录凭据。
                if (!XianyuSignUtils.hasCriticalCookieFields(newCookieStr)) {
                    log.warn("【账号{}】响应 Set-Cookie 合并后缺少关键字段(unb/_m_h5_tk)，放弃写回数据库", accountId);
                    return newCookieStr;
                }
                XianyuCookie targetCookie = xianyuCookieMapper.selectOne(
                        new LambdaQueryWrapper<XianyuCookie>()
                                .eq(XianyuCookie::getXianyuAccountId, accountId)
                                .orderByDesc(XianyuCookie::getCreatedTime)
                                .last("LIMIT 1")
                );
                if (targetCookie != null) {
                    targetCookie.setCookieText(newCookieStr);
                    targetCookie.setCookieStatus(1);
                    // 如果_m_h5_tk更新了，也更新mH5Tk字段（仅合法 token 格式才更新，防嵌套加密）
                    if (mh5tkUpdated && XianyuSignUtils.isValidMh5tk(newMh5tk)) {
                        targetCookie.setMH5Tk(newMh5tk);
                    } else {
                        // 本次不更新 _m_h5_tk：置空跳过该字段，防止把读取到的嵌套密文再次写回膨胀
                        targetCookie.setMH5Tk(null);
                    }
                    xianyuCookieMapper.updateById(targetCookie);
                }

                log.info("【账号{}】Cookie已从响应Set-Cookie更新到数据库", accountId);
            }

            return newCookieStr;
        } catch (Exception e) {
            log.error("【账号{}】处理响应Set-Cookie失败", accountId, e);
            return currentCookieStr;
        }
    }

    /**
     * 合并Cookie（新Cookie覆盖旧Cookie）
     * 模拟Python requests.Session自动处理Set-Cookie的行为
     */
    private String mergeCookies(String oldCookieStr, List<String> newCookies) {
        Map<String, String> cookies = new LinkedHashMap<>();

        // 解析旧Cookie
        if (oldCookieStr != null && !oldCookieStr.isEmpty()) {
            String[] parts = oldCookieStr.split(";\\s*");
            for (String part : parts) {
                int idx = part.indexOf('=');
                if (idx > 0) {
                    String key = part.substring(0, idx);
                    String value = part.substring(idx + 1);
                    cookies.put(key, value);
                }
            }
        }

        // 解析新Cookie（Set-Cookie格式: name=value; Path=/; Domain=.goofish.com; ...）
        for (String newCookie : newCookies) {
            // 只提取第一个name=value对（Set-Cookie头中后面的属性如Path、Domain等不是Cookie值）
            Pattern pattern = Pattern.compile("^\\s*([^=;\\s]+)=([^;]*)");
            Matcher matcher = pattern.matcher(newCookie);
            if (matcher.find()) {
                String key = matcher.group(1).trim();
                String value = matcher.group(2).trim();
                // 跳过删除Cookie（值为空）
                if (!value.isEmpty()) {
                    cookies.put(key, value);
                } else {
                    cookies.remove(key);
                }
            }
        }

        // 重新构建Cookie字符串
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
    public void saveToken(Long accountId, String token) {
        saveTokenToDatabase(accountId, token, false);
    }

    @Override
    public void clearToken(Long accountId) {
        try {
            log.info("【账号{}】清除数据库中的Token缓存", accountId);

            xianyuCookieMapper.update(null,
                    new LambdaUpdateWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .set(XianyuCookie::getTokenExpireTime, 0L)
            );

            log.info("【账号{}】Token缓存已清除", accountId);
        } catch (Exception e) {
            log.error("【账号{}】清除Token缓存失败", accountId, e);
        }
    }

    @Override
    public void clearCaptchaWait(Long accountId) {
        log.info("【账号{}】清除验证等待状态", accountId);
        pendingCaptchaAccounts.remove(accountId);
        captchaTimestamps.remove(accountId);
        captchaAlertedAccounts.remove(accountId);
        captchaAlertInFlightAccounts.remove(accountId);
        authFailureGuard.clear(accountId);
        updateAccountStatusToNormal(accountId);
        log.info("【账号{}】验证等待状态已清除", accountId);
    }

    @Override
    public void restoreCaptchaWait(Long accountId) {
        if (accountId == null) {
            return;
        }
        pendingCaptchaAccounts.put(accountId, "");
        captchaTimestamps.put(accountId, System.currentTimeMillis());
        updateAccountStatusToCaptchaRequired(accountId);
        log.warn("【账号{}】连接恢复失败，继续保留人工验证告警", accountId);
    }

    @Override
    public boolean isCaptchaPending(Long accountId) {
        if (accountId == null) {
            return false;
        }
        if (captchaTimestamps.containsKey(accountId)
                && pendingCaptchaAccounts.containsKey(accountId)) {
            return true;
        }
        try {
            XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            return account != null && Integer.valueOf(-2).equals(account.getStatus());
        } catch (Exception e) {
            log.warn("【账号{}】读取持久化滑块状态失败: errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String getPendingCaptchaUrl(Long accountId) {
        if (accountId == null) {
            return null;
        }
        String url = pendingCaptchaAccounts.get(accountId);
        Long timestamp = captchaTimestamps.get(accountId);
        if (url == null || url.isBlank() || timestamp == null
                || System.currentTimeMillis() - timestamp >= CAPTCHA_TIMEOUT) {
            // Challenge URLs are short-lived. Never hand an expired URL back to
            // Playwright; the caller will clear the hold and request a fresh one.
            pendingCaptchaAccounts.remove(accountId, url);
            if (timestamp != null) {
                captchaTimestamps.remove(accountId, timestamp);
            }
            return null;
        }
        return url;
    }

    /**
     * 刷新WebSocket token
     */
    @Override
    public String refreshToken(Long accountId) {
        synchronized (getTokenLock(accountId)) {
            try {
                log.info("【账号{}】开始刷新WebSocket token...", accountId);
                String newToken = getAccessTokenWithRetry(accountId, 0, true, true, false);

                if (newToken != null && !newToken.isEmpty()) {
                    log.info("【账号{}】✅ WebSocket token刷新成功", accountId);
                    return newToken;
                } else {
                    log.warn("【账号{}】⚠️ WebSocket token刷新失败", accountId);
                    return null;
                }

            } catch (CaptchaRequiredException e) {
                throw e;
            } catch (com.feijimiao.xianyuassistant.exception.CookieExpiredException e) {
                throw e;
            } catch (Exception e) {
                log.error("【账号{}】刷新WebSocket token异常", accountId, e);
                return null;
            }
        }
    }

    @Override
    public String probeAccessToken(Long accountId) {
        synchronized (getTokenLock(accountId)) {
            log.info("【账号{}】执行单次WebSocket Token恢复探测", accountId);
            return getAccessTokenWithRetry(accountId, 0, true, false, false);
        }
    }

    @Override
    public String probeAccessTokenAfterManualVerification(Long accountId) {
        synchronized (getTokenLock(accountId)) {
            log.info("【账号{}】用户确认完成滑块，立即执行WebSocket Token恢复探测", accountId);
            pendingCaptchaAccounts.remove(accountId);
            captchaTimestamps.remove(accountId);
            authFailureGuard.clear(accountId);
            // 完成平台验证后，旧会话可能仍返回 SessionExpired；允许受控的
            // hasLogin/Cookie 续期链拿到新会话，再重新请求 WebSocket Token。
            return getAccessTokenWithRetry(accountId, 0, true, true, true);
        }
    }

    /**
     * 更新账号状态为需要验证（-2）
     */
    private void updateAccountStatusToCaptchaRequired(Long accountId) {
        com.feijimiao.xianyuassistant.entity.XianyuAccount account = null;
        try {
            account = xianyuAccountMapper.selectById(accountId);
            if (account != null) {
                account.setStatus(-2);
                xianyuAccountMapper.updateById(account);
                log.info("【账号{}】账号状态已更新为-2（需要验证）", accountId);
            }
        } catch (Exception e) {
            log.error("【账号{}】更新账号状态失败", accountId, e);
        }

        if (emailNotifyService == null
                || captchaAlertedAccounts.contains(accountId)
                || !captchaAlertInFlightAccounts.add(accountId)) {
            return;
        }
        try {
            String accountNote = account != null ? account.getAccountNote() : null;
            java.util.concurrent.CompletableFuture<Boolean> delivery =
                    emailNotifyService.sendCaptchaRequiredEmail(
                    accountId, accountNote, "FAIL_SYS_USER_VALIDATE");
            if (delivery == null) {
                captchaAlertInFlightAccounts.remove(accountId);
                log.warn("【账号{}】滑块验证邮件任务未返回投递结果，后续检测将重试", accountId);
                return;
            }
            delivery.whenComplete((sent, error) -> {
                captchaAlertInFlightAccounts.remove(accountId);
                if (error == null && Boolean.TRUE.equals(sent)) {
                    captchaAlertedAccounts.add(accountId);
                } else {
                    log.warn("【账号{}】滑块验证邮件未确认投递，后续检测将重试", accountId);
                }
            });
        } catch (Exception e) {
            captchaAlertInFlightAccounts.remove(accountId);
            log.error("【账号{}】提交滑块验证邮件任务失败，后续检测将重试", accountId, e);
        }
    }

    /**
     * 更新账号状态为正常（1）
     */
    private void updateAccountStatusToNormal(Long accountId) {
        try {
            com.feijimiao.xianyuassistant.entity.XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            if (account != null && Integer.valueOf(-2).equals(account.getStatus())) {
                account.setStatus(1);
                xianyuAccountMapper.updateById(account);
                log.info("【账号{}】账号状态已恢复为1（正常）", accountId);
            }
        } catch (Exception e) {
            log.error("【账号{}】更新账号状态失败", accountId, e);
        }
    }

    /**
     * 更新Cookie状态
     * @param accountId 账号ID
     * @param status Cookie状态
     */
    private void updateCookieStatus(Long accountId, Integer status) {
        updateCookieStatus(accountId, status, false);
    }

    /**
     * 更新Cookie状态
     * @param accountId 账号ID
     * @param status Cookie状态
     * @param sendNotify 是否发送邮件通知（仅当确认无法自动续期时才为true）
     */
    private void updateCookieStatus(Long accountId, Integer status, boolean sendNotify) {
        try {
            XianyuCookie currentCookie = xianyuCookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );
            Integer oldStatus = currentCookie != null ? currentCookie.getCookieStatus() : null;

            xianyuCookieMapper.update(null,
                    new LambdaUpdateWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .set(XianyuCookie::getCookieStatus, status)
            );
            String statusText = status == 2 ? "过期" : status == 3 ? "失效" : "未知";
            log.info("【账号{}】Cookie状态已更新为{}({})", accountId, status, statusText);

            // 只有在明确指定发送通知时才发送邮件（即确认无法自动续期后）
            if (sendNotify && Objects.equals(status, 2) && !Objects.equals(oldStatus, 2)) {
                XianyuAccount account = xianyuAccountMapper.selectById(accountId);
                String accountNote = account != null ? account.getAccountNote() : null;
                log.info("【账号{}】Cookie已确认无法自动续期，触发Cookie过期通知流程", accountId);
                emailNotifyService.sendCookieExpireNotifyEmail(accountId, accountNote);
            } else if (Objects.equals(status, 2) && !Objects.equals(oldStatus, 2)) {
                log.info("【账号{}】Cookie被标记为过期，但系统将尝试自动续期，暂不发送邮件通知", accountId);
            }
        } catch (Exception e) {
            log.error("【账号{}】更新Cookie状态失败", accountId, e);
        }
    }

    /**
     * 保存 Token 到数据库
     */
    private void saveTokenToDatabase(Long accountId, String token, boolean confirmedFresh) {
        try {
            XianyuCookie targetCookie = null;
            if (!confirmedFresh) {
                XianyuCookie current = xianyuCookieMapper.selectOne(
                        new LambdaQueryWrapper<XianyuCookie>()
                                .eq(XianyuCookie::getXianyuAccountId, accountId)
                                .orderByDesc(XianyuCookie::getCreatedTime)
                                .last("LIMIT 1"));
                if (current != null
                        && Objects.equals(current.getWebsocketToken(), token)
                        && current.getTokenExpireTime() != null
                        && current.getTokenExpireTime() > System.currentTimeMillis()) {
                    log.debug("【账号{}】Token未变化，保留原始过期时间", accountId);
                    return;
                }
                targetCookie = current;
            }
            if (targetCookie == null) {
                targetCookie = xianyuCookieMapper.selectOne(
                        new LambdaQueryWrapper<XianyuCookie>()
                                .eq(XianyuCookie::getXianyuAccountId, accountId)
                                .orderByDesc(XianyuCookie::getCreatedTime)
                                .last("LIMIT 1"));
            }

            long expireTime = System.currentTimeMillis() + TOKEN_VALID_DURATION;

            if (targetCookie == null) {
                log.warn("【账号{}】Token保存失败，未找到对应的Cookie记录", accountId);
                return;
            }

            targetCookie.setWebsocketToken(token);
            targetCookie.setTokenExpireTime(expireTime);
            // 本方法只保存 Token，不更新 _m_h5_tk：置空后 updateById 会跳过该字段，
            // 避免把读取到的（可能是嵌套加密的）_m_h5_tk 再次加密写回造成膨胀。
            targetCookie.setMH5Tk(null);
            if (confirmedFresh) {
                targetCookie.setCookieStatus(1);
            }
            int updated = xianyuCookieMapper.updateById(targetCookie);

            if (updated > 0) {
                log.info("【账号{}】Token已保存到数据库，过期时间: {}", accountId,
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                .format(new java.util.Date(expireTime)));
            } else {
                log.warn("【账号{}】Token保存失败，未找到对应的Cookie记录", accountId);
            }
        } catch (Exception e) {
            log.error("【账号{}】保存Token到数据库失败", accountId, e);
        }
    }

    @Override
    public void removeAccountState(Long accountId) {
        pendingCaptchaAccounts.remove(accountId);
        captchaTimestamps.remove(accountId);
        captchaAlertedAccounts.remove(accountId);
        captchaAlertInFlightAccounts.remove(accountId);
        tokenLocks.remove(accountId);
    }
}