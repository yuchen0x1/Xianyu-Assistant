package com.feijimiao.xianyuassistant.risk.dto;

import lombok.Data;

/**
 * 账号自动化状态（P0-6）。
 */
@Data
public class RiskAutomationStatus {
    /** 是否被手动暂停（持久化） */
    private boolean paused;
    /** 暂停原因 */
    private String pauseReason;
    /** 认证/风控冷却剩余秒数（0=未冷却） */
    private long cooldownRemainingSeconds;
}
