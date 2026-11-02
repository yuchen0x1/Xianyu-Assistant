package com.feijimiao.xianyuassistant.review.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("review_record")
public class ReviewRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private Long accountId;
    private String orderId;
    private String buyerUserId;
    private Long ruleId;
    private String reviewText;
    private String status;
    private LocalDateTime scheduledAt;
    private LocalDateTime submittedAt;
    private Integer retryCount;
    private Long jobId;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
