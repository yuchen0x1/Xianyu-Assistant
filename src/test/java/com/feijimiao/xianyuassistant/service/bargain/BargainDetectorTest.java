package com.feijimiao.xianyuassistant.service.bargain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 议价意图识别测试：砍价关键词、出价提取与误判防护
 */
class BargainDetectorTest {

    private final BargainDetector detector = new BargainDetector();

    private BigDecimal offerOf(String message) {
        return detector.detect(message).getOfferedPrice();
    }

    @Test
    void 带货币单位的出价应被识别() {
        assertThat(detector.detect("50块能拿吗").isBargain()).isTrue();
        assertThat(offerOf("50块能拿吗")).isEqualByComparingTo("50");
        assertThat(detector.detect("便宜点吧，80元怎么样").isBargain()).isTrue();
        assertThat(offerOf("便宜点吧，80元怎么样")).isEqualByComparingTo("80");
        assertThat(detector.detect("¥60卖不卖").isBargain()).isTrue();
    }

    @Test
    void 裸数字出价应被识别() {
        assertThat(detector.detect("100行不行").isBargain()).isTrue();
        assertThat(offerOf("100行不行")).isEqualByComparingTo("100");
        assertThat(detector.detect("90？").isBargain()).isTrue();
        assertThat(offerOf("90？")).isEqualByComparingTo("90");
        assertThat(detector.detect("80可以吗").isBargain()).isTrue();
    }

    @Test
    void 目标价句式应被识别() {
        assertThat(detector.detect("便宜到100就拍").isBargain()).isTrue();
        assertThat(offerOf("便宜到100就拍")).isEqualByComparingTo("100");
        assertThat(detector.detect("降到150出吗").isBargain()).isTrue();
        assertThat(offerOf("降到150出吗")).isEqualByComparingTo("150");
    }

    @Test
    void 让价差额应被提取() {
        BargainDetector.BargainIntent intent = detector.detect("能不能少20");
        assertThat(intent.isBargain()).isTrue();
        assertThat(intent.getDeduction()).isEqualByComparingTo("20");
    }

    @Test
    void 纯砍价话术无出价也应命中意图() {
        BargainDetector.BargainIntent intent = detector.detect("最低多少钱");
        assertThat(intent.isBargain()).isTrue();
        assertThat(intent.getOfferedPrice()).isNull();
    }

    @Test
    void 普通咨询不应误判为议价() {
        assertThat(detector.detect("这个还在吗").isBargain()).isFalse();
        assertThat(detector.detect("什么时候能发货").isBargain()).isFalse();
        assertThat(detector.detect("有货吗").isBargain()).isFalse();
    }

    @Test
    void 非价格数字不应误判为出价() {
        assertThat(detector.detect("3天能发货吗").isBargain()).isFalse();
        assertThat(detector.detect("可以用2个账号吗").isBargain()).isFalse();
    }

    @Test
    void 空消息应安全返回() {
        BargainDetector.BargainIntent intent = detector.detect(null);
        assertThat(intent.isBargain()).isFalse();
        assertThat(detector.detect("").isBargain()).isFalse();
    }

    @Test
    void 多个出价取最后一个() {
        assertThat(offerOf("100太贵了，90卖吗")).isEqualByComparingTo("90");
    }

    @Test
    void 明显不合理的价格应被过滤() {
        assertThat(offerOf("999999块卖吗")).isNull();
        assertThat(offerOf("0块行吗")).isNull();
    }
}
