package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存配置请求DTO
 */
@Data
public class SaveSettingReqDTO {
    @NotBlank(message = "配置键不能为空")
    @Size(max = 100, message = "配置键长度不能超过100")
    private String settingKey;

    @Size(max = 4000, message = "配置值长度不能超过4000")
    private String settingValue;

    @Size(max = 200, message = "配置描述长度不能超过200")
    private String settingDesc;

    private Boolean clearSecret;
}
