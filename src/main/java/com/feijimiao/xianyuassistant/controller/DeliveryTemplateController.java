package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryTemplateSaveReqDTO;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.DeliveryTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 有序多段自动发货模板接口。 */
@RestController
@RequestMapping("/api/delivery-templates")
@RequirePermission("auto-delivery:read")
@RequiredArgsConstructor
public class DeliveryTemplateController {

    private final DeliveryTemplateService deliveryTemplateService;

    @GetMapping("/list")
    public ResultObject<List<DeliveryTemplateRespDTO>> list(
            @RequestParam(required = false) Long xianyuAccountId,
            @RequestParam(required = false) Long accountId) {
        return ResultObject.success(deliveryTemplateService.list(accountIdOf(xianyuAccountId, accountId)));
    }

    @GetMapping("/get")
    public ResultObject<DeliveryTemplateRespDTO> get(
            @RequestParam(required = false) Long xianyuAccountId,
            @RequestParam(required = false) Long accountId,
            @RequestParam Long templateId) {
        return ResultObject.success(deliveryTemplateService.get(accountIdOf(xianyuAccountId, accountId), templateId));
    }

    @PostMapping("/save")
    @RequirePermission("auto-delivery:write")
    public ResultObject<DeliveryTemplateRespDTO> save(@Valid @RequestBody DeliveryTemplateSaveReqDTO request) {
        return ResultObject.success(deliveryTemplateService.save(request));
    }

    @DeleteMapping("/delete")
    @RequirePermission("auto-delivery:write")
    public ResultObject<Void> delete(@RequestParam(required = false) Long xianyuAccountId,
                                     @RequestParam(required = false) Long accountId,
                                     @RequestParam(required = false) Long templateId,
                                     @RequestParam(required = false) Long id) {
        Long effectiveAccountId = accountIdOf(xianyuAccountId, accountId);
        Long effectiveId = templateId != null ? templateId : id;
        if (effectiveId == null) {
            throw new com.feijimiao.xianyuassistant.exception.BusinessException("模板ID不能为空");
        }
        deliveryTemplateService.delete(effectiveAccountId, effectiveId);
        return ResultObject.success(null);
    }

    private Long accountIdOf(Long xianyuAccountId, Long accountId) {
        Long effective = xianyuAccountId != null ? xianyuAccountId : accountId;
        if (effective == null) {
            throw new com.feijimiao.xianyuassistant.exception.BusinessException("闲鱼账号ID不能为空");
        }
        return effective;
    }
}
