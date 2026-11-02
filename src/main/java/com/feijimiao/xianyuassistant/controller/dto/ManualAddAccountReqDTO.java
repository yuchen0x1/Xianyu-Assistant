package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 手动添加账号请求DTO
 */
@Data
public class ManualAddAccountReqDTO {
    private String accountNote;   // 账号备注

    @NotBlank(message = "Cookie不能为空")
    private String cookie;        // Cookie字符串
}
