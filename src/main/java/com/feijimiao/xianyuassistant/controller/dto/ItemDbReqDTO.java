package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 从数据库获取商品请求DTO
 */
@Data
public class ItemDbReqDTO {
    /**
     * 账号ID
     */
    @NotBlank(message = "账号ID不能为空")
    private String cookieId;
}
