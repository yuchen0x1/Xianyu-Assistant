package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 自动回复配置请求DTO
 * @author IAMLZY
 * @date 2026/4/22
 */
@Data
public class RagAutoReplyConfigReqDTO {
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;
}
