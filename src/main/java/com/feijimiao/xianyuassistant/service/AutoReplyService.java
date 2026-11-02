package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;

import java.util.List;

/**
 * 自动回复服务接口
 * 
 * @author IAMLZY
 * @date 2026/4/22
 */
public interface AutoReplyService {

    /** 自动回复执行结果，供任务引擎判断是否需要重试。 */
    enum ReplyOutcome {
        /** 已成功发送。 */
        SUCCESS,
        /** 按策略跳过（未开启、无策略、重复消息、待人工确认等），不算失败。 */
        SKIPPED,
        /** 已发出但服务端未确认，重试会导致买家收到重复消息。 */
        UNKNOWN,
        /** 明确失败，可安全重试。 */
        FAILED
    }

    /**
     * 执行自动回复（单条消息）
     * 
     * @param messageData 消息数据
     */
    void executeAutoReply(ChatMessageData messageData);
    
    /**
     * 执行自动回复（多条消息，延时期间收集）
     * 
     * @param messageList 触发消息列表
     */
    void executeAutoReply(List<ChatMessageData> messageList);

    /**
     * 执行自动回复并返回结果。
     *
     * <p>旧实现只返回 void，任务 worker 恒返回 true，发送失败也被记为成功：
     * 看板显示已回复，实际买家没收到，且唯一索引让同一 pnmId 无法再重试。</p>
     *
     * @param messageList 触发消息列表
     * @return 执行结果
     */
    ReplyOutcome executeAutoReplyWithOutcome(List<ChatMessageData> messageList);

    /**
     * 检查商品是否开启自动回复
     * 
     * @param accountId 账号ID
     * @param xyGoodsId 商品ID
     * @return 是否开启
     */

    /**
     * 检查商品是否开启自动回复
     *
     * @param accountId 账号ID
     * @param xyGoodsId 商品ID
     * @return 是否开启
     */
    boolean isAutoReplyEnabled(Long accountId, String xyGoodsId);

    /**
     * 确认并发送人工确认模式的AI回复草稿（内容可编辑）（内容可编辑）
     *
     * @param recordId 记录ID
     * @param editedContent 编辑后的回复内容，为空则使用AI生成的草稿
     * @return 发送结果
     */
    com.feijimiao.xianyuassistant.common.ResultObject<String> confirmAndSendReply(Long recordId, String editedContent);

    /**
     * 忽略（关闭）待人工确认的AI回复草稿
     *
     * @param recordId 记录ID
     */
    void dismissPendingReply(Long recordId);
}
