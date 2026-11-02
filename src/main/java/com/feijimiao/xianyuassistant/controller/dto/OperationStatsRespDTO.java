package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 操作审计统计响应DTO（按操作类型聚合）
 */
@Data
public class OperationStatsRespDTO {

    /**
     * 各操作类型统计明细
     */
    private List<TypeStat> types;

    /**
     * 总操作次数
     */
    private long totalOperations;

    /**
     * 总成功次数
     */
    private long totalSuccess;

    /**
     * 总失败次数
     */
    private long totalFail;

    /**
     * 总部分成功次数
     */
    private long totalPartial;

    /**
     * 整体成功率（0-100，保留1位小数）
     */
    private double successRate;

    @Data
    public static class TypeStat {
        /** 操作类型（原始值，如 AUTO_DELIVERY） */
        private String type;
        /** 总次数 */
        private long totalCount;
        /** 成功数 */
        private long successCount;
        /** 失败数 */
        private long failCount;
        /** 部分成功数 */
        private long partialCount;
        /** 平均耗时（毫秒） */
        private double avgDurationMs;
        /** 成功率（0-100，保留1位小数） */
        private double successRate;
    }
}
