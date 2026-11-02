package com.feijimiao.xianyuassistant.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.service.RiskControlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 闲鱼API调用工具类（带自动刷新机制）
 * 参考Python Demo的被动刷新策略
 */
@Slf4j
@Component
public class XianyuApiCallUtils {
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.CookieRefreshService cookieRefreshService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.AccountService accountService;

    @Autowired(required = false)
    private RiskControlService riskControlService;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.monitor.AlertService alertService;

    /** P0-6：账号级滑动窗口限速队列（accountId -> 请求时间戳） */
    private final ConcurrentMap<Long, Deque<Long>> rateLimitWindows = new ConcurrentHashMap<>();

    /** P0-6：账号级并发信号量 */
    private final ConcurrentMap<Long, Semaphore> accountSemaphores = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_COUNT = 2;

    /**
     * 限流错误提示（参考Python: 被挤爆啦）
     */
    private static final String RATE_LIMIT_MARKER = "被挤爆啦";
    
    /**
     * 重试间隔（毫秒）
     */
    private static final long RETRY_INTERVAL = 500;

    /**
     * 单账号认证失败保护。字段提供默认实例，兼容不通过 Spring 容器创建的单元测试对象。
     */
    @Autowired
    private XianyuAuthFailureGuard authFailureGuard = new XianyuAuthFailureGuard();
    
