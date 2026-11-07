package com.feijimiao.xianyuassistant.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量下架商品响应 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchOffShelfItemsRespDTO {

    private Integer total;

    private Integer success;

    private Integer failed;

    private List<ItemOffShelfResult> details;

    /**
     * 单个商品的下架结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemOffShelfResult {
        private String xyGoodsId;
        private Boolean success;
        private String message;
    }
}