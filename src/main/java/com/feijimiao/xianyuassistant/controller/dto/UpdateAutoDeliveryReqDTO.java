package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新自动发货状态请求DTO
 */
@Data
public class UpdateAutoDeliveryReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;

    @NotNull(message = "开关值不能为空")
    private Integer xianyuAutoDeliveryOn;
}
