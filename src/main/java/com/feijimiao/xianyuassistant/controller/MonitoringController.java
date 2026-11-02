package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.monitor.AlertService;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.job.mapper.JobDeadLetterMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控中心控制器：告警列表、确认/静默/恢复操作与概览汇总。
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequirePermission("monitoring:read")
public class MonitoringController {

    @Autowired
    private AlertService alertService;

    @Autowired(required = false)
    private JobDeadLetterMapper deadLetterMapper;

    @Autowired(required = false)
    private EmailNotifyService emailNotifyService;

    @Autowired
    private RbacService rbacService;

    private List<Long> visibleAccountIds() {
        return rbacService.getCommandVisibleAccountIds(UserContext.getUserId());
    }

    /** 受限用户只看可见账号的告警（账号为空的系统级告警对所有用户可见）。 */
    private List<Long> alertVisibleAccountIds() {
        return rbacService.getVisibleAccountIds(UserContext.getUserId());
    }

    /** 显式传入 accountId 时按可见范围校验，空参对受限用户拒绝。 */
    private void assertParamVisible(Long accountId) {
        rbacService.assertAccountVisible(
                com.feijimiao.xianyuassistant.context.UserContext.getUserId(), accountId);
    }

    @GetMapping("/alerts")
    public ResultObject<List<AlertEvent>> listAlerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            List<Long> visible = alertVisibleAccountIds();
            if (accountId != null) {
                assertParamVisible(accountId);
                return ResultObject.success(alertService.listByScope(status, accountId, visible, limit));
            }
            return ResultObject.success(alertService.listByScope(status, null, visible, limit));
        } catch (Exception e) {
            log.error("查询告警列表失败", e);
            return ResultObject.failed("查询告警列表失败");
        }
    }

    /** 支持 GET /alerts?key=... 单条详情以及 POST 操作：ack / silence / unsilence / resolve */
    @GetMapping("/alerts/key")
    public ResultObject<AlertEvent> getAlert(@RequestParam("key") String key) {
        try {
            AlertEvent found = alertService.getByKeyInScope(key, alertVisibleAccountIds());
            return found != null ? ResultObject.success(found) : ResultObject.failed("告警不存在");
        } catch (Exception e) {
            log.error("查询告警失败: key={}", key, e);
            return ResultObject.failed("查询告警失败");
        }
    }

    @PostMapping("/alerts")
    @RequirePermission("monitoring:write")
    public ResultObject<AlertEvent> operateAlert(@RequestBody Map<String, Object> body) {
        try {
            Object keyObj = body.get("key");
            if (keyObj == null) {
                keyObj = body.get("alertKey");
            }
            if (keyObj == null) {
                return ResultObject.failed("告警 key 不能为空");
            }
            String key = String.valueOf(keyObj);
            List<Long> visible = alertVisibleAccountIds();
            String action = body.get("action") != null ? String.valueOf(body.get("action")) : "";
            return switch (action) {
                case "ack" -> alertService.ack(key, visible)
                        ? ResultObject.success(null) : ResultObject.failed("告警不存在或状态不可操作");
                case "silence" -> alertService.silence(key, visible)
                        ? ResultObject.success(null) : ResultObject.failed("告警不存在或状态不可操作");
                case "unsilence" -> alertService.unsilence(key, visible)
                        ? ResultObject.success(null) : ResultObject.failed("告警不存在或状态不可操作");
                case "resolve" -> alertService.resolve(key, visible)
                        ? ResultObject.success(null) : ResultObject.failed("告警不存在或状态不可操作");
                default -> ResultObject.failed("不支持的操作: " + action);
            };
        } catch (Exception e) {
            log.error("操作告警失败", e);
            return ResultObject.failed("操作告警失败");
        }
    }

    @GetMapping("/overview")
    public ResultObject<Map<String, Object>> overview() {
        try {
            Map<String, Object> result = new HashMap<>();
            List<Long> visibleAccountIds = visibleAccountIds();
            if (visibleAccountIds == null) {
                result.put("activeAlerts", alertService.countActive());
                result.put("deliveryFailures", alertService.countDeliveryFailures());
                result.put("pendingDeadLetters", countDeadLetters(null));
            } else {
                result.put("activeAlerts", alertService.countActive(visibleAccountIds));
                result.put("deliveryFailures", alertService.countDeliveryFailures(visibleAccountIds));
                result.put("pendingDeadLetters", countDeadLetters(visibleAccountIds));
            }
            result.put("notificationChannels", notificationChannelStatus());
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("获取监控概览失败", e);
            return ResultObject.failed("获取监控概览失败");
        }
    }

    private long countDeadLetters(List<Long> accountIds) {
        if (deadLetterMapper == null) {
            return 0;
        }
        try {
            if (accountIds == null) {
                return deadLetterMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.feijimiao.xianyuassistant.job.entity.JobDeadLetter>()
                                .eq(com.feijimiao.xianyuassistant.job.entity.JobDeadLetter::getResolved, 0));
            }
            if (accountIds.isEmpty()) return 0;
            return deadLetterMapper.countUnresolvedByAccountIds(accountIds);
        } catch (Exception e) {
            log.warn("统计死信数量失败: errorType={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    private Map<String, Object> notificationChannelStatus() {
        Map<String, Object> channels = new HashMap<>();
        boolean configured = false;
        if (emailNotifyService != null) {
            try {
                configured = emailNotifyService.isEmailConfigured();
            } catch (Exception e) {
                log.warn("检查通知渠道配置失败: errorType={}", e.getClass().getSimpleName());
            }
        }
        channels.put("configured", configured);
        return channels;
    }
}