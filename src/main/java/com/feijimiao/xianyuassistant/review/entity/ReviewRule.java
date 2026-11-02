package com.feijimiao.xianyuassistant.review.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("review_rule")
public class ReviewRule {
    @TableId(type = IdType.AUTO) private Long id;
    private Long accountId;
    private Integer enabled;
    private Integer delaySeconds;
    private String template;
    private Integer requireManualConfirm;
    private Integer maxRetries;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
