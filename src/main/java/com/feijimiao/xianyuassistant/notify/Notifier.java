package com.feijimiao.xianyuassistant.notify;

/**
 * 通知发送器接口
 * 所有通知渠道实现此接口
 */
public interface Notifier {

    /**
     * 发送通知
     *
     * @param message 通知消息
     * @return true表示发送成功，false表示发送失败
     */
    boolean send(NotifyMessage message);

    /**
     * 获取通知渠道类型
     */
    NotifyChannel getChannel();

    /**
     * 检查该通知器是否已配置可用
     */
    boolean isConfigured();
}
