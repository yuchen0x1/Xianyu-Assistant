package com.feijimiao.xianyuassistant.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("job_dead_letter")
public class JobDeadLetter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private String jobType;
    private String idempotencyKey;
    private String paramsJson;
    private Long accountId;
    private Integer maxRetries;
    private Integer finalRetryCount;
    private String lastError;
    private LocalDateTime deadAt;
    private Integer resolved;       // 0:未处理 1:已处理
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionNote;
}
