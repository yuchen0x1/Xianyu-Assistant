package com.feijimiao.xianyuassistant.webhook.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Webhook 对外响应 DTO：不返回明文 secret，仅暴露是否已配置。
 */
@Data
public class WebhookVO {
    private Long id;
    private String name;
    private String url;
    private String events;           // JSON array
    private Integer enabled;
    private Boolean hasSecret;       // 是否已配置签名密钥（明文 secret 永不返回）
    private Integer retryCount;
    private Integer timeoutSeconds;
    private LocalDateTime lastTriggerAt;
    private String lastTriggerStatus;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
