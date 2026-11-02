package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新自动回复配置请求DTO
 * @author IAMLZY
 * @date 2026/4/22
 */
@Data
public class UpdateRagAutoReplyConfigReqDTO {
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;  

    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;

    /** 回复延时秒数 */
    @Min(value = 0, message = "回复延时不能小于0")
    @Max(value = 3600, message = "回复延时不能超过3600秒")
    private Integer ragDelaySeconds;
}
