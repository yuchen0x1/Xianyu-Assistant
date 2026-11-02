package com.feijimiao.xianyuassistant.notify;

import com.feijimiao.xianyuassistant.notify.impl.BarkNotifier;
import com.feijimiao.xianyuassistant.notify.impl.DingTalkNotifier;
import com.feijimiao.xianyuassistant.notify.impl.NtfyNotifier;
import com.feijimiao.xianyuassistant.notify.impl.ServerChanNotifier;
import com.feijimiao.xianyuassistant.notify.impl.TelegramNotifier;
import com.feijimiao.xianyuassistant.notify.impl.WeComNotifier;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一通知服务
 * 整合邮件、企业微信、钉钉、Server酱等多个通知渠道
 */
@Slf4j
@Service
public class NotifyService {

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.service.EmailNotifyService emailNotifyService;

    @Autowired(required = false)
    private WeComNotifier weComNotifier;

    @Autowired(required = false)
    private DingTalkNotifier dingTalkNotifier;

    @Autowired(required = false)
    private ServerChanNotifier serverChanNotifier;

    @Autowired(required = false)
    private BarkNotifier barkNotifier;

    @Autowired(required = false)
    private TelegramNotifier telegramNotifier;

    @Autowired(required = false)
    private NtfyNotifier ntfyNotifier;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.service.NotificationService notificationService;

    private final List<Notifier> notifiers = new ArrayList<>();

    /** 单次通知的投递结果。 */
    public enum SendResult {
        /** 至少一个渠道投递成功。 */
        SENT,
        /** 有渠道被尝试但全部失败。 */
        FAILED,
        /** 没有任何可用渠道（未配置/未启用），既不算成功也不该推进抑制窗口。 */
        NO_CHANNEL
    }

    @PostConstruct
    public void init() {
        if (weComNotifier != null) notifiers.add(weComNotifier);
        if (dingTalkNotifier != null) notifiers.add(dingTalkNotifier);
        if (serverChanNotifier != null) notifiers.add(serverChanNotifier);
        if (barkNotifier != null) notifiers.add(barkNotifier);
        if (telegramNotifier != null) notifiers.add(telegramNotifier);
        if (ntfyNotifier != null) notifiers.add(ntfyNotifier);
        log.info("通知服务初始化完成，已注册 {} 个非邮件通知渠道", notifiers.size());
    }

    /**
     * 发送通知到所有已配置的渠道，并创建站内通知。
     * <p>站内通知先持久化；各渠道独立发送，{@link Notifier#send} 返回 false 记为失败并计入日志，
     * 单个渠道失败不阻塞其他渠道。</p>
     *
     * <p>返回投递结果而不是写入共享字段：并发告警会互相覆盖"上一次结果"，
     * 真实失败被读成成功后会推进 30 分钟抑制窗口，导致通知被静默吞掉。</p>
     */
    public SendResult send(NotifyMessage message) {
        boolean anySuccess = false;
        boolean anyAttempt = false;

        // 站内通知先持久化（不参与渠道失败判定）
        if (notificationService != null) {
            try {
                notificationService.createBroadcastNotification(
                        message.getTitle(), message.getContent(), "system", message.getLevel().name());
            } catch (Exception e) {
                log.warn("[Notify] 创建站内通知失败", e);
            }
        }

        // 发送到非邮件渠道：逐个独立发送，失败不阻塞其他渠道
        int failedChannels = 0;
        for (Notifier notifier : notifiers) {
            if (!notifier.isConfigured()) continue;
            anyAttempt = true;
            try {
                boolean sent = notifier.send(message);
                if (sent) {
                    anySuccess = true;
                } else {
                    failedChannels++;
                    log.warn("[Notify] 通知渠道 {} 发送返回失败，继续尝试其他渠道", notifier.getChannel());
                }
            } catch (Exception e) {
                failedChannels++;
                log.error("[Notify] 通知渠道 {} 发送异常，继续尝试其他渠道", notifier.getChannel(), e);
            }
        }

        if (anyAttempt) {
            log.info("[Notify] 通知发送结果: success={}, failedChannels={}", anySuccess, failedChannels);
        }
        if (anySuccess) {
            return SendResult.SENT;
        }
        return anyAttempt ? SendResult.FAILED : SendResult.NO_CHANNEL;
    }

    /** 兼容旧调用方；语义等同 {@code send(message) == SENT}。 */
    public boolean sendAndReport(NotifyMessage message) {
        return send(message) == SendResult.SENT;
    }

    /**
     * 发送WebSocket断开通知
     */
    public void sendWsDisconnect(Long accountId, String accountNote) {
        String title = "消息监听已掉线 - " + (accountNote != null ? accountNote : "账号" + accountId);
        String content = String.format(
                "账号ID：%s\n账号备注：%s\n\n问题描述：该闲鱼账号的WebSocket消息监听连接已断开，系统尝试多次重连均失败。\n\n请检查网络连接，确认Cookie是否有效。",
                accountId, accountNote != null ? accountNote : "无"
        );
        send(new NotifyMessage(title, content, NotifyMessage.NotifyLevel.ERROR));

        // 同时发送邮件（单渠道失败不阻塞通知链路，与 send() 的设计一致）
        if (emailNotifyService != null) {
            try {
                emailNotifyService.sendWsDisconnectNotifyEmail(accountId, accountNote);
            } catch (Exception e) {
                log.warn("断线邮件通知发送失败: accountId={}, error={}", accountId, e.getMessage());
            }
        }
    }