    /**
     * 调用闲鱼API（带自动刷新机制）
     * 
     * @param accountId 账号ID
     * @param apiName API名称
     * @param dataMap 数据Map
     * @param cookiesStr Cookie字符串
     * @return API响应结果
     */
    public ApiCallResult callApiWithRetry(Long accountId, String apiName, 
                                          Map<String, Object> dataMap, String cookiesStr) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, null, null, 0);
    }

    public ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                          Map<String, Object> dataMap, String cookiesStr,
                                          Map<String, String> extraHeaders) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, extraHeaders, null, 0);
    }

    public ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                          Map<String, Object> dataMap, String cookiesStr,
                                          Map<String, String> extraHeaders,
                                          Map<String, String> extraQueryParams) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, extraHeaders, extraQueryParams, 0);
    }

    /**
     * 调用闲鱼API（带自动刷新机制，支持按 API 指定协议版本号）。
     *
     * @param accountId 账号ID
     * @param apiName API名称
     * @param dataMap 数据Map
     * @param cookiesStr Cookie字符串
     * @param apiVersion API协议版本号（如 "1.0"/"1.1"/"2.0"），null 时使用协议默认版本
     * @return API响应结果
     */
    public ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                          Map<String, Object> dataMap, String cookiesStr,
                                          String apiVersion) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, null, null, apiVersion, 0);
    }
    
    private ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                           Map<String, Object> dataMap, String cookiesStr,
                                           int retryCount) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, null, null, retryCount);
    }

    private ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                           Map<String, Object> dataMap, String cookiesStr,
                                           Map<String, String> extraHeaders,
                                           int retryCount) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, extraHeaders, null, null, retryCount);
    }

    private ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                           Map<String, Object> dataMap, String cookiesStr,
                                           Map<String, String> extraHeaders,
                                           Map<String, String> extraQueryParams,
                                           int retryCount) {
        return callApiWithRetry(accountId, apiName, dataMap, cookiesStr, extraHeaders, extraQueryParams, null, retryCount);
    }

    private ApiCallResult callApiWithRetry(Long accountId, String apiName,
                                           Map<String, Object> dataMap, String cookiesStr,
                                           Map<String, String> extraHeaders,
                                           Map<String, String> extraQueryParams,
                                           String apiVersion,
                                           int retryCount) {
        // P0-6：账号级自动化保护（手动暂停、认证/风控冷却、滑动窗口限速）
        try {
            if (authFailureGuard.isCoolingDown(accountId)) {
                long remainingSeconds = Math.max(1L,
                        (authFailureGuard.remainingCooldownMillis(accountId) + 999L) / 1000L);
                log.warn("【账号{}】认证失败保护冷却中，跳过API请求，剩余约{}秒", accountId, remainingSeconds);
                return new ApiCallResult(false, null, "账号认证冷却中，请稍后重试", false, false);
            }
            if (riskControlService != null && !riskControlService.isAutomationAllowed(accountId)) {
                log.warn("【账号{}】自动化已暂停或冷却中，拒绝API调用", accountId);
                return new ApiCallResult(false, null, "账号自动化已暂停或冷却中，请稍后重试", false, false);
            }
            if (accountId != null && !acquireRateLimitSlot(accountId)) {
                log.warn("【账号{}】API请求频率超限，拒绝本次调用", accountId);
                return new ApiCallResult(false, null, "账号请求频率超限，请稍后重试", false, false);
            }
        } catch (Exception e) {
            log.error("【账号{}】自动化保护检查异常: {}", accountId, e.getMessage());
        }

        // P0-6：账号级并发上限
        if (accountId == null) {
            return doCallApiWithRetry(accountId, apiName, dataMap, cookiesStr,
                    extraHeaders, extraQueryParams, apiVersion, retryCount);
        }
        Semaphore semaphore = accountSemaphores.computeIfAbsent(accountId,
                k -> new Semaphore(XianyuProtocolProfile.ACCOUNT_MAX_CONCURRENCY));
        if (!semaphore.tryAcquire()) {
            log.warn("【账号{}】并发请求数已达上限，拒绝本次API调用", accountId);
            return new ApiCallResult(false, null, "账号并发请求数已达上限，请稍后重试", false, false);
        }
        try {
            return doCallApiWithRetry(accountId, apiName, dataMap, cookiesStr,
                    extraHeaders, extraQueryParams, apiVersion, retryCount);
        } finally {
            semaphore.release();
        }
    }

    private ApiCallResult doCallApiWithRetry(Long accountId, String apiName,
                                           Map<String, Object> dataMap, String cookiesStr,
                                           Map<String, String> extraHeaders,
                                           Map<String, String> extraQueryParams,
                                           String apiVersion,
                                           int retryCount) {
        try {

            XianyuApiUtils.ApiCallResultWithHeaders result = XianyuApiUtils.callApiWithHeaders(
                    apiName, dataMap, cookiesStr, null, null, extraHeaders, extraQueryParams, apiVersion);

            if (result == null) {
                log.error("【账号{}】API调用在发送前失败", accountId);
                return new ApiCallResult(false, null, "请求未发送", false, false);
            }

            if (result.isResponseReceived()
                    && result.getStatusCode() > 0
                    && (result.getStatusCode() < 200 || result.getStatusCode() >= 300)) {
                log.error("【账号{}】API调用收到明确HTTP错误: status={}", accountId, result.getStatusCode());
                return new ApiCallResult(
                        false, result.getBody(), "HTTP " + result.getStatusCode(), false, false);
            }

            String response = result.getBody();
            if (response == null || response.isEmpty()) {
                boolean outcomeUnknown = result.isRequestAttempted();
                log.error("【账号{}】API调用未获得有效响应: outcomeUnknown={}", accountId, outcomeUnknown);
                return new ApiCallResult(false, null, "响应为空", false, outcomeUnknown);
            }

            // 2. 【关键】处理响应中的Set-Cookie（参考Python: session自动处理 + clear_duplicate_cookies）
            List<String> setCookieHeaders = result.getSetCookieHeaders();
            if (!setCookieHeaders.isEmpty()) {
                log.info("【账号{}】检测到响应中的Set-Cookie，数量: {}", accountId, setCookieHeaders.size());
                // 更新Cookie到数据库
                updateCookiesFromResponse(accountId, cookiesStr, setCookieHeaders);
            }

            // 3. 解析响应
            Map<String, Object> responseMap;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
                responseMap = parsed;
            } catch (Exception e) {
                log.error("【账号{}】API响应无法解析: responseLength={}", accountId, response.length());
                return new ApiCallResult(false, response, "响应格式错误", false, true);
            }

            @SuppressWarnings("unchecked")
            List<String> ret = (List<String>) responseMap.get("ret");

            if (ret == null || ret.isEmpty()) {
                log.error("【账号{}】API响应格式错误：缺少ret字段", accountId);
                return new ApiCallResult(false, response, "响应格式错误", false, true);
            }

            String retCode = ret.get(0);

            // 4. 检查是否成功
            if (retCode.contains("SUCCESS")) {
                log.info("【账号{}】API调用成功: {}", accountId, apiName);
                authFailureGuard.recordSuccess(accountId);
                return new ApiCallResult(true, response, null, false);
            }

            // 5. 检查是否需要刷新Cookie（令牌过期）
            if (isTokenExpired(retCode)) {
                log.warn("【账号{}】检测到令牌过期，尝试自动刷新... (重试次数: {}/{})",
                        accountId, retryCount, MAX_RETRY_COUNT);

                // 检查是否超过最大重试次数
                if (retryCount >= MAX_RETRY_COUNT) {
                    log.error("【账号{}】令牌刷新重试次数已达上限，停止重试", accountId);
                    authFailureGuard.recordFailure(accountId, retCode);
                    return new ApiCallResult(false, response, "令牌过期且自动刷新失败", true);
                }

                // 尝试刷新Cookie
                boolean refreshSuccess = cookieRefreshService.refreshCookie(accountId);

                if (refreshSuccess) {
                    log.info("【账号{}】Cookie刷新成功，准备重试API调用...", accountId);

                    // 等待一小段时间
                    Thread.sleep(RETRY_INTERVAL);

                    // 获取新的Cookie
                    String newCookieStr = accountService.getCookieByAccountId(accountId);
                    if (newCookieStr != null && !newCookieStr.isEmpty()) {
                        // 递归调用，重试API
                        return callApiWithRetry(
                                accountId,
                                apiName,
                                dataMap,
                                newCookieStr,
                                extraHeaders,
                                extraQueryParams,
                                apiVersion,
                                retryCount + 1);
                    } else {
                        log.error("【账号{}】获取新Cookie失败", accountId);
                    }
                } else {
                    log.warn("【账号{}】Cookie自动刷新失败", accountId);
                }

                authFailureGuard.recordFailure(accountId, retCode);
                return new ApiCallResult(false, response, "令牌过期，自动刷新失败", true);
            }

            // 6. 检查是否触发风控
            if (isRiskControl(retCode)) {
                log.error("【账号{}】触发风控: {}", accountId, retCode);
                authFailureGuard.recordFailure(accountId, retCode);
                if (riskControlService != null) {
                    String eventType = retCode.contains(RATE_LIMIT_MARKER) ? "RATE_LIMITED" : "RISK_CONTROL";
                    riskControlService.recordRiskEvent(accountId, eventType, retCode);
                }
                // 风控告警（去重/抑制由 AlertService 持久化处理）
                if (alertService != null) {
                    try {
                        alertService.checkAccountRisk(accountId, retCode);
                    } catch (Exception e) {
                        log.warn("【账号{}】风控告警持久化失败: errorType={}", accountId, e.getClass().getSimpleName());
                    }
                }
                return new ApiCallResult(false, response, "触发风控，需要人工处理", false);
            }

            // 7. 其他错误
            log.error("【账号{}】API调用失败: {}", accountId, retCode);
            return new ApiCallResult(false, response, retCode, false);

        } catch (Exception e) {
            // 走到这里说明请求已进入发送/后处理阶段，无法确认服务端是否已受理。
            // 标记 outcomeUnknown，让上层保留卡密预占而不是回滚（回滚会导致重复发货）。
            log.error("【账号{}】API调用异常，结果未知: apiName={}", accountId, apiName, e);
            return new ApiCallResult(false, null, "调用异常: " + e.getMessage(), false, true);
        }
    }

    /**
     * P0-6：账号级滑动窗口限速（每分钟最多 ACCOUNT_RATE_LIMIT_PER_MIN 次）。
     * 返回 true 表示本次请求被允许并已计数。
     */
    private boolean acquireRateLimitSlot(Long accountId) {
        long now = System.currentTimeMillis();
        Deque<Long> window = rateLimitWindows.computeIfAbsent(accountId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= XianyuProtocolProfile.ACCOUNT_RATE_LIMIT_PER_MIN) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * 从响应的Set-Cookie中更新Cookie
     * 参考Python: requests.Session自动处理Set-Cookie + clear_duplicate_cookies
     *
     * @param accountId 账号ID
     * @param currentCookieStr 当前Cookie字符串
     * @param setCookieHeaders 响应中的Set-Cookie列表
     */
    private void updateCookiesFromResponse(Long accountId, String currentCookieStr, List<String> setCookieHeaders) {
        try {
            // 合并Cookie
            String newCookieStr = mergeCookies(currentCookieStr, setCookieHeaders);

            // 清理重复Cookie
            newCookieStr = cookieRefreshService.clearDuplicateCookies(newCookieStr);

            // 更新数据库中的Cookie
            if (!newCookieStr.equals(currentCookieStr)) {
                accountService.updateCookie(accountId, newCookieStr);
                log.info("【账号{}】Cookie已从响应Set-Cookie更新到数据库", accountId);
            }
        } catch (Exception e) {
            log.error("【账号{}】处理响应Set-Cookie失败", accountId, e);
        }
    }

    /**
     * 合并Cookie（新Cookie覆盖旧Cookie）
     * 模拟Python requests.Session自动处理Set-Cookie的行为
     */
    private String mergeCookies(String oldCookieStr, List<String> newCookies) {
        Map<String, String> cookies = new java.util.LinkedHashMap<>();

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
            // 只提取第一个name=value对
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^\\s*([^=;\\s]+)=([^;]*)");
            java.util.regex.Matcher matcher = pattern.matcher(newCookie);
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
    
    /**
     * 判断是否为令牌过期错误
     */
    private boolean isTokenExpired(String retCode) {
        return retCode.contains("FAIL_SYS_TOKEN_EXOIRED") ||  // 注意：API返回的拼写错误
               retCode.contains("FAIL_SYS_TOKEN_EXPIRED") ||
               retCode.contains("FAIL_SYS_TOKEN_EMPTY") ||    // 首次请求常通过此响应下发新的 _m_h5_tk
               retCode.contains("FAIL_SYS_SESSION_EXPIRED") ||
               retCode.contains("令牌过期") ||
               retCode.contains("令牌为空");
    }
    
    /**
     * 判断是否为风控错误
     */
    private boolean isRiskControl(String retCode) {
        return retCode.contains("RGV587_ERROR") ||
               retCode.contains("被挤爆啦") ||
               retCode.contains("FAIL_SYS_USER_VALIDATE");
    }
    
    /**
     * API调用结果封装类
     */
    public static class ApiCallResult {
        private final boolean success;
        private final String response;
        private final String errorMessage;
        private final boolean tokenExpired;
        private final boolean outcomeUnknown;
        
        public ApiCallResult(boolean success, String response, String errorMessage, boolean tokenExpired) {
            this(success, response, errorMessage, tokenExpired, false);
        }

        public ApiCallResult(
                boolean success, String response, String errorMessage,
                boolean tokenExpired, boolean outcomeUnknown) {
            this.success = success;
            this.response = response;
            this.errorMessage = errorMessage;
            this.tokenExpired = tokenExpired;
            this.outcomeUnknown = outcomeUnknown;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getResponse() {
            return response;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public boolean isTokenExpired() {
            return tokenExpired;
        }

        public boolean isOutcomeUnknown() {
            return outcomeUnknown;
        }
        
        /**
         * 从响应中提取data字段
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> extractData() {
            if (response == null || response.isEmpty()) {
                return null;
            }
            
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> responseMap = mapper.readValue(response, Map.class);
                return (Map<String, Object>) responseMap.get("data");
            } catch (Exception e) {
                log.error("提取data字段失败", e);
                return null;
            }
        }
    }
}
