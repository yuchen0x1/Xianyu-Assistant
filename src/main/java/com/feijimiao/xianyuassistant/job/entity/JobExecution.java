package com.feijimiao.xianyuassistant.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("job_execution")
public class JobExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Integer attempt;
    private String status;       // RUNNING / SUCCESS / FAILED
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private String resultJson;
    private String workerId;
    private LocalDateTime createdTime;
}
