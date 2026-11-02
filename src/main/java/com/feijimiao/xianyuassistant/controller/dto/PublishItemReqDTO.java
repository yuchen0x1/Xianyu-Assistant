package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品发布请求
 */
@Data
public class PublishItemReqDTO {

    @NotNull(message = "账号ID不能为空")
    private Long accountId;

    @NotBlank(message = "商品标题不能为空")
    private String title;

    /** 商品描述（可选） */
    private String description;

    @NotNull(message = "商品价格不能为空")
    private BigDecimal price;

    /** 库存数量，默认 1 */
    private Integer quantity = 1;

    /** 图片CDN URL列表（需先通过图片上传接口获得） */
    private List<String> images;

    /** 类目ID（可选；不传时由 AI 类目识别自动推荐） */
    private String categoryId;

    /** 运费（元），默认 0 */
    private BigDecimal postFee = BigDecimal.ZERO;

    /** 是否返回 AI 类目识别结果用于前端预览（默认 false） */
    private Boolean recommendCategoryOnly = false;
}
