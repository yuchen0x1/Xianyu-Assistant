package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ManualDeliveryReqDTO {
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "订单ID不能为空")
    private String orderId;

    @NotBlank(message = "发货内容不能为空")
    private String content;
}
