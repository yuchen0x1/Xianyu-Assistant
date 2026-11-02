package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("xianyu_kami_item")
public class XianyuKamiItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kamiConfigId;

    private String kamiContent;

    private Integer status;

    private String orderId;

    private LocalDateTime usedTime;

    /**
     * 交付确认时间。status=1 且该字段为空表示「预占中，发货结果未确认」，
     * 需要由滞留巡检或人工核对后转为已交付或释放回库存。
     */
    private LocalDateTime deliveredTime;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime = LocalDateTime.now();
}
