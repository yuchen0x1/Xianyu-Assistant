package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KamiItemRespDTO {

    private Long id;

    private Long kamiConfigId;

    private String kamiContent;

    private Integer status;

    private String orderId;

    private LocalDateTime usedTime;

    /** 交付确认时间；为空且 status=1 时表示预占中、发货结果未确认。 */
    private LocalDateTime deliveredTime;

    /** 展示用状态：UNUSED-未使用，PENDING_CONFIRM-预占待确认，DELIVERED-已交付。 */
    private String reservationState;

    private Integer sortOrder;

    private LocalDateTime createTime;
}
