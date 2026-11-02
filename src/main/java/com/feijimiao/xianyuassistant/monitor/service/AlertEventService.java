package com.feijimiao.xianyuassistant.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import com.feijimiao.xianyuassistant.monitor.mapper.AlertEventMapper;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.NotifyMessage.NotifyLevel;
import com.feijimiao.xianyuassistant.notify.NotifyService;
import com.feijimiao.xianyuassistant.webhook.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 告警事件服务：持久化告警、原子去重发送、确认/静默/恢复与概览统计。
 * <p>发送成功才推进抑制窗口；失败只累计 send_attempts，供重试与监控。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEventService {

    /** 默认抑制窗口（秒）：30 分钟。 */
    private static final int DEFAULT_WINDOW_SECONDS = 1800;

    private final AlertEventMapper alertEventMapper;
    private final NotifyService notifyService;
    private final WebhookService webhookService;

    /**
     * 触发一次告警：INSERT OR IGNORE 创建事件，条件 UPDATE 抢占发送窗口。
     * 只有发送成功才更新 last_sent_at / next_send_at，失败不消耗抑制窗口。
     */
    public AlertEvent trigger(String alertKey, String title, String message, String level, Long accountId) {
        if (!emptyKey(alertKey)) {
            throw new IllegalArgumentException("alertKey must not be blank");
        }
        alertEventMapper.insertIfAbsent(alertKey, title, message, level, accountId);
        // 同一问题在"已恢复"之后复发时必须重新打开，否则会永久静音
        alertEventMapper.reopenIfResolved(alertKey, message, level);

        // 抢占窗口（并发下仅一个线程成功）；静默/已恢复的事件不发送。
        boolean sendable = alertEventMapper.bumpIfSendable(alertKey) > 0;
        if (!sendable) {
            return alertEventMapper.selectOne(new LambdaQueryWrapper<AlertEvent>()
                    .eq(AlertEvent::getAlertKey, alertKey));
        }

        NotifyService.SendResult result = sendThroughChannels(alertKey, title, message, level);
        if (result == NotifyService.SendResult.SENT) {
            alertEventMapper.markSent(alertKey, DEFAULT_WINDOW_SECONDS);
        } else if (result == NotifyService.SendResult.FAILED) {
            alertEventMapper.bumpSendAttempts(alertKey);
        }
        // NO_CHANNEL：既没发出去也没失败，不推进抑制窗口、不计失败次数
        return alertEventMapper.selectOne(new LambdaQueryWrapper<AlertEvent>()
                .eq(AlertEvent::getAlertKey, alertKey));
    }

    private NotifyService.SendResult sendThroughChannels(String alertKey, String title, String message, String level) {
        NotifyMessage.NotifyLevel parsedLevel = parseLevel(level);
        NotifyMessage notifyMessage = new NotifyMessage(title, message, parsedLevel);
        NotifyService.SendResult result = NotifyService.SendResult.FAILED;
        try {
            // 站内通知先持久化；非邮件渠道逐渠道独立发送，一个失败不阻塞其他。
            // 结果由返回值携带，不再读共享字段（并发告警会互相覆盖）。
            result = notifyService.send(notifyMessage);
            if (result == NotifyService.SendResult.NO_CHANNEL) {
                log.debug("告警无可用通知渠道，保留抑制窗口: alertKey={}", alertKey);
            }
            // 发送成功后才触发 Webhook（失败仅累计 send_attempts，不消耗抑制窗口）
            if (result == NotifyService.SendResult.SENT) {
                webhookService.dispatch("alert.triggered", Map.of(
                        "alertKey", alertKey,
                        "title", title,
                        "level", level,
                        "message", message
                ));
            }
        } catch (Exception e) {
            log.warn("告警发送异常: alertKey={}, errorType={}", alertKey, e.getClass().getSimpleName());
            return NotifyService.SendResult.FAILED;
        }
        return result;
    }

    public static NotifyMessage.NotifyLevel parseLevel(String level) {
        try {
            return NotifyMessage.NotifyLevel.valueOf(level);
        } catch (Exception e) {
            return NotifyMessage.NotifyLevel.WARNING;
        }
    }

    /** 检查告警键非空且非纯空白，供服务层与测试共用。 */
    public static boolean emptyKey(String alertKey) {
        return alertKey != null && !alertKey.isBlank();
    }

    // ========== 查询 / 操作 ==========

    public List<AlertEvent> list(String status, Long accountId, int limit) {
        LambdaQueryWrapper<AlertEvent> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(AlertEvent::getStatus, status);
        }
        if (accountId != null) {
            qw.eq(AlertEvent::getAccountId, accountId);
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return alertEventMapper.selectList(qw.orderByDesc(AlertEvent::getFirstSeenAt)
                .last("LIMIT " + safeLimit));
    }

    public List<AlertEvent> listByScope(String status, Long accountId, List<Long> accountIds, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return alertEventMapper.selectAlertsByScope(status, accountId, accountIds, safeLimit);
    }

    public AlertEvent getByKeyInScope(String alertKey, List<Long> accountIds) {
        if (alertKey == null || alertKey.isBlank()) {
            return null;
        }
        return alertEventMapper.selectAlertByKeyInScope(alertKey, accountIds);
    }

    public boolean updateStatus(String alertKey, String targetStatus) {
        return updateStatus(alertKey, targetStatus, null);
    }

    public boolean updateStatus(String alertKey, String targetStatus, List<Long> accountIds) {
        if (alertKey == null || alertKey.isBlank() || targetStatus == null) {
            return false;
        }
        if (!AlertEvent.STATUS_ACTIVE.equals(targetStatus)
                && !AlertEvent.STATUS_ACKED.equals(targetStatus)
                && !AlertEvent.STATUS_SILENCED.equals(targetStatus)
                && !AlertEvent.STATUS_RESOLVED.equals(targetStatus)) {
            return false;
        }
        AlertEvent existing = accountIds == null
                ? alertEventMapper.selectAlertByKey(alertKey)
                : alertEventMapper.selectAlertByKeyInScope(alertKey, accountIds);
        if (existing == null) {
            return false;
        }
        int updated = accountIds == null
                ? alertEventMapper.updateStatusTo(alertKey, targetStatus)
                : alertEventMapper.updateStatusToInScope(alertKey, targetStatus, accountIds);
        return updated > 0;
    }

    public long countActive() {
        return alertEventMapper.countActive();
    }

    public long countActive(List<Long> accountIds) {
        if (accountIds == null) return countActive();
        return alertEventMapper.countActiveByAccountIds(accountIds);
    }

    public long countDeliveryFailures() {
        return alertEventMapper.countDeliveryFailures();
    }

    public long countDeliveryFailures(List<Long> accountIds) {
        if (accountIds == null) return countDeliveryFailures();
        return alertEventMapper.countDeliveryFailuresByAccountIds(accountIds);
    }

    public long countPending() {
        return alertEventMapper.countPending();
    }
}