package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 失败原因排行响应DTO（按 error_message 聚合）
 */
@Data
public class FailureRankRespDTO {

    /**
     * 失败原因明细列表
     */
    private List<FailItem> items;

    /**
     * 总失败次数
     */
    private long totalFailures;

    @Data
    public static class FailItem {
        /** 失败原因 */
        private String reason;
        /** 失败次数 */
        private long failCount;
        /** 涉及的操作类型（逗号分隔，如 AUTO_DELIVERY,TOKEN_REFRESH） */
        private String relatedTypes;
    }
}
