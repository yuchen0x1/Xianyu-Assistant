package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量更新商品自动化配置响应DTO
 */
@Data
public class BatchUpdateGoodsConfigRespDTO {

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
        /** 商品ID */
        private String xyGoodsId;
        /** 失败原因 */
        private String reason;
    }
}
