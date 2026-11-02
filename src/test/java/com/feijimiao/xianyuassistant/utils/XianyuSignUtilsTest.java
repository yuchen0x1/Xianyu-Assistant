package com.feijimiao.xianyuassistant.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XianyuSignUtilsTest {

    @Test
    void parseCookiesHandlesNullAndEmpty() {
        assertThat(XianyuSignUtils.parseCookies(null)).isEmpty();
        assertThat(XianyuSignUtils.parseCookies("")).isEmpty();
    }

    @Test
    void parseCookiesSplitsPairsAndTrims() {
        Map<String, String> cookies = XianyuSignUtils.parseCookies("a=1; b=2; _m_h5_tk=abc_123");
        assertThat(cookies).hasSize(3);
        assertThat(cookies.get("a")).isEqualTo("1");
        assertThat(cookies.get("b")).isEqualTo("2");
        assertThat(cookies.get("_m_h5_tk")).isEqualTo("abc_123");
    }

    @Test
    void parseCookiesIgnoresMalformedEntry() {
        Map<String, String> cookies = XianyuSignUtils.parseCookies("onlykey; c=3");
        assertThat(cookies).containsOnlyKeys("c");
        assertThat(cookies.get("c")).isEqualTo("3");
    }

    @Test
    void extractTokenSplitsOnUnderscore() {
        Map<String, String> cookies = XianyuSignUtils.parseCookies("_m_h5_tk=tokenPart_secretPart; other=1");
        assertThat(XianyuSignUtils.extractToken(cookies)).isEqualTo("tokenPart");
    }

    @Test
    void extractTokenReturnsEmptyWhenMissing() {
        Map<String, String> cookies = XianyuSignUtils.parseCookies("other=1");
        assertThat(XianyuSignUtils.extractToken(cookies)).isEmpty();
    }

    @Test
    void extractTokenFromStringDirectly() {
        assertThat(XianyuSignUtils.extractTokenFromString("_m_h5_tk=tk_xyz; a=1")).isEqualTo("tk");
    }

    @Test
    void formatCookiesRoundTrips() {
        Map<String, String> cookies = Map.of("a", "1", "b", "2");
        String formatted = XianyuSignUtils.formatCookies(cookies);
        assertThat(formatted).contains("a=1").contains("b=2");
        // 解析回去应该等价
        Map<String, String> reparsed = XianyuSignUtils.parseCookies(formatted);
        assertThat(reparsed).isEqualTo(cookies);
    }

    @Test
    void formatCookiesHandlesNull() {
        assertThat(XianyuSignUtils.formatCookies(null)).isEmpty();
    }

    @Test
    void generateSignIsDeterministicAnd32HexChars() {
        String sign1 = XianyuSignUtils.generateSign("1700000000000", "tokenPart", "{\"key\":\"value\"}");
        String sign2 = XianyuSignUtils.generateSign("1700000000000", "tokenPart", "{\"key\":\"value\"}");
        assertThat(sign1).isEqualTo(sign2);
        assertThat(sign1).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void generateSignChangesWithData() {
        String signA = XianyuSignUtils.generateSign("1700000000000", "tk", "dataA");
        String signB = XianyuSignUtils.generateSign("1700000000000", "tk", "dataB");
        assertThat(signA).isNotEqualTo(signB);
    }
}
