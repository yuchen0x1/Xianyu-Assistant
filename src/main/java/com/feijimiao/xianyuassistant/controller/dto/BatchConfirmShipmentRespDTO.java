package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量确认发货响应DTO
 */
@Data
public class BatchConfirmShipmentRespDTO {

    /** 总条数 */
    private int total;

    /** 成功条数 */
    private int successCount;

    /** 失败条数 */
    private int failCount;

    /** 失败明细 */
    private List<FailItem> failures = new ArrayList<>();

    @Data
    public static class FailItem {
        /** 订单ID */
        private String orderId;
        /** 失败原因 */
        private String reason;
    }
}
