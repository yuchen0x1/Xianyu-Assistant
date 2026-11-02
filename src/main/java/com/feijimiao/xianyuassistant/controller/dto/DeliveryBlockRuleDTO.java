package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class DeliveryBlockRuleDTO {
    private String ruleCode;
    private String ruleName;
    private String ruleDescription;
    private Integer enabled;
    private Integer priority;
    private String blockReason;
    private List<String> excludedItemIds;
    private Map<String, Object> config;
}
