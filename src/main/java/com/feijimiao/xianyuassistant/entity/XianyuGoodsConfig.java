package com.feijimiao.xianyuassistant.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商品配置实体类
 */
@Data
public class XianyuGoodsConfig {
    
    /**
     * 主键ID
     */
    private Long id;
    
    /**
     * 闲鱼账号ID
     */
    private Long xianyuAccountId;
    
    /**
     * 本地闲鱼商品ID
     */
    private Long xianyuGoodsId;
    
    /**
     * 闲鱼的商品ID
     */
    private String xyGoodsId;
    
    /**
     * 自动发货开关：1-开启，0-关闭，默认关闭
     */
    private Integer xianyuAutoDeliveryOn;
    
    /**
     * 自动回复开关：1-开启，0-关闭，默认关闭
     */
    private Integer xianyuAutoReplyOn;
    
    /**
     * 携带上下文开关：1-开启，0-关闭，默认开启，跟随自动回复开关
     */
    private Integer xianyuAutoReplyContextOn;
    
    private Integer xianyuKeywordReplyOn;

    /**
     * 人工干预开关：1-开启，0-关闭，默认关闭
     * 开启后延时任务到期时若卖家已人工回复则取消自动回复
     */
    private Integer humanInterventionOn;

    private Integer humanInterventionMinutes;
    
    /**
     * AI回复人工确认模式：1-开启，0-关闭，默认关闭
     * 开启后AI生成草稿，不自动发送，等待人工确认/编辑后再发送
     */
    private Integer replyManualConfirmOn;
    
    /**
     * 固定资料（用于AI自动回复）
     */
    private String fixedMaterial;

    /**
     * 智能议价开关：1-开启，0-关闭，默认关闭
     */
    private Integer bargainOn;

    /**
     * 议价保底价（元），买家出价不低于此价即可成交
     */
    private String bargainFloorPrice;

    /**
     * 每轮让价步长（元），为空时第一轮直接给到保底价
     */
    private String bargainStepPrice;

    /**
     * 最大议价轮数，超过后坚持保底价不再让步
     */
    private Integer bargainMaxRounds;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}


