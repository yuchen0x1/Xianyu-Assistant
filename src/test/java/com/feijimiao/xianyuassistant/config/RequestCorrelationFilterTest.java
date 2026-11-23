package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdAndExposesItOnTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            // 日志记录时应当能读到同一个编号
            assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNotBlank();
        });

        String requestId = response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank().matches("[A-Za-z0-9_-]{8,64}");
        // 请求结束后必须清掉，否则线程复用会把编号串到下一个请求的日志
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void reusesSafeUpstreamRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/goods");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "edge-abc12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("edge-abc12345");
    }

    @Test
    void replacesUnsafeHeaderValuesInsteadOfLoggingThem() {
        assertThat(RequestCorrelationFilter.sanitize("bad id with spaces")).isNull();
        assertThat(RequestCorrelationFilter.sanitize("inject\nINFO fake log line")).isNull();
        assertThat(RequestCorrelationFilter.sanitize("short")).isNull();
        assertThat(RequestCorrelationFilter.sanitize("x".repeat(200))).isNull();
        assertThat(RequestCorrelationFilter.sanitize(null)).isNull();
        assertThat(RequestCorrelationFilter.sanitize("req-12345678")).isEqualTo("req-12345678");
    }
}
