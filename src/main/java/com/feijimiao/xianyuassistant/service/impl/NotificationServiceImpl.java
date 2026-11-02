package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import com.feijimiao.xianyuassistant.mapper.XianyuNotificationMapper;
import com.feijimiao.xianyuassistant.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内通知服务实现
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    @Autowired
    private XianyuNotificationMapper notificationMapper;

    @Override
    public void createNotification(String title, String content, String notifyType, String level) {
        createBroadcastNotification(title, content, notifyType, level);
    }

    @Override
    public void createBroadcastNotification(String title, String content, String notifyType, String level) {
        createBroadcastNotification(title, content, notifyType, level, null);
    }

    @Override
    public void createBroadcastNotification(String title, String content, String notifyType, String level,
                                            Long accountId) {
        saveNotification(title, content, notifyType, level, null, accountId, 1);
    }

    @Override
    public void createNotificationForCurrentUser(String title, String content, String notifyType, String level) {
        createNotificationForCurrentUser(title, content, notifyType, level, null);
    }

    @Override
    public void createNotificationForCurrentUser(String title, String content, String notifyType, String level,
                                                 Long accountId) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            log.warn("创建当前用户通知失败: 当前用户上下文为空, title={}", title);
            return;
        }
        saveNotification(title, content, notifyType, level, currentUserId, accountId, 0);
    }

    private void saveNotification(String title, String content, String notifyType, String level,
                                  Long recipientUserId, Long accountId, Integer broadcast) {
        try {
            XianyuNotification notification = new XianyuNotification();
            notification.setTitle(title);
            notification.setContent(content);
            notification.setNotifyType(notifyType);
            notification.setLevel(level != null ? level : "INFO");
            notification.setRecipientUserId(recipientUserId);
            notification.setAccountId(accountId);
            notification.setBroadcast(broadcast);
            notification.setReadStatus(0);
            notificationMapper.insert(notification);
        } catch (Exception e) {
            log.warn("创建站内通知失败: title={}, errorType={}", title, e.getClass().getSimpleName());
        }
    }

    @Override
    public List<XianyuNotification> getRecentNotifications(int limit) {
        return notificationMapper.selectRecent(UserContext.getUserId(), Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public long countUnread() {
        return notificationMapper.countUnread(UserContext.getUserId());
    }

    @Override
    public boolean markRead(Long id) {
        if (id == null || UserContext.getUserId() == null) {
            return false;
        }
        try {
            Long userId = UserContext.getUserId();
            XianyuNotification notification = notificationMapper.selectById(id);
            if (notification == null) {
                return false;
            }
            if (notification.getBroadcast() != null && notification.getBroadcast() == 0
                    && notification.getRecipientUserId() != null
                    && !userId.equals(notification.getRecipientUserId())) {
                return false;
            }
            boolean broadcast = notification.getBroadcast() != null && notification.getBroadcast() == 1
                    || notification.getRecipientUserId() == null;
            if (broadcast) {
                int inserted = notificationMapper.markBroadcastRead(id, userId);
                return inserted > 0 || notificationMapper.hasReadReceipt(id, userId);
            }
            return notificationMapper.markDirectRead(id, userId) > 0
                    || notification.getReadStatus() != null && notification.getReadStatus() == 1;
        } catch (Exception e) {
            log.warn("标记通知已读失败: id={}", id, e);
            return false;
        }
    }

    @Override
    public int markAllRead() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return 0;
        }
        try {
            return notificationMapper.markAllBroadcastRead(userId)
                    + notificationMapper.markAllDirectRead(userId);
        } catch (Exception e) {
            log.warn("标记全部已读失败", e);
            return 0;
        }
    }
}
