package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除商品请求DTO
 */
@Data
public class DeleteItemReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;
}
