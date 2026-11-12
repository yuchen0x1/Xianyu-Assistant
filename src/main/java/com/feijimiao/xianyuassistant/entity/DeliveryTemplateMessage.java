package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 有序多段自动发货模板消息。当前支持 TEXT，预留 IMAGE 类型供后续扩展。 */
@Data
@TableName("xianyu_delivery_template_message")
public class DeliveryTemplateMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Integer sortOrder;

    private String messageType = "TEXT";

    private String content;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
