package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 定时任务请求 DTO
 */
@Data
public class ScheduleTaskReqDTO {

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    /** SYNC_GOODS / OFF_SHELF / PUBLISH */
    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    /** 标准 5 段 Cron 表达式 */
    @NotBlank(message = "Cron表达式不能为空")
    private String cronExpr;

    /** 关联账号ID（同步、发布、下架任务均必填） */
    private Long accountId;

    /** 任务配置 JSON（OFF_SHELF: {"itemIds":["..."]}；PUBLISH: PublishItemReqDTO JSON） */
    private String configJson;

    /** 是否启用 */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
