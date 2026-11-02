package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KamiConfigRespDTO {

    private Long id;

    private Long xianyuAccountId;

    private String aliasName;

    private Integer alertEnabled;

    private Integer alertThresholdType;

    private Integer alertThresholdValue;

    private String alertEmail;

    private Integer totalCount;

    private Integer usedCount;

    private Integer availableCount;

    /** 已占用但尚未确认交付的卡密数量（需人工或巡检核对）。 */
    private Integer pendingCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
