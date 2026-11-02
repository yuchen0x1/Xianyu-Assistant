package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量更新商品自动化配置请求DTO
 */
@Data
public class BatchUpdateGoodsConfigReqDTO {

    /** 闲鱼账号ID */
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;

    /** 商品ID列表 */
    @NotEmpty(message = "商品ID列表不能为空")
    private List<String> xyGoodsIds;

    /** 自动发货开关：1-开启，0-关闭（可选，不传则不改） */
    private Integer xianyuAutoDeliveryOn;

    /** 自动回复开关：1-开启，0-关闭（可选，不传则不改） */
    private Integer xianyuAutoReplyOn;

    /** 携带上下文开关：1-开启，0-关闭（可选，仅开启自动回复时生效） */
    private Integer xianyuAutoReplyContextOn;
}
