package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务实体（定时同步商品 / 发布 / 下架）
 */
@Data
@TableName("xianyu_schedule_task")
public class XianyuScheduleTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 任务类型 SYNC_GOODS / OFF_SHELF / PUBLISH */
    private String taskType;

    /** Cron 表达式 */
    private String cronExpr;

    /** 关联账号ID */
    private Long accountId;

    /** 任务配置（JSON） */
    private String configJson;

    /** 是否启用 1启用 0禁用 */
    private Integer enabled;

    /** 是否因连续失败自动暂停 1暂停 */
    private Integer paused;

    /** 连续失败次数 */
    private Integer failCount;

    /** 最近一次执行时间 */
    private LocalDateTime lastRunTime;

    /** 最近一次执行状态 SUCCESS / FAILED */
    private String lastRunStatus;

    /** 最近一次失败原因 */
    private String lastError;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
