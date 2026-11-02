package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;

/**
 * 自动回复延时调度服务接口
 * 
 * <p>实现15秒延时回复机制：</p>
 * <ul>
 *   <li>用户发送消息后，启动15秒倒计时</li>
 *   <li>如果15秒内用户又发送新消息，取消之前的倒计时，重新开始</li>
 *   <li>15秒后无新消息，触发自动回复</li>
 * </ul>
 * 
 * @author IAMLZY
 * @date 2026/4/22
 */
public interface AutoReplyDelayService {
    
    /**
     * 提交延时回复任务
     * 
     * <p>如果该会话已有待执行的任务，会先取消旧任务再提交新任务</p>
     * 
     * @param messageData 消息数据
     */
    void submitDelayTask(ChatMessageData messageData);
    
    /**
     * 取消指定会话的延时任务
     * 
     * @param accountId 账号ID
     * @param sId 会话ID
     */
    void cancelDelayTask(Long accountId, String sId);
    
    /**
     * 获取当前待执行的延时任务数量
     * 
     * @return 任务数量
     */
    int getPendingTaskCount();

    /**
     * 清理某会话在延时期间累积的消息缓存（任务执行完毕后调用）。
     *
     * <p>不清理会导致：缓存随会话数单调增长，且同一会话下一轮延时任务会把
     * 上一轮已回复过的消息一起带上。</p>
     *
     * @param accountId 账号ID
     * @param sId       会话ID
     */
    void clearPendingMessages(Long accountId, String sId);

    void recordSellerManualReply(Long accountId, String xyGoodsId, String sId);

    void shutdown();
}
