package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KamiExportReqDTO {
    @NotNull(message = "卡密配置ID不能为空")
    private Long kamiConfigId;
    private Boolean includeUnused;
    private Boolean includeUsed;
}
