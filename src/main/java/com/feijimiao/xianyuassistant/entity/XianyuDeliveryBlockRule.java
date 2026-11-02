package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("xianyu_delivery_block_rule")
public class XianyuDeliveryBlockRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long xianyuAccountId;
    private String ruleCode;
    private Integer enabled;
    private Integer priority;
    private String blockReason;
    private String excludedItemIds;
    private String configJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
