package com.feijimiao.xianyuassistant.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Webhook 投递历史记录（V3_17）。
 * payload_json 为事件最小 payload，禁止写入 Cookie/Token/密钥。
 */
@Data
@TableName("webhook_delivery")
public class WebhookDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long webhookId;
    private String event;
    private String payloadJson;
    private String status;          // PENDING / SUCCESS / FAILED / UNKNOWN
    private Integer httpStatus;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private String idempotencyKey;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdTime;
}