    /**
     * 发送Cookie过期通知
     */
    public void sendCookieExpire(Long accountId, String accountNote) {
        String title = "Cookie已过期 - " + (accountNote != null ? accountNote : "账号" + accountId);
        String content = String.format(
                "账号ID：%s\n账号备注：%s\n\n该闲鱼账号的Cookie已过期，且系统无法自动续期。\n\n影响范围：无法接收买家消息、无法自动回复、无法自动发货。\n\n请登录闲鱼APP重新获取Cookie。",
                accountId, accountNote != null ? accountNote : "无"
        );
        send(new NotifyMessage(title, content, NotifyMessage.NotifyLevel.WARNING));

        if (emailNotifyService != null) {
            try {
                emailNotifyService.sendCookieExpireNotifyEmail(accountId, accountNote);
            } catch (Exception e) {
                log.warn("Cookie 过期邮件通知发送失败: accountId={}, error={}", accountId, e.getMessage());
            }
        }
    }

    /**
     * 发送卡密预警通知
     */
    public void sendKamiAlert(String configName, int availableCount, int totalCount) {
        String title = "卡密库存预警 - " + configName;
        String content = String.format(
                "卡密配置：%s\n可用数量：%s\n总数量：%s\n\n该卡密配置的可用库存已低于预设阈值，请及时补充卡密。",
                configName, availableCount, totalCount
        );
        send(new NotifyMessage(title, content, NotifyMessage.NotifyLevel.WARNING));
    }

    /**
     * 发送卡密库存不足通知
     */
    public void sendKamiStockOut(String configName, String orderId) {
        String title = "卡密库存不足 - " + configName;
        String content = String.format(
                "卡密配置：%s\n触发订单：%s\n\n该卡密仓库已无可用卡密，订单无法自动发货！请及时补充卡密。",
                configName, orderId != null ? orderId : "未知"
        );
        send(new NotifyMessage(title, content, NotifyMessage.NotifyLevel.ERROR));
    }

    /**
     * 发送自动发货失败通知
     */
    public void sendAutoDeliveryFail(String xyGoodsId, String orderId, String failReason) {
        String title = "自动发货失败 - " + (orderId != null ? orderId : "未知订单");
        String content = String.format(
                "商品ID：%s\n订单ID：%s\n失败原因：%s\n\n请检查发货配置或手动处理该订单。",
                xyGoodsId != null ? xyGoodsId : "未知",
                orderId != null ? orderId : "未知",
                failReason != null ? failReason : "未知"
        );
        send(new NotifyMessage(title, content, NotifyMessage.NotifyLevel.ERROR));
    }

    /**
     * 发送风控验证通知
     */
    public void sendCaptchaRequired(Long accountId, String accountNote, String reason) {
        String title = "触发风控验证 - " + (accountNote != null ? accountNote : "账号" + accountId);
        String content = String.format(
                "账号ID：%s\n账号备注：%s\n触发原因：%s\n\n系统检测到闲鱼风控，需要人工处理滑块验证。\n请访问 goofish.com/im 完成验证后更新Cookie。",
                accountId, accountNote != null ? accountNote : "无", reason != null ? reason : "未知"
        );
        send(new NotifyMessage(title, content, NotifyMessage.NotifyLevel.ERROR));
    }

    /**
     * 检查是否有任何通知渠道已配置
     */
    public boolean hasAnyNotifierConfigured() {
        for (Notifier notifier : notifiers) {
            if (notifier.isConfigured()) return true;
        }
        return false;
    }

    /**
     * 向单个渠道发送测试推送，返回 null 表示成功，否则为失败原因。
     * <p>邮件渠道有自己的 /testEmail 接口，不在此处理。</p>
     */
    public String testChannel(NotifyChannel channel) {
        Notifier target = null;
        for (Notifier notifier : notifiers) {
            if (notifier.getChannel() == channel) {
                target = notifier;
                break;
            }
        }
        if (target == null) {
            return "该通知渠道暂不支持";
        }
        if (!target.isConfigured()) {
            return "该渠道尚未配置或配置不完整，请先保存配置";
        }
        NotifyMessage message = new NotifyMessage(
                "测试推送 - 闲鱼助手",
                "这是一条测试推送。收到即说明该通知渠道配置成功，后续发货失败、掉线、库存不足等告警会推送到这里。",
                NotifyMessage.NotifyLevel.INFO);
        try {
            return target.send(message) ? null : "测试推送发送失败，请检查配置和网络";
        } catch (Exception e) {
            log.error("[Notify] 测试推送异常: channel={}", channel, e);
            return "测试推送异常: " + e.getMessage();
        }
    }
}
