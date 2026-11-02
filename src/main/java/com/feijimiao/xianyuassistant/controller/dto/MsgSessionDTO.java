package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

/**
 * 消息会话DTO（按会话聚合）
 */
@Data
public class MsgSessionDTO {

    /**
     * 会话ID（sId，格式如 55435931514@goofish）
     */
    private String sid;

    /**
     * 会话显示名称（最新一条消息的发送者名称）
     */
    private String buyerName;

    /**
     * 对端用户ID（最新一条消息的发送者ID）
     */
    private String buyerUserId;

    /**
     * 关联的闲鱼商品ID
     */
    private String xyGoodsId;

    /**
     * 最新消息内容（截断展示）
     */
    private String lastContent;

    /**
     * 最新消息时间戳（毫秒）
     */
    private Long lastTime;

    /**
     * 会话内消息总数
     */
    private Integer messageCount;

    /**
     * 是否待回复（最新一条消息来自买家）
     */
    private Boolean pendingReply;
}
