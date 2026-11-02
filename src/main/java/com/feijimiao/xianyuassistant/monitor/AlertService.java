package com.feijimiao.xianyuassistant.monitor;

import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import com.feijimiao.xianyuassistant.monitor.service.AlertEventService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警服务：监控关键指标阈值，通过通知渠道发送告警。
 * <p>告警去重、抑制窗口与发送状态均由 {@link AlertEventService} 持久化管理，
 * 本类保留业务检查入口与内存统计计数器。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertEventService alertEventService;

    /** 业务统计计数器（本地内存） */
    private final ConcurrentHashMap<String, AtomicLong> statsCounters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("告警服务已初始化");
    }

    // ========== 业务告警 ==========

    /** 检查并发送告警：连续发货失败 */
    public void checkDeliveryFailures(Long accountId, int recentFailures, int threshold) {
        if (recentFailures >= threshold) {
            // 告警键必须带账号：不带的话多账号的失败计数会落到同一条事件上互相覆盖，
            // 某个账号恢复后会把另一个账号仍在失败的状态一起"恢复"掉。
            alert("delivery_failure_" + accountId,
                    "发货失败告警",
                    String.format("账号 %s 最近发货失败 %d 次 (阈值: %d)", accountId, recentFailures, threshold),
                    "WARNING", accountId);
        }
    }

    /** 检查并发送告警：WebSocket 断连 */
    public void checkConnectionLost(Long accountId) {
        alert("connection_lost_" + accountId,
                "连接断开",
                String.format("账号 %s 的 WebSocket 连接已断开", accountId),
                "WARNING", accountId);
    }

    /** 检查并发送告警：账号风控 */
    public void checkAccountRisk(Long accountId, String riskType) {
        alert("account_risk_" + accountId,
                "风控告警",
                String.format("账号 %s 触发风控: %s", accountId, riskType),
                "ERROR", accountId);
    }

    /** 检查并发送告警：卡密库存不足 */
    public void checkKamiStock(String kamiName, int stock, int threshold) {
        if (stock <= threshold) {
            alert("kami_stock_" + kamiName,
                    "卡密库存不足",
                    String.format("卡密 %s 库存仅剩 %d (阈值: %d)", kamiName, stock, threshold),
                    "WARNING", null);
        }
    }

    /** 检查并发送告警：任务死信堆积 */
    public void checkDeadLetterCount(int count, int threshold) {
        if (count >= threshold) {
            alert("dead_letter_accumulation",
                    "死信任务堆积",
                    String.format("死信任务数 %d (阈值: %d)", count, threshold),
                    "WARNING", null);
        }
    }

    // ========== 告警事件操作（代理到持久化服务） ==========

    public AlertEvent alert(String alertKey, String title, String message, String level, Long accountId) {
        return alertEventService.trigger(alertKey, title, message, level, accountId);
    }

    public List<AlertEvent> list(String status, Long accountId, int limit) {
        return alertEventService.list(status, accountId, limit);
    }

    public List<AlertEvent> listByScope(String status, Long accountId, List<Long> accountIds, int limit) {
        return alertEventService.listByScope(status, accountId, accountIds, limit);
    }

    public AlertEvent getByKeyInScope(String alertKey, List<Long> accountIds) {
        return alertEventService.getByKeyInScope(alertKey, accountIds);
    }

    public boolean ack(String alertKey) {
        return ack(alertKey, null);
    }

    public boolean ack(String alertKey, List<Long> accountIds) {
        return alertEventService.updateStatus(alertKey, AlertEvent.STATUS_ACKED, accountIds);
    }

    public boolean silence(String alertKey) {
        return silence(alertKey, null);
    }

    public boolean silence(String alertKey, List<Long> accountIds) {
        return alertEventService.updateStatus(alertKey, AlertEvent.STATUS_SILENCED, accountIds);
    }

    public boolean unsilence(String alertKey) {
        return unsilence(alertKey, null);
    }

    public boolean unsilence(String alertKey, List<Long> accountIds) {
        return alertEventService.updateStatus(alertKey, AlertEvent.STATUS_ACTIVE, accountIds);
    }

    public boolean resolve(String alertKey) {
        return resolve(alertKey, null);
    }

    public boolean resolve(String alertKey, List<Long> accountIds) {
        return alertEventService.updateStatus(alertKey, AlertEvent.STATUS_RESOLVED, accountIds);
    }

    public long countActive() {
        return alertEventService.countActive();
    }

    public long countActive(List<Long> accountIds) {
        return alertEventService.countActive(accountIds);
    }

    public long countDeliveryFailures() {
        return alertEventService.countDeliveryFailures();
    }

    public long countDeliveryFailures(List<Long> accountIds) {
        return alertEventService.countDeliveryFailures(accountIds);
    }

    public long countPending() {
        return alertEventService.countPending();
    }

    // ========== 统计计数器 ==========

    /** 递增统计数据（用于内部聚合） */
    public void incrementStats(String key) {
        statsCounters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /** 获取并重置统计计数器 */
    public Map<String, Long> drainStats() {
        Map<String, Long> snapshot = new ConcurrentHashMap<>();
        statsCounters.forEach((k, v) -> snapshot.put(k, v.getAndSet(0)));
        return snapshot;
    }
}