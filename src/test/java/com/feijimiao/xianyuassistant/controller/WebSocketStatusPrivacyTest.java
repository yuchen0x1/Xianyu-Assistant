package com.feijimiao.xianyuassistant.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketStatusPrivacyTest {

    @Test
    void statusResponseExposesMetadataInsteadOfReusableCredentials() {
        assertThat(WebSocketController.WebSocketStatusRespDTO.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("cookieText", "mH5Tk", "mh5Tk", "websocketToken")
                .contains(
                        "cookieConfigured",
                        "cookieLength",
                        "h5TokenConfigured",
                        "h5TokenLength",
                        "websocketTokenConfigured",
                        "websocketTokenLength",
                        "lastWebSocketMessageAt",
                        "webSocketMessageCount",
                        "lastCustomerMessageAt",
                        "lastAutoReplyStatus",
                        "lastAutoReplyReason",
                        "lastAutoReplyUpdatedAt",
                        "lastSuccessfulReplyAt");
    }
}
