package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量复制自动发货配置请求DTO
 */
@Data
public class BatchCopyConfigReqDTO {

    /** 闲鱼账号ID */
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    /** 参考商品ID（配置来源） */
    @NotBlank(message = "参考商品不能为空")
    private String sourceXyGoodsId;

    /** 目标商品ID列表 */
    @NotEmpty(message = "目标商品列表不能为空")
    private List<String> targetXyGoodsIds;
}
