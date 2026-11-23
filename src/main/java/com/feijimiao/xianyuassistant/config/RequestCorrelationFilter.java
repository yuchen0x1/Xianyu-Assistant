package com.feijimiao.xianyuassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求分配可追踪的请求编号。
 *
 * <p>请求编号同时出现在响应头 {@code X-Request-Id} 和日志 MDC 中，用户报障时
 * 只需提供页面上的编号，就能在日志里定位到这一次请求，而不用靠时间窗口猜测。
 * 上游（反向代理、网关）已经生成编号时直接沿用，但只接受安全的字符集，避免把
 * 任意头内容注入日志。</p>
 *
 * <p>MDC 必须在本过滤器里清理：Tomcat 线程是复用的，不清理会让上一个请求的
 * 编号串到下一个请求的日志里，反而制造错误关联。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** 允许字母、数字、连字符和下划线，长度 8–64；其余一律丢弃并重新生成。 */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return SAFE_REQUEST_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
