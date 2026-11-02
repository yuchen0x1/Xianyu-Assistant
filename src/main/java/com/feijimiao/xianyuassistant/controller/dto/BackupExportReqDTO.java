package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BackupExportReqDTO {
    @NotEmpty(message = "导出模块不能为空")
    private List<String> modules;

    /**
     * 是否包含明文敏感字段（Cookie/Token/API Key/SMTP 密码）。
     * 默认 false：敏感字段导出为 null，恢复后需重新登录/配置。
     */
    private Boolean includeSecrets;
}
