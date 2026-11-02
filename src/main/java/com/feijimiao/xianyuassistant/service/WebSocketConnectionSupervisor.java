package com.feijimiao.xianyuassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Keeps every usable account in the WebSocket connection lifecycle. */
@Slf4j
@Component
public class WebSocketConnectionSupervisor {

    private static final int ACCOUNT_STATUS_NORMAL = 1;
    private static final int ACCOUNT_STATUS_CAPTCHA_REQUIRED = -2;
    private static final int COOKIE_STATUS_VALID = 1;

    private final XianyuAccountMapper accountMapper;
    private final XianyuCookieMapper cookieMapper;
    private final WebSocketService webSocketService;

    public WebSocketConnectionSupervisor(
            XianyuAccountMapper accountMapper,
            XianyuCookieMapper cookieMapper,
            WebSocketService webSocketService) {
        this.accountMapper = accountMapper;
        this.cookieMapper = cookieMapper;
        this.webSocketService = webSocketService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void recoverConnectionsOnStartup() {
        maintainConnections("startup");
    }

    @Scheduled(
            fixedDelayString = "${websocket.connection-watchdog-interval-ms:15000}",
            initialDelayString = "${websocket.connection-watchdog-initial-delay-ms:15000}")
    public void maintainConnections() {
        maintainConnections("watchdog");
    }

    void maintainConnections(String trigger) {
        try {
            List<XianyuAccount> accounts = accountMapper.selectList(
                    new LambdaQueryWrapper<XianyuAccount>()
                            .in(XianyuAccount::getStatus,
                                    ACCOUNT_STATUS_NORMAL, ACCOUNT_STATUS_CAPTCHA_REQUIRED)
                            .and(query -> query.isNull(XianyuAccount::getWebsocketEnabled)
                                    .or()
                                    .eq(XianyuAccount::getWebsocketEnabled, 1)));

            int supervisedCount = 0;
            for (XianyuAccount account : accounts) {
                if (account == null || account.getId() == null
                        || !isRecoverableStatus(account.getStatus())
                        || Objects.equals(account.getWebsocketEnabled(), 0)) {
                    continue;
                }
                try {
                    if (!hasValidCookie(account.getId())) {
                        continue;
                    }
                    webSocketService.ensureConnected(account.getId());
                    supervisedCount++;
                } catch (Exception e) {
                    log.warn("单个账号WebSocket保活巡检失败: accountId={}, errorType={}",
                            account.getId(), e.getClass().getSimpleName());
                }
            }

            if ("startup".equals(trigger) || supervisedCount > 0) {
                log.debug("WebSocket连接保活巡检完成: trigger={}, supervisedAccounts={}",
                        trigger, supervisedCount);
            }
        } catch (Exception e) {
            log.error("WebSocket连接保活巡检失败: trigger={}, errorType={}",
                    trigger, e.getClass().getSimpleName());
        }
    }

    private boolean hasValidCookie(Long accountId) {
        XianyuCookie cookie = cookieMapper.selectOne(
                new LambdaQueryWrapper<XianyuCookie>()
                        .eq(XianyuCookie::getXianyuAccountId, accountId)
                        .orderByDesc(XianyuCookie::getCreatedTime)
                        .last("LIMIT 1"));
        return cookie != null
                && Objects.equals(cookie.getCookieStatus(), COOKIE_STATUS_VALID)
                && cookie.getCookieText() != null
                && !cookie.getCookieText().isBlank();
    }

    private boolean isRecoverableStatus(Integer status) {
        return Objects.equals(status, ACCOUNT_STATUS_NORMAL)
                || Objects.equals(status, ACCOUNT_STATUS_CAPTCHA_REQUIRED);
    }
}
