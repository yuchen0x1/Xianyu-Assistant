package com.feijimiao.xianyuassistant.utils;

import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XianyuProtocolProfileTest {

    @Test
    void standardHeadersUseTheCentralizedBrowserFingerprint() {
        Map<String, String> headers = XianyuApiUtils.buildStandardHeaders("_m_h5_tk=redacted");

        assertThat(headers.get("User-Agent")).isEqualTo(XianyuProtocolProfile.WEB_USER_AGENT);
        assertThat(headers.get("Sec-Ch-Ua")).isEqualTo(XianyuProtocolProfile.WEB_SEC_CH_UA);
        assertThat(headers.get("Origin")).isEqualTo(XianyuProtocolProfile.WEB_ORIGIN);
        assertThat(headers.get("User-Agent")).contains("Chrome/147.0.0.0");
        assertThat(headers.get("Sec-Ch-Ua")).contains("Chromium\";v=\"147");
        assertThat(XianyuProtocolProfile.WEBSOCKET_USER_AGENT).contains("Chrome/147.0.0.0");
    }

    @Test
    void standardParamsUseTheCurrentProtocolProfile() {
        Map<String, String> params = XianyuApiUtils.buildStandardParams(
                "mtop.taobao.idlemessage.pc.login.token", "123", "signature");

        assertThat(params)
                .containsEntry("appKey", XianyuProtocolProfile.H5_APP_KEY)
                .containsEntry("jsv", XianyuProtocolProfile.H5_JSV)
                .containsEntry("v", XianyuProtocolProfile.H5_API_VERSION);
    }
}
