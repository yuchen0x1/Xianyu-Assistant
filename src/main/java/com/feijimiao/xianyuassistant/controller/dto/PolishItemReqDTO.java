package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 擦亮单个商品请求DTO
 */
@Data
public class PolishItemReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;
}
