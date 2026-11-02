package com.feijimiao.xianyuassistant.notify;

/**
 * 通知渠道类型枚举
 */
public enum NotifyChannel {
    /** 邮件 */
    EMAIL,
    /** 企业微信机器人 */
    WECOM,
    /** 钉钉机器人 */
    DINGTALK,
    /** Server酱 */
    SERVERCHAN,
    /** PushPlus */
    PUSHPLUS,
    /** Bark（iOS 推送） */
    BARK,
    /** Telegram Bot */
    TELEGRAM,
    /** ntfy（自托管/公共推送） */
    NTFY
}
