package com.feijimiao.xianyuassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 统一安全响应头。
 *
 * <p>CSP 设计取舍：脚本只允许同源，不放开 {@code unsafe-inline}/{@code unsafe-eval}，
 * 这是防护 XSS 的核心；样式仍允许内联，因为 Vue 的 {@code :style} 绑定和
 * 组件库都要写 style 属性，禁止它只会把界面弄坏而不是变安全。图片来源允许
 * {@code https:}，商品封面和头像来自平台 CDN，无法预先枚举域名。</p>
 *
 * <p>该头对静态页面和 API 都生效，但 API 返回的是 JSON，多出的头没有副作用；
 * 统一注入可以避免只在 HTML 路由上生效、换个入口就漏掉的情况。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob: https:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        chain.doFilter(request, response);
    }
}
