package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 获取账号详情请求DTO
 */
@Data
public class GetAccountDetailReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long accountId;       // 账号ID
}
