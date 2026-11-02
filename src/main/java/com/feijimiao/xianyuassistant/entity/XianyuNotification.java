package com.feijimiao.xianyuassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 站内通知实体
 */
@Data
@TableName("xianyu_notification")
public class XianyuNotification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 通知类型（ws_disconnect/cookie_expire/kami_alert等） */
    private String notifyType;

    /** 通知级别（INFO/WARNING/ERROR） */
    private String level;

    /** 收件用户ID；为空时按广播通知处理（兼容历史数据） */
    private Long recipientUserId;

    /** 关联闲鱼账号ID */
    private Long accountId;

    /** 是否广播通知 0:否 1:是 */
    private Integer broadcast;

    /** 已读状态 0:未读 1:已读 */
    private Integer readStatus;

    /** 创建时间 */
    private String createdTime;
}
