package com.feijimiao.xianyuassistant.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 擦亮结果响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolishItemRespDTO {
    private int total;
    private int success;
    private int failed;
    private List<ItemPolishResult> details;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ItemPolishResult {
        private String xyGoodsId;
        private boolean success;
        private String message;
    }
}
