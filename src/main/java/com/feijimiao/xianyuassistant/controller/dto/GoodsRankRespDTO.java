package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 商品排行响应DTO
 */
@Data
public class GoodsRankRespDTO {

    /**
     * 成交排行（按订单数）
     */
    private List<RankItem> orderRank;

    /**
     * 咨询排行（按消息数）
     */
    private List<RankItem> inquiryRank;

    @Data
    public static class RankItem {
        /** 商品ID */
        private String goodsId;
        /** 商品标题 */
        private String goodsTitle;
        /** 数量（订单数/消息数） */
        private long count;
        /** 金额（仅成交排行有值） */
        private double amount;
    }
}
