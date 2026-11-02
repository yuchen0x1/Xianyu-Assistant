package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商品下架请求
 */
@Data
public class OffShelfReqDTO {

    @NotNull(message = "账号ID不能为空")
    private Long accountId;

    @NotBlank(message = "商品ID不能为空")
    private String itemId;
}
