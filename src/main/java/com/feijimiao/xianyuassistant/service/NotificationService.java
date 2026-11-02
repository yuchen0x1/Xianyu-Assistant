package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuNotification;

import java.util.List;

/**
 * 站内通知服务
 */
public interface NotificationService {

    /**
     * 创建通知（兼容旧调用，默认创建广播通知）
     */
    void createNotification(String title, String content, String notifyType, String level);

    /**
     * 创建广播通知。
     */
    void createBroadcastNotification(String title, String content, String notifyType, String level);

    /**
     * 创建广播通知并关联闲鱼账号。
     */
    void createBroadcastNotification(String title, String content, String notifyType, String level, Long accountId);

    /**
     * 为当前登录用户创建通知。
     */
    void createNotificationForCurrentUser(String title, String content, String notifyType, String level);

    /**
     * 为当前登录用户创建通知并关联闲鱼账号。
     */
    void createNotificationForCurrentUser(String title, String content, String notifyType, String level, Long accountId);

    /**
     * 查询最近通知
     */
    List<XianyuNotification> getRecentNotifications(int limit);

    /**
     * 统计未读通知数量
     */
    long countUnread();

    /**
     * 标记单条已读
     */
    boolean markRead(Long id);

    /**
     * 标记全部已读
     */
    int markAllRead();
}
