package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多账号对比统计响应DTO
 */
@Data
public class AccountCompareRespDTO {

    /** 各账号统计 */
    private List<AccountStat> accounts = new ArrayList<>();

    /** 总订单数 */
    private int totalOrders;

    /** 总销售额 */
    private double totalAmount;

    /** 总AI回复数 */
    private int totalReplies;

    @Data
    public static class AccountStat {
        /** 账号ID */
        private Long accountId;
        /** 账号名称（备注或unb） */
        private String accountName;
        /** 订单数 */
        private int orderCount;
        /** 发货成功数 */
        private int successCount;
        /** 发货失败数 */
        private int failCount;
        /** AI回复数 */
        private int replyCount;
        /** 确认销售额 */
        private double amount;
    }
}
