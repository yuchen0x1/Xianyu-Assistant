package com.feijimiao.xianyuassistant.controller.dto;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import lombok.Data;

@Data
public class ItemWithConfigDTO {
    
    private XianyuGoodsInfo item;
    
    private Integer xianyuAutoDeliveryOn;
    
    private Integer xianyuAutoReplyOn;
    
    private Integer xianyuAutoReplyContextOn;
    
    private Integer xianyuKeywordReplyOn;

    private Integer humanInterventionOn;

    private Integer humanInterventionMinutes;

    private Integer replyManualConfirmOn;

    private Integer bargainOn;

    private String bargainFloorPrice;

    private String bargainStepPrice;

    private Integer bargainMaxRounds;

    private Integer autoDeliveryType;

    private String autoDeliveryContent;
}
