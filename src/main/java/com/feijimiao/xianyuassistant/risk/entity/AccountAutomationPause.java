package com.feijimiao.xianyuassistant.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号自动化手动暂停状态（P0-6，持久化，进程重启后仍生效）。
 */
@Data
@TableName("account_automation_pause")
public class AccountAutomationPause {
    @TableId(type = IdType.INPUT)
    private Long accountId;
    private Integer paused;
    private String pauseReason;
    private LocalDateTime pausedAt;
    private Long pausedBy;
    private LocalDateTime updatedTime;
}
