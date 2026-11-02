package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuNotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 站内通知备份：保留标题/内容/类型/账号与已读状态，供完整恢复与审计轨迹。
 */
@Slf4j
@Component
public class NotificationsBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuNotificationMapper notificationMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public String getModuleKey() {
        return "notifications";
    }

    @Override
    public String getModuleName() {
        return "站内通知";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("notifications");
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuNotification> notifications = notificationMapper.selectList(null);

        List<Map<String, Object>> result = new ArrayList<>();
        for (XianyuNotification notification : notifications) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", findAccountUnb(notification.getAccountId()));
            map.put("title", notification.getTitle());
            map.put("content", notification.getContent());
            map.put("notifyType", notification.getNotifyType());
            map.put("level", notification.getLevel());
            map.put("broadcast", notification.getBroadcast());
            map.put("readStatus", notification.getReadStatus());
            map.put("createdTime", notification.getCreatedTime());
            result.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notifications", result);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notificationMaps = (List<Map<String, Object>>) data.get("notifications");
        if (notificationMaps == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        for (Map<String, Object> map : notificationMaps) {
            try {
                XianyuNotification notification = new XianyuNotification();
                notification.setTitle((String) map.get("title"));
                notification.setContent((String) map.get("content"));
                notification.setNotifyType((String) map.get("notifyType"));
                notification.setLevel((String) map.get("level"));
                notification.setBroadcast(integerValue(map.get("broadcast")));
                notification.setReadStatus(integerValue(map.get("readStatus")));
                notification.setCreatedTime((String) map.get("createdTime"));

                Object unbObj = map.get("unb");
                if (unbObj instanceof String unb && !unb.isBlank()) {
                    Long accountId = unbToAccountId.get(unb);
                    if (accountId == null) {
                        throw new IllegalStateException("找不到通知关联账号: " + unb);
                    }
                    notification.setAccountId(accountId);
                }

                // 通知不可覆盖，仅追加，保留历史轨迹。
                notificationMapper.insert(notification);
            } catch (Exception e) {
                throw new IllegalStateException("导入单条通知失败", e);
            }
        }
    }

    private String findAccountUnb(Long accountId) {
        if (accountId == null) return null;
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
            return null;
        }
        return account.getUnb();
    }

    private Integer integerValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}