package com.feijimiao.xianyuassistant.service.reply;

import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.job.config.JobWorkerRegistry;
import com.feijimiao.xianyuassistant.job.service.JobEngine;
import com.feijimiao.xianyuassistant.service.AutoReplyDelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动回复延时调度服务
 *
 * <p>核心职责：管理买家消息的延时任务，到期后触发AI自动回复。</p>
 *
 * <h3>延时机制（持久化）：</h3>
 * <ol>
 *   <li>买家消息到来 → 检查人工接管 → 未接管则提交持久化延时任务（JobEngine）</li>
 *   <li>延时期间收到新消息 → 幂等更新任务参数（追加消息），重新计时</li>
 *   <li>延时到期 → Worker 再次检查人工接管 → 未接管则执行AI回复</li>
 * </ol>
 *
 * <p>任务通过 {@link JobEngine} 落库（job_definition），进程重启后可恢复未执行任务，
 * 避免此前纯内存调度导致的重启丢失。</p>
 *
 * <h3>人工干预拦截（两道防线）：</h3>
 * <ul>
 *   <li>防线1：消息到来时立即检查 {@link HumanTakeoverManager#isTakenOver}，接管中则不提交任务</li>
 *   <li>防线2：Worker 执行时再次检查，防止并发竞态导致漏拦</li>
 * </ul>
 */
@Slf4j
@Service
public class AutoReplyDelayServiceImpl implements AutoReplyDelayService {

    @Autowired
    private JobEngine jobEngine;

    @Autowired
    private HumanTakeoverManager takeoverManager;

    @Autowired
    private ReplyConfigProvider configProvider;

    /**
     * 延时期间收集的买家消息列表（内存缓存，用于幂等更新任务参数）
     * <ul>
     *   <li>Key: accountId_sId</li>
     *   <li>Value: 该会话在延时期间收到的所有买家消息</li>
     * </ul>
     */
    private final Map<String, List<ChatMessageData>> pendingMessages = new ConcurrentHashMap<>();

    /** 单会话最多累积的消息条数，防止长时间未回复时内存与任务参数无界增长。 */
    private static final int MAX_PENDING_MESSAGES_PER_SESSION = 50;

    @PreDestroy
    @Override
    public void shutdown() {
        log.info("关闭自动回复延时调度器（任务已持久化，重启后可恢复）");
        pendingMessages.clear();
    }

    /**
     * 提交延时回复任务（持久化，幂等）
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查人工接管 → 接管中则直接跳过</li>
     *   <li>追加当前消息到待处理列表</li>
     *   <li>通过 JobEngine 幂等创建/更新延时任务（相同 idempotencyKey 自动重新计时并追加参数）</li>
     * </ol>
     */
    @Override
    public void submitDelayTask(ChatMessageData messageData) {
        if (messageData == null || messageData.getSId() == null) {
            log.warn("消息数据无效，无法提交延时任务");
            return;
        }

        Long accountId = messageData.getXianyuAccountId();
        String sId = messageData.getSId();
        String taskKey = buildTaskKey(accountId, sId);

        // 防线1：消息到来时立即检查人工接管
        boolean isTakenOver = takeoverManager.isTakenOver(accountId, sId);
        log.info("【账号{}】提交延时任务前检查人工接管: sId={}, isTakenOver={}", accountId, sId, isTakenOver);

        if (isTakenOver) {
            log.info("【账号{}】会话已被人工接管，跳过自动回复: sId={}", accountId, sId);
            return;
        }

        int delaySeconds = configProvider.getDelaySeconds(accountId, messageData.getXyGoodsId());
        log.info("【账号{}】提交延时回复任务: sId={}, delay={}s", accountId, sId, delaySeconds);

        // 追加消息到待处理列表。
        // 必须生成新列表而不是原地 add：任务参数序列化在另一个线程，
        // 原地修改会抛 ConcurrentModificationException 导致参数丢失、回复没有内容。
        List<ChatMessageData> snapshot = pendingMessages.compute(taskKey, (key, existingList) -> {
            List<ChatMessageData> next = existingList == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existingList);
            next.add(messageData);
            if (next.size() > MAX_PENDING_MESSAGES_PER_SESSION) {
                next = new ArrayList<>(next.subList(next.size() - MAX_PENDING_MESSAGES_PER_SESSION, next.size()));
            }
            return next;
        });

        // 提交/更新持久化延时任务（幂等键 = accountId_sId，重复提交自动重新计时；
        // rearmOnSuccess：上一轮回复成功后，新消息仍需重新触发延时任务）
        Map<String, Object> params = new HashMap<>();
        params.put("sId", sId);
        params.put("messages", snapshot == null ? List.of() : List.copyOf(snapshot));
        jobEngine.createJob(
                JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY,
                buildIdempotencyKey(accountId, sId),
                params,
                accountId,
                LocalDateTime.now().plusSeconds(Math.max(1, delaySeconds)),
                3,
                0,
                true);
    }

    @Override
    public void cancelDelayTask(Long accountId, String sId) {
        if (accountId == null || sId == null) return;
        String taskKey = buildTaskKey(accountId, sId);
        pendingMessages.remove(taskKey);
        jobEngine.cancelJob(buildIdempotencyKey(accountId, sId));
    }

    @Override
    public int getPendingTaskCount() {
        return pendingMessages.size();
    }

    @Override
    public void clearPendingMessages(Long accountId, String sId) {
        if (accountId == null || sId == null) {
            return;
        }
        pendingMessages.remove(buildTaskKey(accountId, sId));
    }

    /**
     * 记录卖家手动回复，触发人工接管
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查人工干预开关是否开启</li>
     *   <li>标记该会话为人工接管（持续N分钟）</li>
     *   <li>立即取消该会话的延时任务</li>
     *   <li>清除待处理消息</li>
     * </ol>
     */
    @Override
    public void recordSellerManualReply(Long accountId, String xyGoodsId, String sId) {
        if (accountId == null || sId == null) {
            log.warn("recordSellerManualReply参数无效: accountId={}, sId={}, xyGoodsId={}", accountId, sId, xyGoodsId);
            return;
        }

        boolean interventionEnabled = configProvider.isHumanInterventionEnabled(accountId, xyGoodsId);
        log.info("【账号{}】卖家手动回复，检查人工干预开关: sId={}, xyGoodsId={}, enabled={}", accountId, sId, xyGoodsId, interventionEnabled);

        if (!interventionEnabled) {
            log.info("【账号{}】人工干预未开启或xyGoodsId无效，跳过接管: sId={}, xyGoodsId={}", accountId, sId, xyGoodsId);
            return;
        }

        int minutes = configProvider.getInterventionMinutes(accountId, xyGoodsId);
        takeoverManager.takeover(accountId, sId, minutes);

        // 立即取消该会话的延时任务和待处理消息
        cancelDelayTask(accountId, sId);

        log.info("【账号{}】卖家手动回复，人工接管: sId={}, xyGoodsId={}, {}分钟后恢复", accountId, sId, xyGoodsId, minutes);
    }

    private String buildTaskKey(Long accountId, String sId) {
        return accountId + "_" + sId;
    }

    private String buildIdempotencyKey(Long accountId, String sId) {
        return JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY + ":" + accountId + ":" + sId;
    }
}
