package com.feijimiao.xianyuassistant.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("delivery_compensation")
public class DeliveryCompensation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderId;
    private Long xianyuAccountId;
    private String xyGoodsId;
    private String deliveryType;
    private String deliveryContent;
    private String phase;           // PRE_COMMIT / COMMITTED / CONFIRMED / UNKNOWN / RESOLVED
    private String idempotencyKey;
    private String externalRequestId;
    private LocalDateTime unknownSince;
    private String resolvedBy;      // MANUAL / AUTO_RETRY / CANCELLED
    private LocalDateTime resolvedAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
