package com.feijimiao.xianyuassistant.utils;

import com.feijimiao.xianyuassistant.proxy.ProxyPoolAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * HTTP客户端工具类
 */
@Slf4j
public class HttpClientUtils {

    private static final RestTemplate restTemplate = createRestTemplate(null);

    /**
     * 代理池访问器（可选）。由 {@code ProxyPoolManager} 在启动时通过
     * {@link #setProxyAccessor} 注入，用于 mtop 调用链路的代理轮换。
     */
    private static volatile ProxyPoolAccessor proxyAccessor;

    /**
     * 供 ProxyPoolManager 注入代理池访问器。
     */
    public static void setProxyAccessor(ProxyPoolAccessor accessor) {
        proxyAccessor = accessor;
    }

    private static RestTemplate createRestTemplate() {
        return createRestTemplate(null);
    }

    private static RestTemplate createRestTemplate(Proxy proxy) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(15_000);
        if (proxy != null) {
            requestFactory.setProxy(proxy);
        }
        return new RestTemplate(requestFactory);
    }

    /**
     * 发送POST请求
     *
     * @param url     请求URL
     * @param headers 请求头
     * @param body    请求体
     * @return 响应字符串
     */
    public static String post(String url, Map<String, String> headers, Map<String, String> body) {
        try {
            log.info("发送POST请求: urlLength={}", url != null ? url.length() : 0);
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            if (headers != null) {
                headers.forEach(httpHeaders::add);
                log.debug("请求头数量: {}", headers.size());
            }

            // 构建表单数据（需要进行 URL 编码）
            StringBuilder formData = new StringBuilder();
            if (body != null) {
                body.forEach((key, value) -> {
                    try {
                        if (formData.length() > 0) {
                            formData.append("&");
                        }
                        formData.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        log.error("URL编码失败: key={}, valueLength={}, errorType={}", key,
                                value != null ? value.length() : 0, e.getClass().getSimpleName());
                    }
                });
                log.debug("表单数据长度: {}", formData.length());
            }

            HttpEntity<String> entity = new HttpEntity<>(formData.toString(), httpHeaders);
            log.info("开始执行HTTP请求...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("HTTP请求完成，状态码: {}", response.getStatusCode());
            return response.getBody();
        } catch (Exception e) {
            log.error("HTTP POST请求失败: urlLength={}, errorType={}",
                    url != null ? url.length() : 0, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 发送POST请求并返回包含响应头的结果
     * 用于提取响应中的Set-Cookie等信息
     *
     * @param url     请求URL
     * @param headers 请求头
     * @param body    请求体
     * @return HttpResponseResult 包含响应体和响应头
     */
    public static HttpResponseResult postWithHeaders(String url, Map<String, String> headers, Map<String, String> body) {
        ProxyPoolAccessor accessor = proxyAccessor;
        if (accessor == null || !accessor.isEnabled()) {
            return postWithHeaders(restTemplate, url, headers, body);
        }
        return postWithProxyRotation(url, headers, body, accessor);
    }

    /**
     * 带代理池轮换的 POST 请求：
     * <ol>
     *   <li>轮询获取一个可用代理（跳过黑名单）；全部黑名单或池为空时回退直连</li>
     *   <li>用该代理发起请求</li>
     *   <li>收到 HTTP 响应（不论状态码）→ 代理传输层正常 → 标记成功并返回</li>
     *   <li>网络层失败（无响应 / 未发出）→ 标记失败加入黑名单，换下一个代理重试</li>
     *   <li>达到最大尝试次数仍未成功 → 返回最后一次结果</li>
     * </ol>
     */
    private static HttpResponseResult postWithProxyRotation(String url, Map<String, String> headers,
                                                            Map<String, String> body, ProxyPoolAccessor accessor) {
        int maxAttempts = Math.max(1, accessor.maxAttempts());
        HttpResponseResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Proxy proxy = accessor.acquireProxy();
            if (proxy == null) {
                // 无可用代理，回退直连（仅本次请求）
                log.info("[ProxyPool] 无可用代理，回退直连: attempt={}", attempt);
                HttpResponseResult direct = postWithHeaders(restTemplate, url, headers, body);
                return direct;
            }
            String proxyLabel = labelOf(proxy);
            log.info("[ProxyPool] 使用代理发起请求: attempt={}/{} proxy={}", attempt, maxAttempts, proxyLabel);
            RestTemplate proxied = createRestTemplate(proxy);
            HttpResponseResult result = postWithHeaders(proxied, url, headers, body);
            lastResult = result;
            if (result.isResponseReceived()) {
                // 收到 HTTP 响应 → 代理传输层正常，标记成功并返回（即使 4xx/5xx 也不黑名单代理）
                accessor.markSuccess(proxy);
                return result;
            }
            // 网络层失败 → 加入黑名单，换下一个代理重试
            String failKind = result.isRequestAttempted() ? "no-response" : "not-sent";
            accessor.markFailure(proxy, failKind);
            log.warn("[ProxyPool] 代理调用失败，将切换: proxy={} failKind={}", proxyLabel, failKind);
        }
        return lastResult != null ? lastResult : HttpResponseResult.notSent();
    }

    private static String labelOf(Proxy proxy) {
        if (proxy == null || !(proxy.address() instanceof InetSocketAddress addr)) {
            return "direct";
        }
        return addr.getHostString() + ":" + addr.getPort();
    }

    static HttpResponseResult postWithHeaders(
            RestOperations client, String url, Map<String, String> headers, Map<String, String> body) {
        try {
            log.info("发送POST请求(带响应头): urlLength={}", url != null ? url.length() : 0);
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            if (headers != null) {
                headers.forEach(httpHeaders::add);
            }

            // 构建表单数据
            StringBuilder formData = new StringBuilder();
            if (body != null) {
                body.forEach((key, value) -> {
                    try {
                        if (formData.length() > 0) {
                            formData.append("&");
                        }
                        formData.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        log.error("URL编码失败: key={}, valueLength={}, errorType={}", key,
                                value != null ? value.length() : 0, e.getClass().getSimpleName());
                    }
                });
            }

            HttpEntity<String> entity = new HttpEntity<>(formData.toString(), httpHeaders);
            log.info("开始执行HTTP请求...");
            try {
                ResponseEntity<String> response = client.exchange(url, HttpMethod.POST, entity, String.class);
                log.info("HTTP请求完成，状态码: {}", response.getStatusCode());
                return HttpResponseResult.response(
                        response.getBody(), response.getStatusCode().value(), response.getHeaders());
            } catch (HttpStatusCodeException e) {
                log.warn("HTTP请求收到明确错误响应: status={}", e.getStatusCode().value());
                return HttpResponseResult.response(
                        e.getResponseBodyAsString(), e.getStatusCode().value(), e.getResponseHeaders());
            } catch (RestClientException e) {
                log.error("HTTP请求发出后未收到可确认响应: errorType={}", e.getClass().getSimpleName());
                return HttpResponseResult.noResponse();
            } catch (RuntimeException e) {
                log.error("HTTP请求执行结果无法确认: errorType={}", e.getClass().getSimpleName());
                return HttpResponseResult.noResponse();
            }
        } catch (Exception e) {
            log.error("HTTP POST请求发送前失败(带响应头): urlLength={}, errorType={}",
                    url != null ? url.length() : 0, e.getClass().getSimpleName());
            return HttpResponseResult.notSent();
        }
    }

    /**
     * HTTP响应结果（包含响应头）
     */
    public static class HttpResponseResult {
        private String body;
        private int statusCode;
        private HttpHeaders headers;
        private boolean requestAttempted;
        private boolean responseReceived;

        private static HttpResponseResult response(String body, int statusCode, HttpHeaders headers) {
            HttpResponseResult result = new HttpResponseResult();
            result.body = body;
            result.statusCode = statusCode;
            result.headers = headers;
            result.requestAttempted = true;
            result.responseReceived = true;
            return result;
        }

        private static HttpResponseResult noResponse() {
            HttpResponseResult result = new HttpResponseResult();
            result.requestAttempted = true;
            return result;
        }

        private static HttpResponseResult notSent() {
            return new HttpResponseResult();
        }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }

        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

        public HttpHeaders getHeaders() { return headers; }
        public void setHeaders(HttpHeaders headers) { this.headers = headers; }

        public boolean isRequestAttempted() { return requestAttempted; }

        public boolean isResponseReceived() { return responseReceived; }

        /**
         * 获取指定响应头的值列表
         */
        public List<String> getHeaderValues(String headerName) {
            if (headers == null) return List.of();
            return headers.get(headerName) != null ? headers.get(headerName) : List.of();
        }
    }

    /**
     * 发送GET请求
     *
     * @param url     请求URL
     * @param headers 请求头
     * @return 响应字符串
     */
    public static String get(String url, Map<String, String> headers) {
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            if (headers != null) {
                headers.forEach(httpHeaders::add);
            }

            HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("HTTP GET请求失败: urlLength={}, errorType={}",
                    url != null ? url.length() : 0, e.getClass().getSimpleName());
            return null;
        }
    }
}
