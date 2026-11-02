package com.feijimiao.xianyuassistant.service.bargain;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 议价意图识别：从买家消息中识别砍价意图与出价。
 *
 * <p>纯正则实现，不依赖大模型，保证确定性并避免额外 AI 调用。
 * 出价提取优先级：带货币单位的数字 &gt; 砍价语境中的裸数字 &gt; 让价差额（需结合商品售价换算）。</p>
 */
@Component
public class BargainDetector {

    /**
     * 砍价意图关键词：命中任意一个即视为议价消息
     */
    private static final Pattern BARGAIN_KEYWORD = Pattern.compile(
            "便宜|少点|少些|能少|减点|让点|让价|让步|优惠|打折|砍价|最低|底价|一口价|贵了|太贵|包邮|多少能拿|多少能出|多少卖|什么价|能给个价");

    /**
     * 带货币单位的出价：50块 / 50元 / 50米 / 50r / ¥50 / 50块大洋
     */
    private static final Pattern CURRENCY_PRICE = Pattern.compile(
            "(?:¥|￥)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:块|元|米|大洋|r|R)");

    /**
     * 砍价语境中的裸数字出价：50行不行 / 50可以吗 / 50能拿吗 / 100走吗 / 50？ / 60卖不卖
     */
    private static final Pattern CONTEXT_PRICE = Pattern.compile(
            "(\\d+(?:\\.\\d{1,2})?)\\s*(?:块|元|米)?\\s*(?:行不行|行吗|可以吗|好不好|好吗|怎么样|咋样|能拿|能出|能收|能卖|拿下|带走|走吗|出吗|收吗|卖吗|卖不卖|出不出|要不要|要吗|？|\\?)");

    /**
     * 货币符号前缀出价：¥60 / ￥60（无需后缀单位）
     */
    private static final Pattern CURRENCY_SYMBOL = Pattern.compile(
            "(?:¥|￥)\\s*(\\d+(?:\\.\\d{1,2})?)");

    /**
     * 让价差额：少20 / 减10 / 让5 / 便宜到50（差额需结合售价换算出价）
     */
    private static final Pattern DEDUCTION = Pattern.compile(
            "(?:少|减|让|降|便)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:块|元|米)?");

    /**
     * "便宜到X" 直接给出目标价
     */
    private static final Pattern TARGET_PRICE = Pattern.compile(
            "(?:便宜到|降到|少到|给\\s*(?:个|我)?|按)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:块|元|米)?");

    @Data
    public static class BargainIntent {
        /** 是否为议价消息 */
        private boolean bargain;
        /** 买家直接出价（元），可能为空 */
        private BigDecimal offeredPrice;
        /** 买家要求的让价差额（元），需要结合售价换算，可能为空 */
        private BigDecimal deduction;
    }

    /**
     * 识别买家消息的议价意图。
     *
     * @param message 买家消息文本
     * @return 意图结果，永不返回 null
     */
    public BargainIntent detect(String message) {
        BargainIntent intent = new BargainIntent();
        if (message == null || message.isBlank()) {
            return intent;
        }

        // 优先识别直接目标价："便宜到50" / "降到50"
        BigDecimal target = extractFirst(TARGET_PRICE, message);
        BigDecimal currency = extractLast(CURRENCY_PRICE, message);
        if (currency == null) {
            currency = extractLast(CURRENCY_SYMBOL, message);
        }
        BigDecimal context = extractLast(CONTEXT_PRICE, message);
        BigDecimal deduction = extractFirst(DEDUCTION, message);

        BigDecimal offered = target != null ? target : currency;
        if (offered == null) {
            offered = context;
        }

        boolean keywordHit = BARGAIN_KEYWORD.matcher(message).find();
        boolean hasPriceSignal = offered != null || deduction != null;

        intent.setBargain(keywordHit || (hasPriceSignal && looksLikeOffer(message, offered)));
        intent.setOfferedPrice(offered);
        intent.setDeduction(deduction);
        return intent;
    }

    /**
     * 无关键词命中时，只有裸数字出价且语境明显是报价才判定为议价，
     * 避免"3天发货"这类数字误判。
     */
    private boolean looksLikeOffer(String message, BigDecimal offered) {
        return offered != null && CONTEXT_PRICE.matcher(message).find();
    }

    private BigDecimal extractFirst(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return parsePrice(matcher.group(1));
        }
        return null;
    }

    private BigDecimal extractLast(Pattern pattern, String message) {
        BigDecimal last = null;
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            last = parsePrice(matcher.group(1));
        }
        return last;
    }

    private BigDecimal parsePrice(String value) {
        try {
            BigDecimal price = new BigDecimal(value);
            // 排除明显不是价格的数字（过大或非正数）
            if (price.signum() <= 0 || price.compareTo(new BigDecimal("999999")) >= 0) {
                return null;
            }
            return price;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
