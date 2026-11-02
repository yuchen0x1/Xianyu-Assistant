package com.feijimiao.xianyuassistant.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警事件：存储告警去重、抑制窗口与发送状态。
 */
@Data
@TableName("alert_event")
public class AlertEvent {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ACKED = "ACKED";
    public static final String STATUS_SILENCED = "SILENCED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String alertKey;
    private String title;
    private String message;
    private String level;
    private Long accountId;
    private String status;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Integer occurrences;
    private LocalDateTime lastSentAt;
    private LocalDateTime nextSendAt;
    private Integer sendAttempts;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}