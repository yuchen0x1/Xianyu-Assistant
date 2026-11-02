package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 关键词命中排行响应DTO
 */
@Data
public class KeywordRankRespDTO {

    /**
     * 关键词命中明细列表
     */
    private List<RankItem> items;

    /**
     * 总命中次数
     */
    private long totalHits;

    @Data
    public static class RankItem {
        /** 匹配的关键词 */
        private String keyword;
        /** 命中次数 */
        private long hitCount;
        /** 回复成功数 */
        private long successCount;
        /** 回复失败数 */
        private long failCount;
        /** 待回复数 */
        private long pendingCount;
        /** 涉及买家数 */
        private long buyerCount;
    }
}
