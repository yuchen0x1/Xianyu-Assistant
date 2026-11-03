package com.feijimiao.xianyuassistant.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("job_definition")
public class JobDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobType;
    private String idempotencyKey;
    private Integer priority;
    private String status;       // PENDING / RUNNING / SUCCESS / FAILED / CANCELLED / DEAD_LETTER
    private String paramsJson;
    private Long accountId;
    private Integer maxRetries;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime nextRunTime;
    private LocalDateTime leaseUntil;
    private String leaseToken;
    private String scheduleCron;
    private Long createdBy;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
