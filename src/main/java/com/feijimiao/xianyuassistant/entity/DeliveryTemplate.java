package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 有序多段自动发货模板。
 *
 * <p>模板只保存账号级配置，具体消息顺序由 {@link DeliveryTemplateMessage#sortOrder}
 * 决定。旧的单段自动发货配置不受影响。</p>
 */
@Data
@TableName("xianyu_delivery_template")
public class DeliveryTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long xianyuAccountId;

    private String name;

    private Integer enabled = 1;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField(exist = false)
    private List<DeliveryTemplateMessage> messages = new ArrayList<>();
}
