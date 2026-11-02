package com.feijimiao.xianyuassistant.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自动回复决策日志实体
 *
 * <p>记录买家消息未被自动回复的原因，用于事后追溯"为什么这条消息没回"。
 * 已成功回复/等待人工确认的消息由 {@link XianyuGoodsAutoReplyRecord} 承载，
 * 本表只记录跳过与生成失败两类事件。</p>
 */
@Data
public class AutoReplyDecision {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 闲鱼账号ID
     */
    private Long xianyuAccountId;

    /**
     * 会话ID
     */
    private String sId;

    /**
     * 闲鱼商品ID
     */
    private String xyGoodsId;

    /**
     * 买家用户ID
     */
    private String buyerUserId;

    /**
     * 触发消息内容（截断存储）
     */
    private String buyerMessage;

    /**
     * 跳过原因码：HUMAN_TAKEOVER / GOODS_NOT_FOUND / NO_REPLY_STRATEGY / NO_REPLY_CONTENT
     */
    private String reasonCode;

    /**
     * 补充说明
     */
    private String detail;

    /**
     * 记录时间
     */
    private LocalDateTime createTime;
}
