package com.feijimiao.xianyuassistant.controller.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品价格趋势展示VO
 */
@Data
public class PriceHistoryTrendVO {
    /**
     * 日期（yyyy-MM-dd）
     */
    private String date;

    /**
     * 当日价格
     */
    private BigDecimal price;
}
