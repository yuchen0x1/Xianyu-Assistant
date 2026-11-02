package com.feijimiao.xianyuassistant.utils;

import com.feijimiao.xianyuassistant.config.ObjectMapperProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 闲鱼API工具类
 * 封装闲鱼API调用的通用逻辑
 */
@Slf4j
public class XianyuApiUtils {
    
    private static final ObjectMapper objectMapper = ObjectMapperProvider.getSharedMapper();
    private static final String BASE_URL = XianyuProtocolProfile.H5_BASE_URL;
    
    /**
     * 闲鱼API基础URL
     */
    /**
     * 构建标准的闲鱼API请求头
     *
     * @param cookiesStr Cookie字符串
     * @return 请求头Map
     */
    public static Map<String, String> buildStandardHeaders(String cookiesStr) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cookiesStr);
        headers.put("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT);
        headers.put("Accept", "application/json");
        headers.put("Accept-Language", XianyuProtocolProfile.WEB_ACCEPT_LANGUAGE);
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        headers.put("Origin", XianyuProtocolProfile.WEB_ORIGIN);
        headers.put("Referer", XianyuProtocolProfile.WEB_REFERER);
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-site");
        headers.put("Sec-Ch-Ua", XianyuProtocolProfile.WEB_SEC_CH_UA);
        headers.put("Sec-Ch-Ua-Mobile", "?0");
        headers.put("Sec-Ch-Ua-Platform", "\"Windows\"");
        return headers;
    }
    
    /**
     * 构建标准的URL参数
     *
     * @param apiName API名称（如：mtop.idle.web.xyh.item.list）
     * @param timestamp 时间戳
     * @param sign 签名
     * @return URL参数Map
     */
    public static Map<String, String> buildStandardParams(String apiName, String timestamp, String sign) {
        return buildStandardParams(apiName, timestamp, sign, null);
    }

    /**
     * 构建标准的URL参数（支持按 API 指定协议版本号）
     *
     * @param apiName API名称
     * @param timestamp 时间戳
     * @param sign 签名
     * @param apiVersion API协议版本号（如 "1.0"/"1.1"/"2.0"），null 时使用协议默认版本
     * @return URL参数Map
     */
    public static Map<String, String> buildStandardParams(String apiName, String timestamp, String sign,
                                                           String apiVersion) {
        String version = (apiVersion == null || apiVersion.isBlank())
                ? XianyuProtocolProfile.H5_API_VERSION : apiVersion;
        Map<String, String> params = new HashMap<>();
        params.put("jsv", XianyuProtocolProfile.H5_JSV);
        params.put("appKey", XianyuProtocolProfile.H5_APP_KEY);
        params.put("t", timestamp);
        params.put("sign", sign);
        params.put("v", version);
        params.put("type", "originaljson");
        params.put("accountSite", "xianyu");
        params.put("dataType", "json");
        params.put("timeout", "20000");
        params.put("api", apiName);
        params.put("sessionOption", "AutoLoginOnly");
        return params;
    }
    
    /**
     * 添加SPM参数（用于统计追踪）
     *
     * @param params 参数Map
     * @param spmCnt SPM计数
     * @param spmPre SPM前缀
     */
    public static void addSpmParams(Map<String, String> params, String spmCnt, String spmPre) {
        if (spmCnt != null && !spmCnt.isEmpty()) {
            params.put("spm_cnt", spmCnt);
        }
        if (spmPre != null && !spmPre.isEmpty()) {
            params.put("spm_pre", spmPre);
        }
    }
    
    /**
     * 调用闲鱼API（POST方法）
     *
     * @param apiName API名称
     * @param dataMap 数据Map
     * @param cookiesStr Cookie字符串
     * @param spmCnt SPM计数（可选）
     * @param spmPre SPM前缀（可选）
     * @return API响应字符串
     */
    public static String callApi(String apiName, Map<String, Object> dataMap, String cookiesStr,
                                  String spmCnt, String spmPre) {
        try {
            // 1. 解析Cookie获取token
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookiesStr);
            String token = XianyuSignUtils.extractToken(cookies);

            if (token == null || token.isEmpty()) {
                log.error("无法从Cookie中提取token");
                return null;
            }

            // 2. 生成时间戳
            String timestamp = String.valueOf(System.currentTimeMillis());

            // 3. 序列化数据
            String dataJson = objectMapper.writeValueAsString(dataMap);

            // 4. 生成签名
            String sign = XianyuSignUtils.generateSign(timestamp, token, dataJson);

            // 5. 构建URL参数
            Map<String, String> params = buildStandardParams(apiName, timestamp, sign);

            // 6. 添加SPM参数（如果提供）
            if (spmCnt != null || spmPre != null) {
                addSpmParams(params, spmCnt, spmPre);
            }

            // 7. 构建完整URL
            String url = buildUrl(apiName, params);

            // 8. 构建请求头
            Map<String, String> headers = buildStandardHeaders(cookiesStr);

            // 9. 构建请求体
            Map<String, String> body = new HashMap<>();
            body.put("data", dataJson);

            // 10. 发送请求
            log.info("调用闲鱼API: {}", apiName);
            return HttpClientUtils.post(url, headers, body);

        } catch (Exception e) {
            log.error("调用闲鱼API失败: apiName={}", apiName, e);
            return null;
        }
    }

    /**
     * 调用闲鱼API（POST方法，返回包含响应头的结果）
     * 【关键】参考Python：所有API调用都需要处理Set-Cookie
     *
     * @param apiName API名称
     * @param dataMap 数据Map
     * @param cookiesStr Cookie字符串
     * @param spmCnt SPM计数（可选）
     * @param spmPre SPM前缀（可选）
     * @return ApiCallResultWithHeaders 包含响应体和响应头的结果
     */
    public static ApiCallResultWithHeaders callApiWithHeaders(String apiName, Map<String, Object> dataMap, String cookiesStr,
                                                               String spmCnt, String spmPre) {
        return callApiWithHeaders(apiName, dataMap, cookiesStr, spmCnt, spmPre, null);
    }

    public static ApiCallResultWithHeaders callApiWithHeaders(String apiName, Map<String, Object> dataMap, String cookiesStr,
                                                               String spmCnt, String spmPre,
                                                               Map<String, String> extraHeaders) {
        return callApiWithHeaders(apiName, dataMap, cookiesStr, spmCnt, spmPre, extraHeaders, null);
    }

    public static ApiCallResultWithHeaders callApiWithHeaders(String apiName, Map<String, Object> dataMap, String cookiesStr,
                                                               String spmCnt, String spmPre,
                                                               Map<String, String> extraHeaders,
                                                               Map<String, String> extraQueryParams) {
        return callApiWithHeaders(apiName, dataMap, cookiesStr, spmCnt, spmPre, extraHeaders, extraQueryParams, null);
    }

    /**
     * 调用闲鱼API（POST方法，返回包含响应头的结果，支持按 API 指定协议版本号）。
     *
     * @param apiName API名称
     * @param dataMap 数据Map
     * @param cookiesStr Cookie字符串
     * @param spmCnt SPM计数（可选）
     * @param spmPre SPM前缀（可选）
     * @param extraHeaders 额外请求头（可选）
     * @param extraQueryParams 额外查询参数（可选）
     * @param apiVersion API协议版本号（如 "1.0"/"1.1"/"2.0"），null 时使用协议默认版本
     * @return ApiCallResultWithHeaders 包含响应体和响应头的结果
     */
    public static ApiCallResultWithHeaders callApiWithHeaders(String apiName, Map<String, Object> dataMap, String cookiesStr,
                                                               String spmCnt, String spmPre,
                                                               Map<String, String> extraHeaders,
                                                               Map<String, String> extraQueryParams,
                                                               String apiVersion) {
        try {
            // 1. 解析Cookie获取token
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookiesStr);
            String token = XianyuSignUtils.extractToken(cookies);

            if (token == null || token.isEmpty()) {
                log.error("无法从Cookie中提取token");
                return ApiCallResultWithHeaders.notSent();
            }

            // 2. 生成时间戳
            String timestamp = String.valueOf(System.currentTimeMillis());

            // 3. 序列化数据
            String dataJson = objectMapper.writeValueAsString(dataMap);

            // 4. 生成签名
            String sign = XianyuSignUtils.generateSign(timestamp, token, dataJson);

            // 5. 构建URL参数
            Map<String, String> params = buildStandardParams(apiName, timestamp, sign, apiVersion);

            // 6. 添加SPM参数（如果提供）
            if (spmCnt != null || spmPre != null) {
                addSpmParams(params, spmCnt, spmPre);
            }

            if (extraQueryParams != null && !extraQueryParams.isEmpty()) {
                params.putAll(extraQueryParams);
            }

            // 7. 构建完整URL
            String url = buildUrl(apiName, params, apiVersion);

            // 8. 构建请求头
            Map<String, String> headers = buildStandardHeaders(cookiesStr);

            if (extraHeaders != null && !extraHeaders.isEmpty()) {
                headers.putAll(extraHeaders);
            }

            // 9. 构建请求体
            Map<String, String> body = new HashMap<>();
            body.put("data", dataJson);

            // 10. 发送请求（带响应头）
            log.info("调用闲鱼API(带响应头): {}", apiName);
            HttpClientUtils.HttpResponseResult result = HttpClientUtils.postWithHeaders(url, headers, body);

            return new ApiCallResultWithHeaders(
                result != null ? result.getBody() : null,
                result != null ? result.getHeaders() : null,
                result != null ? result.getStatusCode() : 0,
                result != null && result.isRequestAttempted(),
                result != null && result.isResponseReceived()
            );

        } catch (Exception e) {
            log.error("调用闲鱼API失败: apiName={}", apiName, e);
            return ApiCallResultWithHeaders.notSent();
        }
    }

    /**
     * API调用结果（包含响应头）
     */
    public static class ApiCallResultWithHeaders {
        private final String body;
        private final Map<String, List<String>> headers;
        private final int statusCode;
        private final boolean requestAttempted;
        private final boolean responseReceived;

        public ApiCallResultWithHeaders(String body, Map<String, List<String>> headers) {
            this(body, headers, body != null ? 200 : 0, body != null, body != null);
        }

        public ApiCallResultWithHeaders(
                String body, Map<String, List<String>> headers, int statusCode,
                boolean requestAttempted, boolean responseReceived) {
            this.body = body;
            this.headers = headers;
            this.statusCode = statusCode;
            this.requestAttempted = requestAttempted;
            this.responseReceived = responseReceived;
        }

        static ApiCallResultWithHeaders notSent() {
            return new ApiCallResultWithHeaders(null, null, 0, false, false);
        }

        public String getBody() {
            return body;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isRequestAttempted() {
            return requestAttempted;
        }

        public boolean isResponseReceived() {
            return responseReceived;
        }

        /**
         * 获取Set-Cookie头
         */
        public List<String> getSetCookieHeaders() {
            if (headers == null) {
                return java.util.Collections.emptyList();
            }
            List<String> setCookies = headers.get("Set-Cookie");
            return setCookies != null ? setCookies : java.util.Collections.emptyList();
        }
    }
    
    /**
     * 调用闲鱼API（POST方法，不带SPM参数）
     *
     * @param apiName API名称
     * @param dataMap 数据Map
     * @param cookiesStr Cookie字符串
     * @return API响应字符串
     */
    public static String callApi(String apiName, Map<String, Object> dataMap, String cookiesStr) {
        return callApi(apiName, dataMap, cookiesStr, null, null);
    }
    
    /**
     * 构建完整的API URL
     *
     * @param apiName API名称（如：mtop.idle.web.xyh.item.list）
     * @param params URL参数
     * @return 完整URL
     */
    private static String buildUrl(String apiName, Map<String, String> params) {
        return buildUrl(apiName, params, null);
    }

    /**
     * 构建完整的API URL（支持按 API 指定协议版本号）
     *
     * @param apiName API名称（如：mtop.idle.web.xyh.item.list）
     * @param params URL参数
     * @param apiVersion API协议版本号（如 "1.0"/"1.1"/"2.0"），null 时使用协议默认版本
     * @return 完整URL
     */
    private static String buildUrl(String apiName, Map<String, String> params, String apiVersion) {
        String version = (apiVersion == null || apiVersion.isBlank())
                ? XianyuProtocolProfile.H5_API_VERSION : apiVersion;
        StringBuilder url = new StringBuilder(BASE_URL);
        // 官方 MTop SDK 会将 API 与版本标准化为小写，保持路径与浏览器实际请求一致。
        url.append(apiName.toLowerCase(java.util.Locale.ROOT))
                .append("/")
                .append(version.toLowerCase(java.util.Locale.ROOT))
                .append("/");
        
        if (params != null && !params.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    url.append("&");
                }
                url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        
        return url.toString();
    }
    
    /**
     * 解析API响应，检查是否成功
     *
     * @param response API响应字符串
     * @return 是否成功
     */
    public static boolean isSuccess(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
            @SuppressWarnings("unchecked")
            java.util.List<String> ret = (java.util.List<String>) responseMap.get("ret");
            
            return ret != null && !ret.isEmpty() && ret.get(0).contains("SUCCESS");
        } catch (Exception e) {
            log.error("解析API响应失败", e);
            return false;
        }
    }
    
    /**
     * 从响应中提取data字段
     *
     * @param response API响应字符串
     * @return data字段的Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractData(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        
        try {
            Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
            return (Map<String, Object>) responseMap.get("data");
        } catch (Exception e) {
            log.error("提取data字段失败", e);
            return null;
        }
    }
    
    /**
     * 从响应中提取错误信息
     *
     * @param response API响应字符串
     * @return 错误信息
     */
    @SuppressWarnings("unchecked")
    public static String extractError(String response) {
        if (response == null || response.isEmpty()) {
            return "响应为空";
        }
        
        try {
            Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
            java.util.List<String> ret = (java.util.List<String>) responseMap.get("ret");
            
            if (ret != null && !ret.isEmpty()) {
                return ret.get(0);
            }
            
            return "未知错误";
        } catch (Exception e) {
            log.error("提取错误信息失败", e);
            return "解析响应失败: " + e.getMessage();
        }
    }
}
