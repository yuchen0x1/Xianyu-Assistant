package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.dto.DeliveryBlockRuleDTO;

import java.util.List;

public interface DeliveryBlockRuleService {
    List<DeliveryBlockRuleDTO> getRules(Long accountId);

    List<DeliveryBlockRuleDTO> saveRules(Long accountId, List<DeliveryBlockRuleDTO> rules);

    DeliveryBlockDecision evaluate(Long accountId, String xyGoodsId, String orderId, String buyerUserId);
}
