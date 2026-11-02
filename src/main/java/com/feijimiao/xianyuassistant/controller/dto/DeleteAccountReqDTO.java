package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除账号请求DTO
 */
@Data
public class DeleteAccountReqDTO {
    @NotNull(message = "账号ID不能为空")
    private Long accountId;
}
