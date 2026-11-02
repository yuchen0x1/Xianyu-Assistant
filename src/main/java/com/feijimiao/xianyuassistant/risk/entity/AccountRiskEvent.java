package com.feijimiao.xianyuassistant.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号级风控事件记录（P0-6 风险事件中心）。
 */
@Data
@TableName("account_risk_event")
public class AccountRiskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String eventType;
    private String reason;
    private LocalDateTime occurredAt;
    private LocalDateTime createdTime;
}
