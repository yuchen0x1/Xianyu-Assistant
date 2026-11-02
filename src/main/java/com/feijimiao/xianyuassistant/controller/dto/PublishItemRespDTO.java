package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品发布响应
 */
@Data
public class PublishItemRespDTO {

    /** 是否发布成功 */
    private boolean success;

    /** 发布成功的商品ID */
    private String itemId;

    /** 提示信息 */
    private String message;

    /** AI 类目识别结果（仅 recommendCategoryOnly=true 时返回） */
    private List<CategoryPredict> categories;

    @Data
    public static class CategoryPredict {
        private String categoryId;
        private String categoryName;
        private double score;
    }

    /** 发布后价格（展示用） */
    private BigDecimal price;
}
