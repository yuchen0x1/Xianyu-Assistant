package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void appliesStrictBaselineHeadersToEveryResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).contains(
                "default-src 'self'",
                "script-src 'self'",
                "object-src 'none'",
                "frame-ancestors 'none'",
                "base-uri 'self'");
        // 不允许把脚本内联放行，否则 CSP 对 XSS 基本失效
        assertThat(csp).doesNotContain("script-src 'self' 'unsafe-inline'", "'unsafe-eval'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy")).contains("camera=()", "microphone=()");
    }

    @Test
    void allowsRemoteProductImagesAndInlineComponentStyles() {
        assertThat(SecurityHeadersFilter.CONTENT_SECURITY_POLICY)
                .contains("img-src 'self' data: blob: https:")
                .contains("style-src 'self' 'unsafe-inline'")
                .contains("connect-src 'self'");
    }
}
