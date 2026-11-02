package com.feijimiao.xianyuassistant.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class XianyuApiCallUtilsOutcomeTest {

    private static final long ACCOUNT_ID = 20L;
    private static final String API_NAME = "delivery-api";
    private static final String COOKIE = "cookie";
    private static final Map<String, Object> DATA = Map.of("orderId", "order-1");
    private static final Map<String, String> HEADERS = Map.of("Origin", "https://local.invalid");

    private final XianyuApiCallUtils apiCallUtils = new XianyuApiCallUtils();

    @Test
    void responseLossAfterRequestIsUnknown() {
        XianyuApiUtils.ApiCallResultWithHeaders transportResult =
                new XianyuApiUtils.ApiCallResultWithHeaders(null, null, 0, true, false);

        XianyuApiCallUtils.ApiCallResult result = callWith(transportResult);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isOutcomeUnknown()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500})
    void httpErrorResponseIsExplicitFailure(int statusCode) {
        XianyuApiUtils.ApiCallResultWithHeaders transportResult =
                new XianyuApiUtils.ApiCallResultWithHeaders(
                        "error", Map.of(), statusCode, true, true);

        XianyuApiCallUtils.ApiCallResult result = callWith(transportResult);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isOutcomeUnknown()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("HTTP " + statusCode);
    }

    @Test
    void successfulResponseIsConfirmed() {
        XianyuApiUtils.ApiCallResultWithHeaders transportResult =
                new XianyuApiUtils.ApiCallResultWithHeaders(
                        "{\"ret\":[\"SUCCESS::调用成功\"],\"data\":{}}",
                        Map.of(), 200, true, true);

        XianyuApiCallUtils.ApiCallResult result = callWith(transportResult);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isOutcomeUnknown()).isFalse();
    }

    private XianyuApiCallUtils.ApiCallResult callWith(
            XianyuApiUtils.ApiCallResultWithHeaders transportResult) {
        try (var mocked = mockStatic(XianyuApiUtils.class)) {
            mocked.when(() -> XianyuApiUtils.callApiWithHeaders(
                            API_NAME, DATA, COOKIE, null, null, HEADERS, null, null))
                    .thenReturn(transportResult);
            return apiCallUtils.callApiWithRetry(ACCOUNT_ID, API_NAME, DATA, COOKIE, HEADERS);
        }
    }
}
