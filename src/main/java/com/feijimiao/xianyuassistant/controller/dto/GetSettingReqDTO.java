package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取配置请求DTO
 * @author IAMLZY
 * @date 2026/4/22
 */
@Data
public class GetSettingReqDTO {
    @NotBlank(message = "配置Key不能为空")
    private String settingKey;
}
