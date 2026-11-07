package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量下架商品请求 DTO。
 */
@Data
public class BatchOffShelfItemsReqDTO {

    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    /**
     * 待下架的商品ID（xyGoodsId）列表
     */
    @NotEmpty(message = "商品ID列表不能为空")
    @Size(max = 50, message = "单次最多批量下架50个商品")
    private List<String> xyGoodsIds;
}