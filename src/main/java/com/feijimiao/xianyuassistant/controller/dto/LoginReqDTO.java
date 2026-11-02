package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求DTO
 */
@Data
public class LoginReqDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 50, message = "用户名长度2-50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 5, max = 64, message = "密码长度5-64")
    private String password;
}
