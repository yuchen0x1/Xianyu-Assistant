package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KamiItemQueryReqDTO {
    
    @NotNull(message = "卡密配置ID不能为空")
    private Long kamiConfigId;
    
    private Integer status;

    /** 只看预占待确认（status=1 且未写入交付时间）的卡密。 */
    private Boolean pendingOnly;

    private String keyword;
}
