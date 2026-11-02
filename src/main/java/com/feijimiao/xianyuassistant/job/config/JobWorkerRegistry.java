package com.feijimiao.xianyuassistant.job.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.service.JobEngine;
import com.feijimiao.xianyuassistant.service.AutoReplyDelayService;
import com.feijimiao.xianyuassistant.service.AutoReplyService;
import com.feijimiao.xianyuassistant.review.service.ReviewService;
import com.feijimiao.xianyuassistant.service.reply.HumanTakeoverManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 注册内置 Worker 到任务引擎。
 * 各模块在启动时注册自己的任务处理器。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JobWorkerRegistry {

    /** 自动回复延时任务类型（与 AutoReplyDelayServiceImpl 提交时一致） */
    public static final String JOB_TYPE_AUTO_REPLY_DELAY = "AUTO_REPLY_DELAY";
    public static final String JOB_TYPE_REVIEW_PENDING = ReviewService.JOB_TYPE;

    private final JobEngine jobEngine;
    private final AutoReplyService autoReplyService;
    private final AutoReplyDelayService autoReplyDelayService;
    private final HumanTakeoverManager takeoverManager;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void registerWorkers() {
        // 自动回复延时 Worker：由 AutoReplyDelayService 提交，到期执行 AI 回复
        jobEngine.registerWorker(JOB_TYPE_AUTO_REPLY_DELAY, this::handleAutoReplyDelay);
        jobEngine.registerWorker(JOB_TYPE_REVIEW_PENDING, (job, params) -> {
            Object id = params == null ? null : params.get("reviewRecordId");
            if (!(id instanceof Number number)) return false;
            return reviewService.executePending(number.longValue());
        });

        // 发货流程直接调用 jobEngine.createJob()，Worker 定义在 DeliveryWorker 中
        // Token 刷新 Worker：由 TokenRefreshServiceImpl 提交
        // 订单轮询 Worker：由 PendingOrderPollService 提交

        log.info("内置 Worker 注册完成，等待业务模块通过 JobEngine.createJob() 提交任务");
    }

    /**
     * 自动回复延时任务执行器：解析参数中的消息列表，到期时执行 AI 自动回复。
     * 若会话已被人工接管则跳过（视为成功，避免重试）。
     */
    private boolean handleAutoReplyDelay(JobDefinition job, Map<String, Object> params) {
        Long accountId = job.getAccountId();
        Object rawMessages = params == null ? null : params.get("messages");
        if (!(rawMessages instanceof List<?> list) || list.isEmpty()) {
            log.warn("自动回复延时任务缺少消息参数: jobId={}, key={}", job.getId(), job.getIdempotencyKey());
            return false;
        }
        List<ChatMessageData> messages = objectMapper.convertValue(
                list, new TypeReference<List<ChatMessageData>>() { });
        if (messages.isEmpty()) {
            return false;
        }
        String sId = messages.get(0).getSId();

        // 到期防线：会话已被人工接管则跳过自动回复
        if (takeoverManager.isTakenOver(accountId, sId)) {
            log.info("【账号{}】延时任务到期时会话已被人工接管，跳过自动回复: sId={}", accountId, sId);
            return true;
        }
        log.info("【账号{}】延时任务到期，执行自动回复: sId={}, 消息数={}", accountId, sId, messages.size());
        try {
            // 只有明确失败才让任务引擎重试；SKIPPED/UNKNOWN 记成功，避免重复回复买家
            return autoReplyService.executeAutoReplyWithOutcome(messages)
                    != AutoReplyService.ReplyOutcome.FAILED;
        } finally {
            // 执行完清空累积缓存，否则同一会话下一轮会把已回复过的消息再带上
            autoReplyDelayService.clearPendingMessages(accountId, sId);
        }
    }
}
