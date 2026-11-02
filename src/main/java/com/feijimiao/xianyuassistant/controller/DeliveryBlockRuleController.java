package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryBlockRuleDTO;
import com.feijimiao.xianyuassistant.controller.dto.SaveDeliveryBlockRulesReqDTO;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/delivery-block-rule")
@RequirePermission("order:read")
public class DeliveryBlockRuleController {

    @Autowired
    private DeliveryBlockRuleService deliveryBlockRuleService;

    @PostMapping("/list")
    public ResultObject<List<DeliveryBlockRuleDTO>> list(@RequestParam Long xianyuAccountId) {
        return ResultObject.success(deliveryBlockRuleService.getRules(xianyuAccountId));
    }

    @PostMapping("/save")
    @RequirePermission("order:write")
    public ResultObject<List<DeliveryBlockRuleDTO>> save(@Valid @RequestBody SaveDeliveryBlockRulesReqDTO reqDTO) {
        log.info("保存发货风控规则: accountId={}, ruleCount={}",
                reqDTO.getXianyuAccountId(), reqDTO.getRules().size());
        return ResultObject.success(deliveryBlockRuleService.saveRules(
                reqDTO.getXianyuAccountId(), reqDTO.getRules()));
    }
}
