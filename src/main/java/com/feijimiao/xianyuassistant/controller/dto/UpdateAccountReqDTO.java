package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新账号请求DTO
 */
@Data
public class UpdateAccountReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long accountId;

    @Size(max = 100, message = "账号备注长度不能超过100")
    private String accountNote;
}
