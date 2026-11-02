package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchCopyConfigReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.BatchUpdateGoodsConfigRespDTO;
import com.feijimiao.xianyuassistant.service.AutoDeliveryConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/auto-delivery-config")
@RequirePermission("auto-delivery:read")
public class AutoDeliveryConfigController {

    @Autowired
    private AutoDeliveryConfigService autoDeliveryConfigService;

    @PostMapping("/save")
    @RequirePermission("auto-delivery:write")
    public ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(@Valid @RequestBody AutoDeliveryConfigReqDTO reqDTO) {
        try {
            log.info("保存自动发货配置请求: xianyuAccountId={}, xyGoodsId={}, skuId={}, deliveryMode={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getSkuId(), reqDTO.getDeliveryMode());
            return autoDeliveryConfigService.saveOrUpdateConfig(reqDTO);
        } catch (Exception e) {
            log.error("保存自动发货配置失败", e);
            return ResultObject.failed("保存自动发货配置失败: " + e.getMessage());
        }
    }

    @PostMapping("/get")
    public ResultObject<AutoDeliveryConfigRespDTO> getConfig(@Valid @RequestBody AutoDeliveryConfigQueryReqDTO reqDTO) {
        try {
            log.info("查询自动发货配置请求: xianyuAccountId={}, xyGoodsId={}, skuId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getSkuId());
            return autoDeliveryConfigService.getConfig(reqDTO);
        } catch (Exception e) {
            log.error("查询自动发货配置失败", e);
            return ResultObject.failed("查询自动发货配置失败: " + e.getMessage());
        }
    }

    @PostMapping("/listByGoods")
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(@Valid @RequestBody AutoDeliveryConfigQueryReqDTO reqDTO) {
        try {
            log.info("查询商品自动发货配置列表请求: xianyuAccountId={}, xyGoodsId={}", 
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
            return autoDeliveryConfigService.getConfigsByGoodsId(reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId());
        } catch (Exception e) {
            log.error("查询商品自动发货配置列表失败", e);
            return ResultObject.failed("查询商品自动发货配置列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/list")
    public ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(@RequestParam("xianyuAccountId") Long xianyuAccountId) {
        try {
            log.info("查询账号自动发货配置列表请求: xianyuAccountId={}", xianyuAccountId);
            return autoDeliveryConfigService.getConfigsByAccountId(xianyuAccountId);
        } catch (Exception e) {
            log.error("查询账号自动发货配置列表失败", e);
            return ResultObject.failed("查询账号自动发货配置列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/delete")
    @RequirePermission("auto-delivery:write")
    public ResultObject<Void> deleteConfig(@RequestParam("xianyuAccountId") Long xianyuAccountId,
                                           @RequestParam("xyGoodsId") String xyGoodsId) {
        try {
            log.info("删除自动发货配置请求: xianyuAccountId={}, xyGoodsId={}", xianyuAccountId, xyGoodsId);
            return autoDeliveryConfigService.deleteConfig(xianyuAccountId, xyGoodsId);
        } catch (Exception e) {
            log.error("删除自动发货配置失败", e);
            return ResultObject.failed("删除自动发货配置失败: " + e.getMessage());
        }
    }

    /**
     * 批量复制配置：将参考商品的基础配置套用到多个目标商品
     */
    @PostMapping("/batchCopy")
    @RequirePermission("auto-delivery:write")
    public ResultObject<BatchUpdateGoodsConfigRespDTO> batchCopyConfig(@Valid @RequestBody BatchCopyConfigReqDTO reqDTO) {
        try {
            log.info("批量复制配置请求: xianyuAccountId={}, sourceXyGoodsId={}, 目标数={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getSourceXyGoodsId(),
                    reqDTO.getTargetXyGoodsIds() != null ? reqDTO.getTargetXyGoodsIds().size() : 0);
            return autoDeliveryConfigService.batchCopyConfig(reqDTO);
        } catch (Exception e) {
            log.error("批量复制配置失败", e);
            return ResultObject.failed("批量复制配置失败: " + e.getMessage());
        }
    }
}

