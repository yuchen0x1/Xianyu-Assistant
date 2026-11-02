package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新Cookie请求DTO
 */
@Data
public class UpdateCookieReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long xianyuAccountId;

    @NotBlank(message = "Cookie不能为空")
    @Size(max = 8192, message = "Cookie长度过长")
    private String cookieText;
}
