package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.risk.dto.RiskAutomationStatus;
import com.feijimiao.xianyuassistant.service.RiskControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 账号级自动化风控接口（P0-6）。
 */
@Slf4j
@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskController {

    private final RiskControlService riskControlService;

    /**
     * 查询账号自动化状态（是否暂停、冷却剩余时间）。
     */
    @GetMapping("/accounts/{accountId}")
    @RequirePermission("account:read")
    public ResultObject<RiskAutomationStatus> getAutomationStatus(@PathVariable Long accountId) {
        return ResultObject.success(riskControlService.getAutomationStatus(accountId));
    }

    /**
     * 手动暂停账号自动化（持久化，重启后仍生效）。
     */
    @PostMapping("/accounts/{accountId}/pause")
    @RequirePermission("account:write")
    public ResultObject<Void> pauseAutomation(@PathVariable Long accountId,
                                              @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        riskControlService.pauseAutomation(accountId, reason);
        return ResultObject.success(null);
    }

    /**
     * 手动恢复账号自动化（同时清除认证/风控冷却）。
     */
    @PostMapping("/accounts/{accountId}/resume")
    @RequirePermission("account:write")
    public ResultObject<Void> resumeAutomation(@PathVariable Long accountId) {
        riskControlService.resumeAutomation(accountId);
        return ResultObject.success(null);
    }
}
