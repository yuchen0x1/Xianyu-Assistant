package com.feijimiao.xianyuassistant.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.feijimiao.xianyuassistant.security.EncryptedStringTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value = "webhook_config", autoResultMap = true)
public class WebhookConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String url;
    private String events;           // JSON array
    private Integer enabled;
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String secret;
    private Integer retryCount;
    private Integer timeoutSeconds;
    private LocalDateTime lastTriggerAt;
    private String lastTriggerStatus;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
