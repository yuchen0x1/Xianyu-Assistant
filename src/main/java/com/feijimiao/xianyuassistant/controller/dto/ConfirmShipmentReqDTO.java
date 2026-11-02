package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认发货请求DTO
 */
@Data
public class ConfirmShipmentReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "订单ID不能为空")
    private String orderId;
}
