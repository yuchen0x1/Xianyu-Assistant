package com.feijimiao.xianyuassistant.webhook.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Webhook 投递历史响应 DTO：只暴露脱敏摘要，不返回 payload 明文。
 */
@Data
public class WebhookDeliveryVO {
    private Long id;
    private Long webhookId;
    private String event;
    private String status;            // PENDING / SUCCESS / FAILED / UNKNOWN
    private Integer httpStatus;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private String lastError;         // 脱敏摘要（截断 500 字符）
    private String payloadSummary;    // 脱敏摘要：payload 字段名列表，不含值
    private LocalDateTime deliveredAt;
    private LocalDateTime createdTime;
}