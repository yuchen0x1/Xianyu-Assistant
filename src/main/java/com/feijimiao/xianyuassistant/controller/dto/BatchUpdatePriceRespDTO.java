package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量更新本地商品售价响应。
 */
@Data
public class BatchUpdatePriceRespDTO {

    private int total;
    private int success;
    private int failed;
    private List<ItemPriceResult> details = new ArrayList<>();

    @Data
    public static class ItemPriceResult {
        private String xyGoodsId;
        private boolean success;
        private String soldPrice;
        private String message;
    }
}
