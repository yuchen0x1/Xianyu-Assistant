package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.ApiDeliveryScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
@Slf4j
@RestController
@RequestMapping("/api/websocket")
@RequirePermission("order:read")
public class ApiDeliveryFallbackController {

    @Autowired
    private ApiDeliveryScheduler apiDeliveryScheduler;

    @PostMapping("/apiDeliveryFallbackStatus")
    public ResultObject<ApiDeliveryFallbackStatusRespDTO> getApiDeliveryFallbackStatus() {
        try {
            ApiDeliveryFallbackStatusRespDTO respDTO = new ApiDeliveryFallbackStatusRespDTO();
            respDTO.setEnabled(apiDeliveryScheduler.isApiFallbackEnabled());
            respDTO.setAccountIds(apiDeliveryScheduler.getApiFallbackAccountIds());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取API兜底发货状态失败", e);
            return ResultObject.failed("获取API兜底发货状态失败: " + e.getMessage());
        }
    }

    @PostMapping("/updateApiDeliveryFallback")
    @RequirePermission("order:write")
    public ResultObject<String> updateApiDeliveryFallback(@Valid @RequestBody UpdateApiDeliveryFallbackReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getAccountIds() == null) {
                return ResultObject.validateFailed("参数不完整");
            }

            apiDeliveryScheduler.setApiFallbackEnabled(Boolean.TRUE.equals(reqDTO.getEnabled()));
            apiDeliveryScheduler.setApiFallbackAccountIds(reqDTO.getAccountIds().trim());
            return ResultObject.success("API兜底发货配置已更新");
        } catch (Exception e) {
            log.error("更新API兜底发货配置失败", e);
            return ResultObject.failed("更新API兜底发货配置失败: " + e.getMessage());
        }
    }

    @lombok.Data
    public static class ApiDeliveryFallbackStatusRespDTO {
        private Boolean enabled;
        private String accountIds;
    }

    @lombok.Data
    public static class UpdateApiDeliveryFallbackReqDTO {
        private Boolean enabled;
        private String accountIds;
    }
}
