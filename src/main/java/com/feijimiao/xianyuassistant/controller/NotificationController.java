package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import com.feijimiao.xianyuassistant.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/notification")
@RequirePermission("notification:read")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    /**
     * 查询最近通知及未读数量
     */
    @PostMapping("/list")
    public ResultObject<Map<String, Object>> listNotifications(@RequestBody(required = false) Map<String, Object> params) {
        try {
            int limit = params != null && params.get("limit") != null
                    ? Integer.parseInt(params.get("limit").toString()) : 20;

            List<XianyuNotification> notifications = notificationService.getRecentNotifications(limit);
            long unread = notificationService.countUnread();

            Map<String, Object> result = new HashMap<>();
            result.put("list", notifications);
            result.put("unread", unread);
            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("查询站内通知失败", e);
            return ResultObject.failed("查询站内通知失败: " + e.getMessage());
        }
    }

    /**
     * 统计未读通知数量
     */
    @PostMapping("/unreadCount")
    public ResultObject<Long> unreadCount() {
        try {
            return ResultObject.success(notificationService.countUnread());
        } catch (Exception e) {
            log.error("统计未读通知失败", e);
            return ResultObject.failed("统计未读通知失败: " + e.getMessage());
        }
    }

    /**
     * 标记通知已读
     */
    @PostMapping("/markRead")
    @RequirePermission("notification:write")
    public ResultObject<?> markRead(@RequestBody Map<String, Object> params) {
        try {
            if (params.get("id") == null) {
                return ResultObject.failed("通知ID不能为空");
            }
            Long id = Long.parseLong(params.get("id").toString());
            boolean success = notificationService.markRead(id);
            return success ? ResultObject.success(null) : ResultObject.failed("通知不存在");
        } catch (Exception e) {
            log.error("标记通知已读失败", e);
            return ResultObject.failed("标记通知已读失败: " + e.getMessage());
        }
    }

    /**
     * 标记全部已读
     */
    @PostMapping("/markAllRead")
    @RequirePermission("notification:write")
    public ResultObject<Integer> markAllRead() {
        try {
            int count = notificationService.markAllRead();
            return ResultObject.success(count);
        } catch (Exception e) {
            log.error("标记全部已读失败", e);
            return ResultObject.failed("标记全部已读失败: " + e.getMessage());
        }
    }
}
