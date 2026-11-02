package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新自动回复状态请求DTO
 */
@Data
public class UpdateAutoReplyReqDTO {
    
    /**
     * 闲鱼账号ID
     */
    @NotNull(message = "闲鱼账号ID不能为空")
    private Long xianyuAccountId;
    
    /**
     * 闲鱼商品ID
     */
    @NotBlank(message = "商品ID不能为空")
    private String xyGoodsId;
    
    /**
     * 自动回复开关：1-开启，0-关闭
     */
    private Integer xianyuAutoReplyOn;
    
    /**
     * 携带上下文开关：1-开启，0-关闭（可选，仅当xianyuAutoReplyOn=1时有意义）
     */
    private Integer xianyuAutoReplyContextOn;

    private Integer xianyuKeywordReplyOn;

    /**
     * 人工干预开关：1-开启，0-关闭
     */
    private Integer humanInterventionOn;

    private Integer humanInterventionMinutes;

    /**
     * AI回复人工确认模式：1-开启，0-关闭。开启后AI生成草稿等待人工确认
     */
    private Integer replyManualConfirmOn;

    /**
     * 智能议价开关：1-开启，0-关闭
     */
    private Integer bargainOn;

    /**
     * 议价保底价（元），买家出价不低于此价即可成交
     */
    private String bargainFloorPrice;

    /**
     * 每轮让价步长（元），为空时按均分让价
     */
    private String bargainStepPrice;

    /**
     * 最大议价轮数
     */
    private Integer bargainMaxRounds;
}
