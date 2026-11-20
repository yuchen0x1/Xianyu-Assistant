package com.feijimiao.xianyuassistant.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryContentScannerTest {
    @Test
    void preservesLinksPlaceholdersAndMultiwordLabelledValues() {
        String text = "链接 HTTPS://example.com/share，卡密：AB-42 密码: correct horse. 请保管\n占位符 {kmKey}";
        assertThat(values(text, false)).containsExactly(
                "HTTPS://example.com/share", "卡密：AB-42", "密码: correct horse", "{kmKey}");
        assertThat(values(text, true)).containsExactlyElementsOf(values(text, false));
    }

    @Test
    void classifiesOrdinaryAsciiSeparatelyFromSensitiveRuns() {
        String text = "提示 Hello friend，网址 example.com/path，邮箱 user@example.com，编号 AB42";
        assertThat(values(text, false)).containsExactly("Hello", "friend", "example.com/path", "user@example.com", "AB42");
        assertThat(values(text, true)).containsExactly("example.com/path", "user@example.com", "AB42");
    }

    @Test
    void handlesMissingValuesAndPlaceholderBoundaries() {
        assertThat(values("账号安全很重要，密码不要泄露。卡密：", false)).isEmpty();
        assertThat(values("{outer{inner}} {} {unfinished", true)).containsExactly("{inner}");
        assertThat(values("账号\tAlice\n密码：B2\n订单号 1234", true))
                .containsExactly("账号\tAlice", "密码：B2", "订单号 1234");
    }

    @Test
    void scansAnOrdinaryMaximumLengthDeliveryMessage() {
        String sentence = "您的资料已经准备好，请按说明领取并妥善保管，如需帮助请联系卖家。";
        String text = (sentence + sentence + sentence + sentence + sentence + sentence + sentence).substring(0, 200);
        assertThat(values(text, false)).isEmpty();
        assertThat(values(text, true)).isEmpty();
    }

    private List<String> values(String text, boolean sensitiveOnly) {
        return DeliveryContentScanner.scan(text, sensitiveOnly).stream().map(fragment -> fragment.value(text)).toList();
    }
}
