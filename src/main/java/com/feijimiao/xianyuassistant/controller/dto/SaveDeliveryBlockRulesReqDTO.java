package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SaveDeliveryBlockRulesReqDTO {

    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    @Valid
    @NotNull(message = "规则列表不能为空")
    private List<DeliveryBlockRuleDTO> rules;
}
