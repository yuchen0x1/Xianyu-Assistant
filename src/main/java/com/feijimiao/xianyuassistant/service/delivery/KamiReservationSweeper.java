package com.feijimiao.xianyuassistant.service.delivery;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.monitor.AlertService;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 卡密滞留预占巡检。
 *
 * <p>卡密在发货前就被置为「已占用」（status=1）并写入预占动作。如果进程崩溃、响应超时、
 * 或发货结果未知，这条占用会永远停在预占态：库存少了，却没有任何交付证据。
 * 本巡检负责把这类卡密收敛到确定状态：</p>
 *
 * <ul>
 *   <li>订单终态为成功 → 补记交付确认时间（收敛为已交付）</li>
 *   <li>订单终态为失败 → 释放回库存（删除使用记录并恢复可用）</li>
 *   <li>无法判定（订单待定 / 记录缺失）→ 保持预占并触发告警，等待人工核对</li>
 * </ul>
 *
 * <p>设计取舍：宁可把卡密留在预占态并告警，也不自动释放「可能已经发给买家」的卡密，
 * 否则会把静默泄漏换成同一张卡卖两次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KamiReservationSweeper {

    private static final DateTimeFormatter SQLITE_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ALERT_KEY_PREFIX = "kami_reservation_stranded_";
    private static final int MAX_ITEMS_PER_RUN = 200;

    private final XianyuKamiItemMapper kamiItemMapper;
    private final XianyuKamiUsageRecordMapper usageRecordMapper;
    private final XianyuGoodsOrderMapper orderMapper;
    private final KamiConfigService kamiConfigService;
    private final AlertService alertService;

    /** 预占超过该时长仍未确认交付，才进入核对流程，避免与正常发货中的订单抢跑。 */
    @Value("${xianyu.kami.pending-reservation-grace-minutes:30}")
    private long graceMinutes;

    @Scheduled(fixedDelay = 300_000L, initialDelay = 180_000L)
    public void sweep() {
        try {
            String cutoff = LocalDateTime.now().minusMinutes(Math.max(graceMinutes, 1L)).format(SQLITE_DATETIME);
            List<XianyuKamiItem> pending = kamiItemMapper.findStaleReservations(cutoff, MAX_ITEMS_PER_RUN);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            int confirmed = 0;
            int released = 0;
            int stranded = 0;
            for (XianyuKamiItem item : pending) {
                try {
                    int outcome = resolve(item);
                    if (outcome == 1) {
                        confirmed++;
                    } else if (outcome == -1) {
                        released++;
                    } else {
                        stranded++;
                    }
                } catch (Exception e) {
                    stranded++;
                    log.warn("卡密滞留预占处理失败: itemId={}, errorType={}",
                            item.getId(), e.getClass().getSimpleName());
                }
            }
            if (confirmed + released + stranded > 0) {
                log.info("卡密滞留预占巡检完成: 待核对={}, 补确认={}, 已释放={}, 仍需人工={}",
                        pending.size(), confirmed, released, stranded);
            }
        } catch (Exception e) {
            log.warn("卡密滞留预占巡检异常: errorType={}", e.getClass().getSimpleName());
        }
    }

    /**
     * @return 1 已补确认交付，-1 已释放，0 无法判定（保持预占并告警）
     */
    private int resolve(XianyuKamiItem item) {
        String reservationKey = item.getOrderId();
        if (reservationKey == null || reservationKey.isBlank()) {
            // 预占时间有记录却没有动作标识，属于异常数据，无法安全处理。
            raiseStrandedAlert(item, null, "卡密预占缺少动作标识");
            return 0;
        }

        List<XianyuKamiUsageRecord> usageRecords = usageRecordMapper.findByItemId(item.getId());
        XianyuKamiUsageRecord usage = usageRecords == null || usageRecords.isEmpty() ? null : usageRecords.get(0);
        XianyuGoodsOrder order = findOrder(usage);
        if (order == null) {
            raiseStrandedAlert(item, usage != null ? usage.getXianyuAccountId() : null,
                    "找不到对应订单记录，无法判定是否已发货");
            return 0;
        }

        Integer state = order.getState();
        if (state != null && state == 1) {
            kamiConfigService.confirmKamiDelivered(item.getId(), reservationKey);
            clearStrandedAlert(item);
            log.info("滞留预占按订单成功补记交付: itemId={}, orderId={}", item.getId(), order.getOrderId());
            return 1;
        }
        if (state != null && state == -1) {
            if (kamiConfigService.releaseStaleKamiReservation(item.getId(), reservationKey,
                    "订单发货终态失败，卡密未被送达")) {
                clearStrandedAlert(item);
                return -1;
            }
            return 0;
        }

        raiseStrandedAlert(item, order.getXianyuAccountId(),
                String.format("订单 %s 仍处于待确认状态，需人工核对是否已发出", order.getOrderId()));
        return 0;
    }

    private XianyuGoodsOrder findOrder(XianyuKamiUsageRecord usage) {
        if (usage == null || usage.getOrderId() == null || usage.getOrderId().isBlank()
                || usage.getXianyuAccountId() == null) {
            return null;
        }
        return orderMapper.selectByAccountIdAndOrderId(usage.getXianyuAccountId(), usage.getOrderId());
    }

    private void raiseStrandedAlert(XianyuKamiItem item, Long accountId, String detail) {
        try {
            alertService.alert(ALERT_KEY_PREFIX + item.getId(),
                    "卡密预占滞留待核对",
                    String.format("卡密#%d（配置#%d）自 %s 起处于预占态但未确认交付：%s",
                            item.getId(), item.getKamiConfigId(), item.getUsedTime(), detail),
                    "WARNING", accountId);
        } catch (Exception e) {
            log.warn("卡密滞留告警写入失败: itemId={}, errorType={}", item.getId(), e.getClass().getSimpleName());
        }
    }

    private void clearStrandedAlert(XianyuKamiItem item) {
        try {
            alertService.resolve(ALERT_KEY_PREFIX + item.getId());
        } catch (Exception e) {
            log.debug("卡密滞留告警关闭失败: itemId={}", item.getId());
        }
    }
}
