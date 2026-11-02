package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.feijimiao.xianyuassistant.config.WebSocketConfig;
import com.feijimiao.xianyuassistant.constants.OperationConstants;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.service.OperationLogService;

import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.security.CredentialCipher;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import com.feijimiao.xianyuassistant.utils.XianyuAuthFailureGuard;
import com.feijimiao.xianyuassistant.websocket.WebSocketInitializer;
import com.feijimiao.xianyuassistant.websocket.WebSocketMessageHandler;
import com.feijimiao.xianyuassistant.websocket.XianyuWebSocketClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * WebSocket服务实现类
 * 参考Python代码的XianyuAutoAsync类
 * 增强功能：
 * 1. Token自动刷新机制
 * 2. 心跳超时检测
 * 3. 连接重连机制
 */
@Slf4j
@Service
public class WebSocketServiceImpl implements WebSocketService {

    @Autowired
    private AccountService accountService;
    
    @Autowired
    private WebSocketMessageHandler messageHandler;
    
    @Autowired
    private WebSocketTokenService tokenService;
    
    @Autowired
    private WebSocketInitializer initializer;
    
    @Autowired
    private WebSocketConfig config;
    
    @Autowired
    private com.feijimiao.xianyuassistant.utils.AccountDisplayNameUtils displayNameUtils;
    
    @Autowired
    private OperationLogService operationLogService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.CookieRefreshService cookieRefreshService;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.service.EmailNotifyService emailNotifyService;

    @Autowired
    private com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper xianyuAccountMapper;

    @Autowired
    private XianyuCookieMapper xianyuCookieMapper;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.monitor.AlertService alertService;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.webhook.service.WebhookService webhookService;

    @Autowired
    private XianyuAuthFailureGuard authFailureGuard = new XianyuAuthFailureGuard();


    // 存储WebSocket客户端
    private final Map<Long, XianyuWebSocketClient> webSocketClients = new ConcurrentHashMap<>();
    
    // 心跳定时器（默认2线程保证构造/单测安全；@PostConstruct 按配置扩容重建）
    private ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(2);
    
    // 心跳任务
    private final Map<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    
    // Token刷新定时器（默认1线程；@PostConstruct 按配置重建）
    private ScheduledExecutorService tokenRefreshScheduler = Executors.newScheduledThreadPool(1);
    
    // Token刷新任务
    private final Map<Long, ScheduledFuture<?>> tokenRefreshTasks = new ConcurrentHashMap<>();
    
    // 心跳响应时间记录
    private final Map<Long, Long> lastHeartbeatResponseTimes = new ConcurrentHashMap<>();
    
    // 心跳发送时间记录（参考Python的last_heartbeat_time）
    private final Map<Long, Long> lastHeartbeatSendTimes = new ConcurrentHashMap<>();
    
    // Token刷新时间记录
    private final Map<Long, Long> lastTokenRefreshTimes = new ConcurrentHashMap<>();
    
    // 连接重启标志
    private final Map<Long, Boolean> connectionRestartFlags = new ConcurrentHashMap<>();
    
    // 重连任务（防止重复重连）
    private final Map<Long, Future<?>> reconnectTasks = new ConcurrentHashMap<>();

    // 使已取消但已经进入执行器的旧任务失效，避免其覆盖刚恢复的新连接。
    private final Map<Long, AtomicLong> reconnectTaskGenerations = new ConcurrentHashMap<>();

    // Token刷新失败后的延迟重试，必须可在账号删除前取消。
    private final Map<Long, ScheduledFuture<?>> tokenRefreshRetryTasks = new ConcurrentHashMap<>();

    // 滑块等待期间仅保留低频探测，避免普通重连或Token重试形成请求风暴。
    private final Map<Long, ScheduledFuture<?>> captchaProbeTasks = new ConcurrentHashMap<>();

    // 取消后使已经进入执行器队列的旧探测失效。
    private final Map<Long, AtomicLong> captchaProbeTaskGenerations = new ConcurrentHashMap<>();

    // 同一滑块事件逐步降低探测频率；手动恢复或连接恢复后清零。
    private final Map<Long, AtomicInteger> captchaProbeAttemptCounts = new ConcurrentHashMap<>();

    // 用户从验证页返回后，平台状态可能还在传播；短时探测一小段时间，
    // 再回到普通低频策略，避免用户需要重复点击恢复按钮。
    private final Set<Long> manualCaptchaRecoveryRequests = ConcurrentHashMap.newKeySet();

    // Token失效回调可能并发到达；同一账号只允许一个恢复任务执行或排队。
    private final Map<Long, Future<?>> tokenExpiredRecoveryTasks = new ConcurrentHashMap<>();

    // 删除账号后永久阻止异步回调、刷新和重连再次访问外部服务。
    private final Set<Long> permanentlyStoppedAccounts = ConcurrentHashMap.newKeySet();

    // 应用关停后禁止任何巡检或异步回调重新创建连接任务。
    private volatile boolean shuttingDown;

    // 同一账号的启动、握手、登记、发送、重连和删除必须使用同一生命周期屏障。
    private final Map<Long, ReentrantLock> accountLifecycleLocks = new ConcurrentHashMap<>();
    
    // 重连执行器（参考Python的异步重连；默认2线程，@PostConstruct 按配置扩容重建）
    private ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(2);
    
    // 重连次数记录（参考Python的无限重连但有退避）
    private final Map<Long, AtomicInteger> reconnectAttemptCounts = new ConcurrentHashMap<>();

    // 调度器命名线程序号
    private final AtomicInteger schedulerThreadSequence = new AtomicInteger();

    /**
     * 按配置初始化心跳/Token巡检/重连线程池。
     * 多账号场景下固定2线程会导致心跳任务排队，发送或超时检测延迟，
     * 甚至误判掉线；改为可配置大小，默认覆盖 10+ 账号。
     */
    @PostConstruct
    public void initExecutors() {
        int heartbeatPool = Math.max(1, config.getHeartbeatPoolSize());
        int tokenRefreshPool = Math.max(1, config.getTokenRefreshPoolSize());
        int reconnectPool = Math.max(1, config.getReconnectPoolSize());
        // 先关闭字段初始化创建的默认池，避免线程泄漏
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (tokenRefreshScheduler != null) {
            tokenRefreshScheduler.shutdownNow();
        }
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
        heartbeatScheduler = Executors.newScheduledThreadPool(heartbeatPool,
                schedulerThreadFactory("ws-heartbeat"));
        tokenRefreshScheduler = Executors.newScheduledThreadPool(tokenRefreshPool,
                schedulerThreadFactory("ws-token-refresh"));
        reconnectExecutor = Executors.newScheduledThreadPool(reconnectPool,
                schedulerThreadFactory("ws-reconnect"));
        log.info("WebSocket调度线程池已初始化: heartbeat={}, tokenRefresh={}, reconnect={}",
                heartbeatPool, tokenRefreshPool, reconnectPool);
    }

    private java.util.concurrent.ThreadFactory schedulerThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable,
                    prefix + "-" + schedulerThreadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }


    // 仅用于状态展示，不包含任何凭证或验证链接。
    private final Map<Long, Long> nextReconnectAt = new ConcurrentHashMap<>();

    // 邮件通知防抖记录（避免频繁发送）
    private final Map<Long, Long> lastDisconnectNotifyTimes = new ConcurrentHashMap<>();

    // 连续重连失败达到5次后触发邮件通知；后续仍按退避策略重试，避免永久放弃连接。
    private static final int RECONNECT_NOTIFY_THRESHOLD = 5;

    // 邮件通知最小间隔（10分钟）
    private static final long NOTIFY_INTERVAL_MS = 10 * 60 * 1000;

    // 第三方WebSocket关闭等待必须有上限，避免半开连接永久占用账号生命周期锁。
    private static final long CLIENT_CLOSE_TIMEOUT_MILLIS = 2000L;

    // 握手后必须在限定时间内收到应用层reg-sid确认，否则消息通道仍不可用。
    private static final long REGISTRATION_TIMEOUT_SECONDS = 30L;

    // 新扫码会话完成票据交换后，等待平台登录态在各接口间传播再获取WebSocket Token。
    private static final int CREDENTIAL_UPDATE_RECONNECT_DELAY_SECONDS = 15;

    // Token到期前30分钟才允许刷新，数据库过期时间是唯一判断依据。
    private static final long TOKEN_REFRESH_SAFETY_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static final long[] CAPTCHA_PROBE_BACKOFF_SECONDS = {
            TimeUnit.MINUTES.toSeconds(30),
            TimeUnit.HOURS.toSeconds(1),
            TimeUnit.HOURS.toSeconds(2),
            TimeUnit.HOURS.toSeconds(4),
            TimeUnit.HOURS.toSeconds(6)
    };

    private static final long[] MANUAL_CAPTCHA_PROBE_BACKOFF_SECONDS = {
            5L, 15L, 30L, 60L, 120L, 300L
    };

    // 向前抖动最多10%，避免多个账号在固定整点同时请求风控接口。
    private static final int CAPTCHA_PROBE_JITTER_PERCENT = 10;

    private enum PersistedTokenState {
        HEALTHY,
        REFRESH_REQUIRED,
        UNKNOWN
    }

    /**
     * 闲鱼WebSocket URL
     * 参考Python代码：wss://wss-goofish.dingtalk.com/
     */
    private static final String WEBSOCKET_URL = XianyuProtocolProfile.WEBSOCKET_URL;

    private ReentrantLock lifecycleLock(Long accountId) {
        return accountLifecycleLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock(true));
    }

    private <T> T withLifecycleLock(Long accountId, Supplier<T> action) {
        ReentrantLock lock = lifecycleLock(accountId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void withLifecycleLock(Long accountId, Runnable action) {
        withLifecycleLock(accountId, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public boolean startWebSocket(Long accountId) {
        return withLifecycleLock(accountId, () -> startWebSocketLocked(accountId));
    }

    @Override
    public void ensureConnected(Long accountId) {
        if (accountId == null || shuttingDown || isPermanentlyStopped(accountId)) {
            return;
        }

        ReentrantLock lock = lifecycleLock(accountId);
        if (!lock.tryLock()) {
            log.debug("账号生命周期操作正在执行，本次保活巡检跳过: accountId={}", accountId);
            return;
        }

        try {
            if (shuttingDown || isPermanentlyStopped(accountId)) {
                return;
            }
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client != null && client.isConnected()) {
                return;
            }

            Future<?> reconnectTask = reconnectTasks.get(accountId);
            if (reconnectTask != null && !reconnectTask.isDone()) {
                return;
            }

            Future<?> tokenRecoveryTask = tokenExpiredRecoveryTasks.get(accountId);
            if (tokenRecoveryTask != null && !tokenRecoveryTask.isDone()) {
                return;
            }

            ScheduledFuture<?> tokenRetryTask = tokenRefreshRetryTasks.get(accountId);
            if (tokenRetryTask != null && !tokenRetryTask.isDone()) {
                return;
            }

            if (isCaptchaPending(accountId)) {
                scheduleCaptchaProbeLocked(accountId);
                log.debug("账号正在等待滑块验证，本次保活巡检跳过: accountId={}", accountId);
                return;
            }

            scheduleReconnectLocked(accountId, config.getReconnectDelay(), false, true);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean setAutoConnectEnabled(Long accountId, boolean enabled) {
        if (accountId == null || shuttingDown || isPermanentlyStopped(accountId)) {
            return false;
        }
        return withLifecycleLock(accountId, () -> {
            int updatedRows = xianyuAccountMapper.update(null, new UpdateWrapper<XianyuAccount>()
                    .eq("id", accountId)
                    .set("websocket_enabled", enabled ? 1 : 0));
            if (updatedRows <= 0) {
                return false;
            }
            if (enabled) {
                resetReconnectBackoffLocked(accountId);
                return true;
            }
            // 先持久化用户的停用意图，即使关闭连接失败，巡检也不会再把它拉起。
            return stopWebSocketInternal(accountId, true);
        });
    }

    @Override
    public boolean resumeAutoConnection(Long accountId) {
        if (accountId == null || shuttingDown || isPermanentlyStopped(accountId)) {
            return false;
        }
        return withLifecycleLock(accountId, () -> {
            int updatedRows = xianyuAccountMapper.update(null, new UpdateWrapper<XianyuAccount>()
                    .eq("id", accountId)
                    .set("websocket_enabled", 1));
            if (updatedRows <= 0) {
                return false;
            }

            cancelCaptchaProbeLocked(accountId);
            resetReconnectBackoffLocked(accountId);
            boolean stopped = stopWebSocketInternal(accountId, true);
            scheduleReconnectLocked(
                    accountId,
                    stopped ? 0 : config.getReconnectDelay(),
                    stopped,
                    false);
            return true;
        });
    }

    @Override
    public boolean resumeAutoConnectionAfterCredentialUpdate(Long accountId) {
        if (accountId == null || shuttingDown || isPermanentlyStopped(accountId)) {
            return false;
        }
        return withLifecycleLock(accountId, () -> {
            int updatedRows = xianyuAccountMapper.update(null, new UpdateWrapper<XianyuAccount>()
                    .eq("id", accountId)
                    .set("websocket_enabled", 1));
            if (updatedRows <= 0) {
                return false;
            }

            cancelCaptchaProbeLocked(accountId);
            resetReconnectBackoffLocked(accountId);
            stopWebSocketInternal(accountId, true);
            scheduleReconnectLocked(
                    accountId, CREDENTIAL_UPDATE_RECONNECT_DELAY_SECONDS, true, false);
            log.info("【账号{}】新登录凭据已保存，将在{}秒后单次恢复连接",
                    accountId, CREDENTIAL_UPDATE_RECONNECT_DELAY_SECONDS);
            return true;
        });
    }

    @Override
    public boolean recoverAfterManualCaptcha(Long accountId) {
        if (accountId == null || shuttingDown || isPermanentlyStopped(accountId)) {
            return false;
        }
        return withLifecycleLock(accountId, () -> {
            int updatedRows = xianyuAccountMapper.update(null, new UpdateWrapper<XianyuAccount>()
                    .eq("id", accountId)
                    .set("websocket_enabled", 1));
            if (updatedRows <= 0) {
                return false;
            }

            cancelCaptchaProbeLocked(accountId);
            resetReconnectBackoffLocked(accountId);
            manualCaptchaRecoveryRequests.add(accountId);
            String token;
            try {
                token = tokenService.probeAccessTokenAfterManualVerification(accountId);
            } catch (CaptchaRequiredException e) {
                tokenService.restoreCaptchaWait(accountId);
                scheduleCaptchaProbeLocked(accountId);
                log.warn("【账号{}】用户确认滑块后平台仍要求验证，保留人工验证状态", accountId);
                return false;
            } catch (Exception e) {
                tokenService.restoreCaptchaWait(accountId);
                scheduleCaptchaProbeLocked(accountId);
                log.warn("【账号{}】用户确认滑块后Token探测失败: errorType={}",
                        accountId, e.getClass().getSimpleName());
                return false;
            }

            if (token == null || token.isBlank()) {
                tokenService.restoreCaptchaWait(accountId);
                scheduleCaptchaProbeLocked(accountId);
                log.warn("【账号{}】用户确认滑块后平台尚未返回有效Token，保留人工验证状态", accountId);
                return false;
            }

            tokenService.clearCaptchaWait(accountId);
            boolean stopped = stopWebSocketInternal(accountId, true);
            if (!stopped) {
                manualCaptchaRecoveryRequests.remove(accountId);
                scheduleReconnectLocked(accountId, config.getReconnectDelay(), false, false);
                log.warn("【账号{}】验证已恢复但旧连接暂时无法关闭，已安排普通重连", accountId);
                return false;
            }

            boolean success = startWebSocketWithTokenLocked(accountId, token);
            if (success) {
                manualCaptchaRecoveryRequests.remove(accountId);
                reconnectAttemptCounts.remove(accountId);
                nextReconnectAt.remove(accountId);
                log.info("【账号{}】用户确认滑块后已立即恢复WebSocket连接", accountId);
                return true;
            }

            if (isCaptchaPending(accountId)) {
                tokenService.restoreCaptchaWait(accountId);
                manualCaptchaRecoveryRequests.add(accountId);
                scheduleCaptchaProbeLocked(accountId);
                return false;
            }
            manualCaptchaRecoveryRequests.remove(accountId);
            scheduleReconnectLocked(accountId, config.getReconnectDelay(), false, false);
            return false;
        });
    }

    private void resetReconnectBackoffLocked(Long accountId) {
        cancelReconnectTaskLocked(accountId);
        reconnectAttemptCounts.remove(accountId);
    }

    private void cancelReconnectTaskLocked(Long accountId) {
        reconnectTaskGenerations
                .computeIfAbsent(accountId, ignored -> new AtomicLong())
                .incrementAndGet();
        Future<?> reconnectTask = reconnectTasks.remove(accountId);
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            log.info("重连任务已取消: accountId={}", accountId);
        }
        nextReconnectAt.remove(accountId);
    }

    private boolean startWebSocketLocked(Long accountId) {
        try {
            if (shuttingDown || isPermanentlyStopped(accountId)) {
                log.warn("账号已进入永久停止状态，拒绝启动WebSocket: accountId={}", accountId);
                return false;
            }
            if (authFailureGuard.isCoolingDown(accountId)) {
                long remainingSeconds = Math.max(1L,
                        (authFailureGuard.remainingCooldownMillis(accountId) + 999L) / 1000L);
                log.warn("账号{}认证失败保护冷却中，暂不启动WebSocket，剩余约{}秒", accountId, remainingSeconds);
                return false;
            }
            log.info("启动WebSocket连接: accountId={}", accountId);

            // 检查是否已经连接
            if (webSocketClients.containsKey(accountId)) {
                XianyuWebSocketClient existingClient = webSocketClients.get(accountId);
                if (existingClient.isConnected()) {
                    log.info("WebSocket已连接: accountId={}", accountId);
                    return true;
                } else {
                    // 关闭旧连接
                    if (!stopWebSocket(accountId)) {
                        log.error("旧WebSocket客户端无法确认关闭，拒绝创建新连接: accountId={}", accountId);
                        return false;
                    }
                }
            }

            // 获取Cookie
            String cookieStr = CredentialCipher.decrypt(accountService.getCookieByAccountId(accountId));
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("未找到账号Cookie: accountId={}", accountId);
                throw new com.feijimiao.xianyuassistant.exception.CookieNotFoundException("未找到账号Cookie，请先配置Cookie");
            }

            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieStr);
            
            // 生成设备ID（参考Python的generate_device_id）
            String unb = resolveAuthenticatedUnb(accountId, cookies);
            cookieStr = XianyuSignUtils.formatCookies(cookies);
            // 使用持久化的设备ID（如果数据库中已有则使用，否则生成新的并保存）
            String deviceId = accountService.getOrGenerateDeviceId(accountId, unb);
            if (deviceId == null || deviceId.isEmpty()) {
                log.error("获取或生成设备ID失败: accountId={}", accountId);
                throw new BusinessException("无法获取或生成设备ID");
            }
            log.info("使用设备ID: accountId={}, deviceIdLength={}", accountId, deviceId.length());
            
            // 获取accessToken（参考Python的refresh_token）
            log.info("正在获取accessToken: accountId={}", accountId);
            String accessToken = tokenService.getAccessToken(accountId);
            if (accessToken == null || accessToken.isEmpty()) {
                log.error("获取accessToken失败: accountId={}", accountId);
                log.error("无法继续WebSocket连接，请检查Cookie是否有效");
                throw new com.feijimiao.xianyuassistant.exception.TokenInvalidException("无法获取WebSocket Token，请检查Cookie是否有效");
            }
            log.info("accessToken获取成功: accountId={}, token长度={}", accountId, accessToken.length());
            
            // 调用通用连接方法
            return connectWebSocket(accountId, cookieStr, deviceId, accessToken, unb);

        } catch (CaptchaRequiredException e) {
            boolean captchaUrlPresent = e.getCaptchaUrl() != null && !e.getCaptchaUrl().isBlank();
            log.warn("启动WebSocket需要滑块验证: accountId={}, captchaUrlPresent={}",
                    accountId, captchaUrlPresent);
            scheduleCaptchaProbeLocked(accountId);
            throw e; // 重新抛出，让Controller处理
        } catch (Exception e) {
            log.error("启动WebSocket失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean startWebSocketWithToken(Long accountId, String accessToken) {
        return withLifecycleLock(accountId, () -> startWebSocketWithTokenLocked(accountId, accessToken));
    }

    private boolean startWebSocketWithTokenLocked(Long accountId, String accessToken) {
        try {
            if (isPermanentlyStopped(accountId)) {
                log.warn("账号已进入永久停止状态，拒绝使用Token启动WebSocket: accountId={}", accountId);
                return false;
            }
            log.info("========== 使用手动Token启动WebSocket连接 ==========");
            log.info("【账号{}】accountId={}", accountId, accountId);
            log.info("【账号{}】accessToken长度={}", accountId, accessToken != null ? accessToken.length() : 0);

            // 检查是否已经连接
            if (webSocketClients.containsKey(accountId)) {
                XianyuWebSocketClient existingClient = webSocketClients.get(accountId);
                if (existingClient.isConnected()) {
                    log.info("【账号{}】WebSocket已连接", accountId);
                    return true;
                } else {
                    // 关闭旧连接
                    log.info("【账号{}】关闭旧连接", accountId);
                    if (!stopWebSocket(accountId)) {
                        log.error("【账号{}】旧WebSocket客户端无法确认关闭，拒绝创建新连接", accountId);
                        return false;
                    }
                }
            }

            // 获取Cookie
            String cookieStr = CredentialCipher.decrypt(accountService.getCookieByAccountId(accountId));
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到账号Cookie", accountId);
                throw new com.feijimiao.xianyuassistant.exception.CookieNotFoundException("未找到账号Cookie，请先配置Cookie");
            }
            log.info("【账号{}】Cookie长度={}", accountId, cookieStr.length());

            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieStr);
            log.info("【账号{}】解析到{}个Cookie字段", accountId, cookies.size());
            
            // 生成设备ID
            String unb = resolveAuthenticatedUnb(accountId, cookies);
            cookieStr = XianyuSignUtils.formatCookies(cookies);
            // 使用持久化的设备ID
            String deviceId = accountService.getOrGenerateDeviceId(accountId, unb);
            if (deviceId == null || deviceId.isEmpty()) {
                log.error("【账号{}】获取或生成设备ID失败", accountId);
                throw new BusinessException("无法获取或生成设备ID");
            }
            log.info("【账号{}】设备ID长度={}", accountId, deviceId.length());
            
            log.info("【账号{}】准备调用通用连接方法（Token将在注册成功后保存）...", accountId);
            
            // 调用通用连接方法
            boolean result = connectWebSocket(accountId, cookieStr, deviceId, accessToken, unb);
            
            log.info("【账号{}】连接结果={}", accountId, result);
            log.info("========== 手动Token启动流程结束 ==========");
            
            return result;

        } catch (Exception e) {
            log.error("【账号{}】使用手动Token启动WebSocket失败: errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * QR confirmation can return the authenticated UNB separately from the
     * Set-Cookie collection. The account record is populated only after that
     * identity has been verified, so it is a valid fallback for this session.
     */
    private String resolveAuthenticatedUnb(Long accountId, Map<String, String> cookies) {
        String unb = cookies.get("unb");
        if (unb != null && !unb.isBlank()) {
            return unb;
        }

        String persistedUnb = accountService.getXianyuUserId(accountId);
        if (persistedUnb == null || persistedUnb.isBlank()) {
            log.error("Cookie和账号记录中都缺少unb字段: accountId={}", accountId);
            throw new com.feijimiao.xianyuassistant.exception.CookieExpiredException(
                    "Cookie和账号记录中都缺少unb字段，无法确认登录身份");
        }

        cookies.put("unb", persistedUnb);
        log.warn("Cookie中缺少unb，使用已验证的账号身份补全WebSocket会话: accountId={}", accountId);
        return persistedUnb;
    }

    /**
     * 通用WebSocket连接方法
     */
    private boolean connectWebSocket(Long accountId, String cookieStr, String deviceId, String accessToken, String unb) throws Exception {
        XianyuWebSocketClient client = null;
        try {
            // 构建WebSocket请求头（参考Python的WEBSOCKET_HEADERS配置）
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", cookieStr);
            headers.put("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT);
            headers.put("Origin", XianyuProtocolProfile.WEB_ORIGIN);
            headers.put("Host", XianyuProtocolProfile.WEBSOCKET_HOST);
            headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
            headers.put("Connection", "Upgrade");
            headers.put("Upgrade", "websocket");

            // 创建WebSocket客户端（参考Python的_create_websocket_connection）
            URI serverUri = new URI(WEBSOCKET_URL);
            client = createWebSocketClient(
                    serverUri, headers, String.valueOf(accountId), displayNameUtils);
            
            // 设置当前用户ID（从Cookie的unb字段获取）
            client.setMyUserId(unb);
            
            // 设置消息处理器
            client.setMessageHandler(messageHandler);

            final XianyuWebSocketClient callbackClient = client;
            
            // 设置注册成功回调（保存Token）
            final String finalAccessToken = accessToken;
            client.setOnRegistrationSuccess(() -> withLifecycleLock(accountId, () -> {
                    if (isPermanentlyStopped(accountId)
                            || webSocketClients.get(accountId) != callbackClient) {
                        log.info("【账号{}】连接已被替换或永久停止，忽略注册成功回调", accountId);
                        return;
                    }
                    log.info("【账号{}】注册成功回调被触发，开始保存Token到数据库", accountId);
                    tokenService.saveToken(accountId, finalAccessToken);
                    log.info("【账号{}】✅ Token已成功保存到数据库", accountId);
                }));
            
            // 设置Token失效回调（自动重连）
            // 参考Python: Token失效时设置connection_restart_flag=True，关闭WebSocket触发重连
            client.setOnTokenExpired(
                    () -> requestTokenExpiredReconnect(accountId, callbackClient));
            
            // 设置心跳响应回调（更新心跳响应时间）。
            // 只写 ConcurrentHashMap，不取生命周期锁：Token 刷新/浏览器恢复可能持锁
            // 30-60 秒，若心跳时间戳更新在锁上排队，健康连接会被超时判定误杀。
            client.setOnHeartbeatResponse(() -> {
                if (webSocketClients.get(accountId) == callbackClient) {
                    lastHeartbeatResponseTimes.put(
                            accountId, System.currentTimeMillis() / 1000);
                }
            });
            
            // 设置连接关闭回调（参考Python的finally块中重连逻辑）
            client.setOnConnectionClosed(() -> {
                log.warn("【账号{}】WebSocket连接被关闭，触发自动重连...", accountId);

                Boolean restartFlag = connectionRestartFlags.get(accountId);
                boolean isManualRestart = restartFlag != null && restartFlag;

                int delay = isManualRestart ? 0 : config.getReconnectDelay();
                requestReconnectFromCloseCallback(
                        accountId, callbackClient, delay, isManualRestart);
            });

            // 在握手前登记，使任何失败或超时的连接都能被后续永久停止再次确认关闭。
            webSocketClients.put(accountId, client);

            // 连接WebSocket（参考Python的connect方法）
            log.info("正在连接WebSocket: {}", WEBSOCKET_URL);
            log.debug("WebSocket请求头已构建: accountId={}, headerCount={}", accountId, headers.size());
            
            boolean connected = client.connectBlocking(10, TimeUnit.SECONDS);

            if (connected && isPermanentlyStopped(accountId)) {
                discardClient(accountId, client);
                log.info("【账号{}】连接建立期间账号已被删除，已立即关闭连接", accountId);
                return false;
            }

            if (connected) {
                // 执行WebSocket初始化流程（参考Python的init方法）
                log.info("开始WebSocket初始化流程: accountId={}", accountId);
                initializer.initialize(client, accessToken, deviceId, String.valueOf(accountId));

                if (!client.awaitRegistration(REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    if (!client.isOpen() || client.isClosed()) {
                        log.error("WebSocket注册确认超时且连接已关闭: accountId={}", accountId);
                        discardClient(accountId, client);
                        operationLogService.log(accountId,
                                OperationConstants.Type.CONNECT,
                                OperationConstants.Module.WEBSOCKET,
                                "WebSocket握手成功但未收到reg-sid注册确认",
                                OperationConstants.Status.FAIL,
                                OperationConstants.TargetType.WEBSOCKET,
                                String.valueOf(accountId),
                                null, null, "registration timeout", null);
                        return false;
                    }
                    log.warn("WebSocket注册确认超时但连接仍打开，主动关闭触发重连（避免假在线）: accountId={}", accountId);
                    // 注册超时意味着消息通道实际不可用（reg-sid 未确认），
                    // 若继续监听会出现"显示在线但收不到消息"的假在线状态，掉单风险极高。
                    // 主动关闭会触发 onClose -> 自动重连（指数退避 + 连续5次邮件通知），
                    // 避免该连接以不可用状态长期占用账号生命周期。
                    try {
                        client.close(1000, "registration timeout");
                    } catch (Exception closeEx) {
                        log.warn("主动关闭注册超时连接异常: accountId={}, errorType={}",
                                accountId, closeEx.getClass().getSimpleName());
                    }
                    return false;
                }

                // 启动心跳任务
                startHeartbeat(accountId, client);
                
                log.info("WebSocket连接成功: accountId={}", accountId);
                log.info("连接状态: isOpen={}, isClosed={}", 
                        client.isOpen(), client.isClosed());
                dispatchWebhookSafely("connection.restored", accountId, "WebSocket 连接成功");
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.CONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接成功", 
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return true;
            } else {
                log.error("WebSocket连接失败: accountId={}", accountId);
                log.error("连接状态: isOpen={}, isClosed={}", 
                        client.isOpen(), client.isClosed());

                discardClient(accountId, client);
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.CONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接失败", 
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return false;
            }
        } catch (Exception e) {
            if (client != null) {
                discardClient(accountId, client);
            }
            log.error("连接WebSocket异常: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            
            // 记录操作日志
            operationLogService.log(accountId, 
                OperationConstants.Type.CONNECT, 
                OperationConstants.Module.WEBSOCKET,
                "WebSocket连接异常: errorType=" + e.getClass().getSimpleName(),
                OperationConstants.Status.FAIL,
                OperationConstants.TargetType.WEBSOCKET, 
                String.valueOf(accountId),
                null, null, null, null);
            
            throw e;
        }
    }

    private void requestTokenExpiredReconnect(Long accountId) {
        requestTokenExpiredReconnect(accountId, webSocketClients.get(accountId));
    }

    private void requestTokenExpiredReconnect(
            Long accountId, XianyuWebSocketClient sourceClient) {
        if (shuttingDown || isPermanentlyStopped(accountId)) {
            return;
        }
        tokenExpiredRecoveryTasks.compute(accountId, (id, existingTask) -> {
            if (existingTask != null && !existingTask.isDone()) {
                return existingTask;
            }
            try {
                return reconnectExecutor.submit(() -> {
                    try {
                        withLifecycleLock(accountId,
                                () -> recoverFromExpiredTokenLocked(accountId, sourceClient));
                    } finally {
                        tokenExpiredRecoveryTasks.remove(accountId);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.debug("重连执行器已关闭，忽略Token失效回调: accountId={}", accountId);
                return null;
            }
        });
    }

    private void recoverFromExpiredTokenLocked(
            Long accountId, XianyuWebSocketClient sourceClient) {
        if (shuttingDown || isPermanentlyStopped(accountId)
                || !isAutoConnectEnabled(accountId)
                || sourceClient == null
                || webSocketClients.get(accountId) != sourceClient) {
            return;
        }
        log.warn("【账号{}】Token失效(401)，触发自动重连流程...", accountId);
        try {
            connectionRestartFlags.put(accountId, true);
            boolean stopped = stopWebSocketInternal(accountId, false, false);
            if (!stopped) {
                log.error("【账号{}】Token失效后旧连接无法确认关闭，已安排重试", accountId);
                scheduleReconnectLocked(accountId, config.getReconnectDelay(), false);
                return;
            }

            tokenService.clearToken(accountId);
            log.info("【账号{}】重新启动WebSocket连接（自动刷新Token）", accountId);
            boolean success = startWebSocketLocked(accountId);
            if (success) {
                AtomicInteger attemptCount = reconnectAttemptCounts.get(accountId);
                if (attemptCount != null) {
                    attemptCount.set(0);
                }
                log.info("【账号{}】Token失效后自动重连成功", accountId);
            } else {
                log.error("【账号{}】Token失效后自动重连失败，已安排重试", accountId);
                scheduleReconnectLocked(accountId, config.getReconnectDelay(), false);
            }
        } catch (Exception e) {
            log.error("【账号{}】Token失效自动重连异常: errorType={}",
                    accountId, e.getClass().getSimpleName());
            scheduleReconnectLocked(accountId, config.getReconnectDelay(), false);
        }
    }

    @Override
    public boolean stopWebSocket(Long accountId) {
        return withLifecycleLock(accountId, () -> stopWebSocketInternal(accountId, false));
    }

    XianyuWebSocketClient createWebSocketClient(
            URI serverUri,
            Map<String, String> headers,
            String accountId,
            com.feijimiao.xianyuassistant.utils.AccountDisplayNameUtils accountDisplayNameUtils) {
        return new XianyuWebSocketClient(serverUri, headers, accountId, accountDisplayNameUtils);
    }

    @Override
    public boolean stopWebSocketForAccountDeletion(Long accountId) {
        return withLifecycleLock(accountId, () -> {
            permanentlyStoppedAccounts.add(accountId);
            boolean stopped = stopWebSocketInternal(accountId, true);
            reconnectAttemptCounts.remove(accountId);
            lastDisconnectNotifyTimes.remove(accountId);
            return stopped;
        });
    }

    private boolean stopWebSocketInternal(Long accountId, boolean noClientIsSuccess) {
        return stopWebSocketInternal(accountId, noClientIsSuccess, true);
    }

    private boolean stopWebSocketInternal(
            Long accountId, boolean noClientIsSuccess, boolean cancelTokenRecovery) {
        XianyuWebSocketClient client = null;
        try {
            log.info("停止WebSocket连接: accountId={}", accountId);

            if (cancelTokenRecovery) {
                cancelTokenExpiredRecovery(accountId);
            }

            // 关闭WebSocket连接
            client = webSocketClients.get(accountId);
            if (client != null) {
                // 标记为主动关闭，防止onClose回调触发自动重连
                // 重连由调用方自行决定是否需要
                client.setIntentionalClose(true);
                if (!client.closeWithin(CLIENT_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    // 关闭失败时保留原监控链路，避免留下既未关闭也无人巡检的僵尸连接。
                    client.setIntentionalClose(false);
                    log.error("WebSocket连接未能在限定时间内关闭: accountId={}", accountId);
                    return false;
                }
                client.setOnConnectionClosed(null);
                client.setOnTokenExpired(null);
                client.setOnRegistrationSuccess(null);
                client.setOnHeartbeatResponse(null);
                stopHeartbeat(accountId);
                webSocketClients.remove(accountId, client);
                log.info("WebSocket连接已关闭: accountId={}", accountId);
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.DISCONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接已关闭", 
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return true;
            } else {
                stopHeartbeat(accountId);
                log.warn("WebSocket连接不存在: accountId={}", accountId);
                return noClientIsSuccess;
            }

        } catch (Exception e) {
            if (client != null && webSocketClients.get(accountId) == client) {
                client.setIntentionalClose(false);
            }
            log.error("停止WebSocket失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean isConnected(Long accountId) {
        return withLifecycleLock(accountId, () -> {
            if (isPermanentlyStopped(accountId)) {
                return false;
            }
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            return client != null && client.isConnected();
        });
    }

    @Override
    public void stopAllWebSockets() {
        log.info("停止所有WebSocket连接");

        for (Long accountId : webSocketClients.keySet()) {
            stopWebSocket(accountId);
        }

        // 注意：heartbeatScheduler/tokenRefreshScheduler 是全服务共享的调度池，
        // 这里只停连接与任务；调度器关闭统一收敛到 cleanup()，
        // 否则运行期调用本方法后所有账号的心跳/巡检任务将永久无法再启动。
    }

    /**
     * 启动心跳任务
     * 增强功能：心跳超时检测（完全对齐Python逻辑）
     * 
     * Python心跳逻辑：
     * 1. 每隔heartbeat_interval秒发送一次心跳
     * 2. 检查上次心跳响应时间，如果超过(heartbeat_interval + heartbeat_timeout)则认为连接断开
     * 3. 心跳循环break后，外层while True循环会自动重连
     */
    private void startHeartbeat(Long accountId, XianyuWebSocketClient client) {
        // 初始化心跳响应时间（秒级时间戳，对齐Python）
        long currentTime = System.currentTimeMillis() / 1000;
        lastHeartbeatResponseTimes.put(accountId, currentTime);
        lastHeartbeatSendTimes.put(accountId, currentTime);
        
        // 心跳发送任务（参考Python的heartbeat_loop）
        // 立即发送第一次心跳,防止连接空闲被关闭
        try {
            if (client.isConnected()) {
                client.sendHeartbeat();
                log.info("【账号{}】已发送初始心跳", accountId);
            }
        } catch (Exception e) {
            log.error("发送初始心跳失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
        }
        
        // 参考Python: heartbeat_loop 中每1秒检查一次
        // 心跳不持有账号生命周期锁：发送/重连/Token 刷新会在锁内做最长可达数十秒的 IO，
        // 若心跳在此排队，共享心跳线程池会被占满，导致健康连接被误判超时并引发重连风暴。
        // 连接代际用 webSocketClients.get(accountId) == client 判断，足以避免操作旧连接。
        ScheduledFuture<?> heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
            () -> {
                try {
                    if (isPermanentlyStopped(accountId) || webSocketClients.get(accountId) != client) {
                        return;
                    }
                    long now = System.currentTimeMillis() / 1000;
                    
                    // 参考Python: 每隔heartbeat_interval秒发送一次心跳
                    Long lastSendTime = lastHeartbeatSendTimes.get(accountId);
                    if (lastSendTime == null || now - lastSendTime >= config.getHeartbeatInterval()) {
                        if (client.isConnected()) {
                            client.sendHeartbeat();
                            lastHeartbeatSendTimes.put(accountId, now);
                            log.debug("【账号{}】心跳已发送", accountId);
                        }
                    }
                    
                    // 参考Python: 检查上次心跳响应时间
                    // Python: if (current_time - self.last_heartbeat_response) > (self.heartbeat_interval + self.heartbeat_timeout):
                    Long lastResponseTime = lastHeartbeatResponseTimes.get(accountId);
                    if (lastResponseTime != null) {
                        long timeout = config.getHeartbeatInterval() + config.getHeartbeatTimeout();
                        
                        if (now - lastResponseTime > timeout) {
                            log.warn("【账号{}】心跳响应超时（{}秒无响应，超时阈值{}秒），连接可能已断开",
                                    accountId, now - lastResponseTime, timeout);
                            // 参考Python: heartbeat_loop break，触发外层重连
                            // 断线处理需要与发送/重连互斥，但用 tryLock 而非阻塞：
                            // 拿不到锁说明生命周期操作正在进行，下一秒再判定即可，避免阻塞心跳线程。
                            ReentrantLock accountLock = lifecycleLock(accountId);
                            if (accountLock.tryLock()) {
                                try {
                                    handleConnectionLost(accountId);
                                } finally {
                                    accountLock.unlock();
                                }
                            } else {
                                log.debug("【账号{}】生命周期操作进行中，心跳超时处理延后", accountId);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("【账号{}】心跳任务异常: errorType={}",
                            accountId, e.getClass().getSimpleName());
                }
            },
            1, 1, TimeUnit.SECONDS  // 参考Python: 每秒检查一次
        );
        
        heartbeatTasks.put(accountId, heartbeatTask);
        log.info("心跳任务已启动: accountId={}, 心跳间隔{}秒, 超时阈值{}+{}秒", 
                accountId, config.getHeartbeatInterval(), config.getHeartbeatInterval(), config.getHeartbeatTimeout());
        
        // 启动Token自动刷新任务（参考Python的token_refresh_loop）
        startTokenRefresh(accountId);
    }
    
    /**
     * 每分钟检查数据库中的Token过期时间。只有进入30分钟安全窗口后才刷新，
     * 避免按进程启动时间强制断开仍然健康的连接。
     */
    private void startTokenRefresh(Long accountId) {
        if (isPermanentlyStopped(accountId)) {
            return;
        }
        log.info("【账号{}】Token到期巡检已启动: 每分钟检查，提前30分钟刷新", accountId);

        ScheduledFuture<?> tokenRefreshTask = tokenRefreshScheduler.scheduleAtFixedRate(
            () -> withLifecycleLock(accountId, () -> {
                try {
                    if (isPermanentlyStopped(accountId)) {
                        return;
                    }

                    ScheduledFuture<?> delayedRetry = tokenRefreshRetryTasks.get(accountId);
                    if (delayedRetry != null && !delayedRetry.isDone()) {
                        log.debug("【账号{}】Token刷新已进入延迟退避，本分钟巡检跳过", accountId);
                        return;
                    }

                    PersistedTokenState tokenState = readPersistedTokenState(accountId);
                    if (tokenState == PersistedTokenState.HEALTHY
                            || tokenState == PersistedTokenState.UNKNOWN) {
                        return;
                    }

                    log.info("【账号{}】数据库Token已进入30分钟刷新窗口，准备刷新并重连", accountId);
                    refreshTokenAndReconnectLocked(accountId);
                } catch (Exception e) {
                    log.error("【账号{}】Token刷新检查失败: errorType={}",
                            accountId, e.getClass().getSimpleName());
                }
            }),
            60, 60, TimeUnit.SECONDS  // 参考Python: 每分钟检查一次
        );
        
        tokenRefreshTasks.put(accountId, tokenRefreshTask);
    }
    
    /**
     * 刷新Token并重连
     * 参考Python的refresh_token和重连逻辑
     * 
     * Python逻辑：
     * 1. 刷新Token
     * 2. 设置connection_restart_flag = True
     * 3. 关闭当前WebSocket连接（触发重连）
     * 4. Token刷新失败时，在token_retry_interval后重试
     */
    private void refreshTokenAndReconnect(Long accountId) {
        withLifecycleLock(accountId, () -> refreshTokenAndReconnectLocked(accountId));
    }

    @Override
    public boolean isReconnecting(Long accountId) {
        Future<?> task = reconnectTasks.get(accountId);
        return task != null && !task.isDone() && !isPermanentlyStopped(accountId);
    }

    @Override
    public int getReconnectAttempt(Long accountId) {
        AtomicInteger attempt = reconnectAttemptCounts.get(accountId);
        return attempt == null ? 0 : Math.max(0, attempt.get());
    }

    @Override
    public Long getNextReconnectAt(Long accountId) {
        return isReconnecting(accountId) ? nextReconnectAt.get(accountId) : null;
    }

    private void refreshTokenAndReconnectLocked(Long accountId) {
        if (shuttingDown || isPermanentlyStopped(accountId)
                || !isAutoConnectEnabled(accountId)) {
            return;
        }
        try {
            if (isCaptchaPending(accountId)) {
                scheduleCaptchaProbeLocked(accountId);
                return;
            }

            PersistedTokenState tokenState = readPersistedTokenState(accountId);
            if (tokenState == PersistedTokenState.HEALTHY) {
                connectionRestartFlags.remove(accountId);
                log.debug("【账号{}】数据库Token仍在30分钟安全窗口外，跳过刷新", accountId);
                return;
            }
            if (tokenState == PersistedTokenState.UNKNOWN) {
                log.warn("【账号{}】暂时无法读取数据库Token状态，保留现有连接并稍后重试", accountId);
                scheduleTokenRefreshRetryLocked(accountId);
                return;
            }

            log.info("【账号{}】开始刷新Token并重连...", accountId);
            
            // 参考Python: 刷新Token前先从数据库重新加载最新Cookie
            // 避免使用过期的Cookie导致刷新必然失败
            try {
                if (cookieRefreshService != null) {
                    log.info("【账号{}】刷新Token前先检查Cookie登录状态...", accountId);
                    // 使用静默检查，不记录操作日志（避免频繁记录）
                    boolean cookieOk = cookieRefreshService.checkLoginStatusQuietly(accountId);
                    if (!cookieOk) {
                        log.warn("【账号{}】Cookie已失效(hasLogin)，触发浏览器兜底刷新Cookie（对齐Python的_refresh_cookies_via_browser）...", accountId);
                        boolean browserRefreshOk = cookieRefreshService.refreshCookie(accountId);
                        if (browserRefreshOk) {
                            log.info("【账号{}】浏览器兜底刷新Cookie成功，继续重连", accountId);
                        } else {
                            log.error("【账号{}】hasLogin和浏览器兜底刷新Cookie均失败，Cookie可能已彻底过期", accountId);
                        }
                    }
                }
            } catch (CaptchaRequiredException e) {
                throw e;
            } catch (Exception e) {
                log.warn("【账号{}】刷新Token前Cookie检查/兜底刷新异常，继续尝试重连: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }

            if (isPermanentlyStopped(accountId)) {
                return;
            }

            // 先无损获取替代Token；失败时保留仍可工作的旧连接和原始过期时间。
            String replacementToken = tokenService.refreshToken(accountId);
            if (replacementToken == null || replacementToken.isBlank()) {
                log.error("【账号{}】替代Token获取失败，保留当前连接并延迟重试", accountId);
                scheduleTokenRefreshRetryLocked(accountId);
                return;
            }

            connectionRestartFlags.put(accountId, true);
            if (!stopWebSocketInternal(accountId, true)) {
                connectionRestartFlags.remove(accountId);
                log.warn("【账号{}】替代Token已保存，但旧连接暂时无法关闭；继续保留旧连接", accountId);
                return;
            }

            if (isPermanentlyStopped(accountId)) {
                return;
            }
            
            boolean success = startWebSocketWithTokenLocked(accountId, replacementToken);
            
            if (success) {
                // 更新Token刷新时间
                lastTokenRefreshTimes.put(accountId, System.currentTimeMillis() / 1000);
                // 重置重连计数
                AtomicInteger attemptCount = reconnectAttemptCounts.get(accountId);
                if (attemptCount != null) {
                    attemptCount.set(0);
                }
                log.info("【账号{}】✅ Token刷新并重连成功", accountId);
            } else {
                log.error("【账号{}】❌ Token刷新并重连失败，将在{}秒后重试", 
                        accountId, config.getTokenRetryInterval());
                
                scheduleTokenRefreshRetry(accountId);
            }
        } catch (CaptchaRequiredException e) {
            log.warn("【账号{}】Token刷新触发滑块验证，停止普通Token重试并转为低频探测", accountId);
            scheduleCaptchaProbeLocked(accountId);
        } catch (Exception e) {
            log.error("【账号{}】Token刷新并重连异常，将在{}秒后重试: errorType={}",
                    accountId, config.getTokenRetryInterval(), e.getClass().getSimpleName());

            scheduleTokenRefreshRetry(accountId);
        }
    }

    private PersistedTokenState readPersistedTokenState(Long accountId) {
        try {
            XianyuCookie cookie = xianyuCookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1"));
            if (cookie == null
                    || cookie.getWebsocketToken() == null
                    || cookie.getWebsocketToken().isBlank()
                    || cookie.getTokenExpireTime() == null) {
                return PersistedTokenState.REFRESH_REQUIRED;
            }
            long refreshAt = System.currentTimeMillis() + TOKEN_REFRESH_SAFETY_WINDOW_MILLIS;
            return cookie.getTokenExpireTime() > refreshAt
                    ? PersistedTokenState.HEALTHY
                    : PersistedTokenState.REFRESH_REQUIRED;
        } catch (Exception e) {
            log.warn("【账号{}】读取数据库Token过期时间失败，保留现有连接: errorType={}",
                    accountId, e.getClass().getSimpleName());
            return PersistedTokenState.UNKNOWN;
        }
    }

    private void cancelTokenExpiredRecovery(Long accountId) {
        Future<?> recoveryTask = tokenExpiredRecoveryTasks.remove(accountId);
        if (recoveryTask != null) {
            recoveryTask.cancel(false);
        }
    }

    private void discardClient(Long accountId, XianyuWebSocketClient client) {
        if (webSocketClients.get(accountId) == client) {
            stopHeartbeat(accountId);
        }
        try {
            client.setOnConnectionClosed(null);
            client.setIntentionalClose(true);
            if (!client.closeWithin(CLIENT_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("丢弃WebSocket客户端时未能在限定时间内关闭: accountId={}", accountId);
                return;
            }
            webSocketClients.remove(accountId, client);
        } catch (Exception e) {
            log.warn("丢弃WebSocket客户端失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
        }
    }

    private void scheduleTokenRefreshRetry(Long accountId) {
        withLifecycleLock(accountId, () -> scheduleTokenRefreshRetryLocked(accountId));
    }

    private void scheduleTokenRefreshRetryLocked(Long accountId) {
        if (shuttingDown || isPermanentlyStopped(accountId)
                || !isAutoConnectEnabled(accountId)) {
            return;
        }
        if (isCaptchaPending(accountId)) {
            scheduleCaptchaProbeLocked(accountId);
            return;
        }

        ScheduledFuture<?> existingTask = tokenRefreshRetryTasks.get(accountId);
        if (existingTask != null && !existingTask.isDone()) {
            return;
        }

        ScheduledFuture<?> retryTask = reconnectExecutor.schedule(
                () -> withLifecycleLock(accountId, () -> {
                    tokenRefreshRetryTasks.remove(accountId);
                    if (shuttingDown || isPermanentlyStopped(accountId)
                            || !isAutoConnectEnabled(accountId)) {
                        return;
                    }
                    log.info("【账号{}】Token刷新重试间隔已到，开始重试...", accountId);
                    refreshTokenAndReconnect(accountId);
                }),
                config.getTokenRetryInterval(), TimeUnit.SECONDS);
        tokenRefreshRetryTasks.put(accountId, retryTask);
    }

    private void scheduleCaptchaProbeLocked(Long accountId) {
        if (shuttingDown || isPermanentlyStopped(accountId)) {
            return;
        }

        ScheduledFuture<?> existingTask = captchaProbeTasks.get(accountId);
        if (existingTask != null && !existingTask.isDone()) {
            return;
        }

        ScheduledFuture<?> tokenRetryTask = tokenRefreshRetryTasks.remove(accountId);
        if (tokenRetryTask != null) {
            tokenRetryTask.cancel(false);
        }
        cancelReconnectTaskLocked(accountId);

        long generation = captchaProbeTaskGenerations
                .computeIfAbsent(accountId, ignored -> new AtomicLong())
                .incrementAndGet();
        AtomicInteger attemptCounter = captchaProbeAttemptCounts
                .computeIfAbsent(accountId, ignored -> new AtomicInteger());
        boolean manualRecovery = manualCaptchaRecoveryRequests.contains(accountId);
        long[] backoff = manualRecovery
                ? MANUAL_CAPTCHA_PROBE_BACKOFF_SECONDS
                : CAPTCHA_PROBE_BACKOFF_SECONDS;
        int attemptIndex = Math.min(attemptCounter.get(), backoff.length - 1);
        long delaySeconds = calculateCaptchaProbeDelaySeconds(backoff, attemptIndex);
        AtomicReference<ScheduledFuture<?>> taskReference = new AtomicReference<>();
        try {
            ScheduledFuture<?> probeTask = reconnectExecutor.schedule(
                    () -> withLifecycleLock(accountId, () -> {
                        AtomicLong currentGeneration = captchaProbeTaskGenerations.get(accountId);
                        if (currentGeneration == null || currentGeneration.get() != generation) {
                            return;
                        }
                        captchaProbeTasks.remove(accountId, taskReference.get());
                        if (shuttingDown || isPermanentlyStopped(accountId)
                                || !isAutoConnectEnabled(accountId)) {
                            return;
                        }

                        log.info("【账号{}】滑块低频探测开始，尝试恢复连接", accountId);
                        try {
                            String replacementToken = tokenService.probeAccessToken(accountId);
                            if (replacementToken == null || replacementToken.isBlank()) {
                                scheduleCaptchaProbeLocked(accountId);
                                return;
                            }

                            connectionRestartFlags.put(accountId, true);
                            if (!stopWebSocketInternal(accountId, true)) {
                                connectionRestartFlags.remove(accountId);
                                log.warn("【账号{}】验证已恢复且新Token已保存，保留当前连接", accountId);
                                reconnectAttemptCounts.remove(accountId);
                                return;
                            }
                            boolean success = startWebSocketWithTokenLocked(accountId, replacementToken);
                            if (success) {
                                manualCaptchaRecoveryRequests.remove(accountId);
                                reconnectAttemptCounts.remove(accountId);
                                log.info("【账号{}】滑块低频探测已恢复连接", accountId);
                            } else if (isCaptchaPending(accountId)) {
                                scheduleCaptchaProbeLocked(accountId);
                            } else {
                                manualCaptchaRecoveryRequests.remove(accountId);
                                scheduleReconnectLocked(
                                        accountId, config.getReconnectDelay(), false, false);
                            }
                        } catch (CaptchaRequiredException e) {
                            scheduleCaptchaProbeLocked(accountId);
                        }
                    }),
                    delaySeconds,
                    TimeUnit.SECONDS);
            taskReference.set(probeTask);
            captchaProbeTasks.put(accountId, probeTask);
            attemptCounter.updateAndGet(current -> Math.min(
                    current + 1, backoff.length - 1));
            if (manualRecovery && attemptIndex >= MANUAL_CAPTCHA_PROBE_BACKOFF_SECONDS.length - 1) {
                // The short post-return window has ended; subsequent probes use
                // the normal 30-minute-to-6-hour backoff.
                manualCaptchaRecoveryRequests.remove(accountId);
                attemptCounter.set(0);
            }
            log.info("【账号{}】已安排{}滑块探测，延迟{}秒（第{}次）",
                    accountId, manualRecovery ? "验证后短时" : "低频",
                    delaySeconds, attemptIndex + 1);
        } catch (RejectedExecutionException e) {
            log.debug("重连执行器已关闭，忽略滑块低频探测: accountId={}", accountId);
        }
    }

    private long calculateCaptchaProbeDelaySeconds(int attemptIndex) {
        return calculateCaptchaProbeDelaySeconds(CAPTCHA_PROBE_BACKOFF_SECONDS, attemptIndex);
    }

    private long calculateCaptchaProbeDelaySeconds(long[] backoffSeconds, int attemptIndex) {
        int boundedIndex = Math.max(0,
                Math.min(attemptIndex, backoffSeconds.length - 1));
        long baseDelay = backoffSeconds[boundedIndex];
        long jitterWindow = Math.max(1L, baseDelay * CAPTCHA_PROBE_JITTER_PERCENT / 100L);
        return ThreadLocalRandom.current().nextLong(baseDelay, baseDelay + jitterWindow + 1L);
    }

    private void cancelCaptchaProbeLocked(Long accountId) {
        captchaProbeTaskGenerations
                .computeIfAbsent(accountId, ignored -> new AtomicLong())
                .incrementAndGet();
        ScheduledFuture<?> probeTask = captchaProbeTasks.remove(accountId);
        if (probeTask != null) {
            probeTask.cancel(false);
            log.info("滑块低频探测任务已取消: accountId={}", accountId);
        }
        captchaProbeAttemptCounts.remove(accountId);
    }
    
    /**
     * 处理连接丢失
     * 参考Python的连接重连逻辑
     */
    private void handleConnectionLost(Long accountId) {
        // 心跳任务每秒检查一次。首次超时后必须先停掉该任务，
        // 否则同一失联连接会每秒重复创建重连计划并造成重连日志风暴。
        Future<?> existingTask = reconnectTasks.get(accountId);
        if (existingTask != null && !existingTask.isDone()) {
            log.debug("【账号{}】连接丢失已存在重连任务，本次心跳检查跳过", accountId);
            return;
        }

        log.warn("【账号{}】检测到连接丢失（心跳超时），停止旧心跳并准备重连...", accountId);
        // 断连告警（去重/抑制由 AlertService 处理）
        if (alertService != null) {
            try {
                alertService.checkConnectionLost(accountId);
            } catch (Exception e) {
                log.warn("【账号{}】断连告警持久化失败: errorType={}", accountId, e.getClass().getSimpleName());
            }
        }
        dispatchWebhookSafely("connection.lost", accountId, "WebSocket 连接断开，开始自动重连");
        stopHeartbeat(accountId);
        scheduleReconnect(accountId, config.getReconnectDelay(), false);
    }

    /**
     * Webhook 连接状态事件（最小接线：只传 accountId，不携带 Cookie/Token 等敏感信息）。
     */
    private void dispatchWebhookSafely(String event, Long accountId, String detail) {
        try {
            if (webhookService != null) {
                webhookService.dispatch(event, java.util.Map.of(
                        "accountId", accountId,
                        "detail", detail != null ? detail : ""));
            }
        } catch (Exception e) {
            log.warn("Webhook 事件推送失败: event={}, accountId={}, errorType={}",
                    event, accountId, e.getClass().getSimpleName());
        }
    }
    
    /**
     * 调度重连任务
     * 参考Python的main()方法中while True无限重连循环
     * 
     * 关键改进（对齐Python逻辑）：
     * 1. 防止重复重连：同一账号同时只有一个重连任务
     * 2. 指数退避：重连失败后延迟逐渐增加
     * 3. 重连成功后重置计数
     * 
     * @param accountId 账号ID
     * @param delaySeconds 延迟秒数
     * @param isManualRestart 是否主动重启（Token刷新等）
     */
    private void scheduleReconnect(Long accountId, int delaySeconds, boolean isManualRestart) {
        scheduleReconnect(accountId, delaySeconds, isManualRestart, false);
    }

    private void scheduleReconnect(
            Long accountId, int delaySeconds, boolean isManualRestart, boolean onlyIfDisconnected) {
        withLifecycleLock(accountId,
                () -> scheduleReconnectLocked(
                        accountId, delaySeconds, isManualRestart, onlyIfDisconnected));
    }

    private void requestReconnectFromCloseCallback(
            Long accountId, int delaySeconds, boolean isManualRestart) {
        requestReconnectFromCloseCallback(
                accountId, webSocketClients.get(accountId), delaySeconds, isManualRestart);
    }

    private void requestReconnectFromCloseCallback(
            Long accountId,
            XianyuWebSocketClient sourceClient,
            int delaySeconds,
            boolean isManualRestart) {
        if (shuttingDown || isPermanentlyStopped(accountId)) {
            return;
        }

        ReentrantLock lock = lifecycleLock(accountId);
        if (lock.tryLock()) {
            try {
                XianyuWebSocketClient currentClient = webSocketClients.get(accountId);
                if (sourceClient != null && currentClient != sourceClient) {
                    return;
                }
                scheduleReconnectLocked(accountId, delaySeconds, isManualRestart, true);
            } finally {
                lock.unlock();
            }
            return;
        }

        try {
            reconnectExecutor.execute(() -> withLifecycleLock(accountId, () -> {
                XianyuWebSocketClient currentClient = webSocketClients.get(accountId);
                if (sourceClient == null || currentClient == sourceClient) {
                    scheduleReconnectLocked(accountId, delaySeconds, isManualRestart, true);
                }
            }));
        } catch (RejectedExecutionException e) {
            log.debug("重连执行器已关闭，忽略连接关闭回调: accountId={}", accountId);
        }
    }

    private void scheduleReconnectLocked(Long accountId, int delaySeconds, boolean isManualRestart) {
        scheduleReconnectLocked(accountId, delaySeconds, isManualRestart, false);
    }

    private void scheduleReconnectLocked(
            Long accountId, int delaySeconds, boolean isManualRestart, boolean onlyIfDisconnected) {
        if (shuttingDown || isPermanentlyStopped(accountId)
                || !isAutoConnectEnabled(accountId)) {
            return;
        }
        // 滑块验证必须由用户完成；验证等待期间暂停普通重连，避免重复请求触发风控。
        if (isCaptchaPending(accountId)) {
            scheduleCaptchaProbeLocked(accountId);
            reconnectAttemptCounts.remove(accountId);
            log.info("【账号{}】正在等待人工滑块验证，普通重连已转为低频探测", accountId);
            return;
        }
        long cooldownRemaining = authFailureGuard.remainingCooldownMillis(accountId);
        if (cooldownRemaining > 0) {
            int cooldownSeconds = (int) Math.min(Integer.MAX_VALUE,
                    (cooldownRemaining + 999L) / 1000L);
            delaySeconds = Math.max(delaySeconds, cooldownSeconds);
            log.info("账号{}处于认证失败冷却，重连任务将延后约{}秒", accountId, cooldownSeconds);
        }
        // 取消已有的重连任务（防止重复）
        Future<?> existingTask = reconnectTasks.get(accountId);
        if (existingTask != null && !existingTask.isDone()) {
            log.debug("【账号{}】已有重连任务在执行，跳过", accountId);
            return;
        }
        
        // 重置重启标志
        if (isManualRestart) {
            connectionRestartFlags.put(accountId, false);
        }
        
        // 获取/初始化重连次数
        AtomicInteger attemptCount = reconnectAttemptCounts.computeIfAbsent(accountId, k -> new AtomicInteger(0));
        
        // 参考Python: 无限重连，但使用指数退避
        int currentAttempt = attemptCount.incrementAndGet();
        // 指数退避: 5s, 10s, 20s, 40s, 60s, 60s, ... 最大60秒
        int actualDelay = isManualRestart ? delaySeconds : 
                Math.min(delaySeconds * (int) Math.pow(2, Math.min(currentAttempt - 1, 4)), 60);
        long taskGeneration = reconnectTaskGenerations
                .computeIfAbsent(accountId, ignored -> new AtomicLong())
                .incrementAndGet();
        
        log.info("【账号{}】计划{}秒后执行重连（第{}次尝试）...", accountId, actualDelay, currentAttempt);
        nextReconnectAt.put(accountId, System.currentTimeMillis() + actualDelay * 1000L);
        
        ScheduledFuture<?> reconnectTask;
        try {
            reconnectTask = reconnectExecutor.schedule(
                     () -> withLifecycleLock(accountId, () -> {
            try {
                AtomicLong currentGeneration = reconnectTaskGenerations.get(accountId);
                if (currentGeneration == null || currentGeneration.get() != taskGeneration) {
                    return;
                }
                reconnectTasks.remove(accountId);
                nextReconnectAt.remove(accountId);

                if (shuttingDown || isPermanentlyStopped(accountId)
                        || !isAutoConnectEnabled(accountId)) {
                    attemptCount.set(0);
                    return;
                }

                if (isCaptchaPending(accountId)) {
                    scheduleCaptchaProbeLocked(accountId);
                    log.info("【账号{}】检测到滑块验证等待状态，本次自动重连转为低频探测", accountId);
                    return;
                }

                if (authFailureGuard.isCoolingDown(accountId)) {
                    scheduleReconnect(accountId, (int) Math.max(1L,
                            (authFailureGuard.remainingCooldownMillis(accountId) + 999L) / 1000L),
                            false, onlyIfDisconnected);
                    return;
                }

                if (onlyIfDisconnected) {
                    XianyuWebSocketClient currentClient = webSocketClients.get(accountId);
                    if (currentClient != null && currentClient.isConnected()) {
                        attemptCount.set(0);
                        log.debug("账号已由其他流程恢复连接，取消本次保活重连: accountId={}", accountId);
                        return;
                    }
                    if (!isEligibleForAutomaticConnection(accountId)) {
                        attemptCount.set(0);
                        log.info("账号或Cookie已不符合自动连接条件，停止保活重连: accountId={}", accountId);
                        return;
                    }
                }

                // 旧客户端必须先确认关闭，否则不能将它误当成新连接成功。
                if (!stopWebSocketInternal(accountId, true)) {
                    log.error("【账号{}】旧WebSocket连接无法确认关闭，将继续重试", accountId);
                    if (currentAttempt >= RECONNECT_NOTIFY_THRESHOLD) {
                        triggerWsDisconnectNotify(accountId);
                    }
                    scheduleReconnectLocked(
                            accountId, config.getReconnectDelay(), false, onlyIfDisconnected);
                    return;
                }

                if (isPermanentlyStopped(accountId)) {
                    return;
                }
                
                // 参考Python: 重连前先刷新Cookie（hasLogin保活）
                try {
                    if (cookieRefreshService != null) {
                        log.info("【账号{}】重连前先检查Cookie登录状态...", accountId);
                        // 使用静默检查，不记录操作日志（避免频繁记录）
                        boolean cookieOk = cookieRefreshService.checkLoginStatusQuietly(accountId);
                        if (!cookieOk) {
                            log.warn("【账号{}】Cookie已失效(hasLogin)，重连前触发浏览器兜底刷新Cookie（对齐Python）...", accountId);
                            boolean browserRefreshOk = cookieRefreshService.refreshCookie(accountId);
                            if (browserRefreshOk) {
                                log.info("【账号{}】浏览器兜底刷新Cookie成功，继续重连", accountId);
                            } else {
                                log.error("【账号{}】hasLogin和浏览器兜底刷新Cookie均失败，重连可能失败", accountId);
                            }
                        }
                        }
                    } catch (CaptchaRequiredException e) {
                        throw e;
                    } catch (Exception e) {
                        log.warn("【账号{}】重连前Cookie检查/兜底刷新异常，继续尝试重连: errorType={}",
                            accountId, e.getClass().getSimpleName());
                }

                if (isPermanentlyStopped(accountId)) {
                    return;
                }
                
                // 重新启动连接
                boolean success = startWebSocket(accountId);
                
                if (success) {
                    // 重连成功，重置计数
                    attemptCount.set(0);
                    log.info("【账号{}】✅ 重连成功", accountId);
                    dispatchWebhookSafely("connection.restored", accountId, "WebSocket 连接已恢复");
                    
                    operationLogService.log(accountId, 
                        OperationConstants.Type.RECONNECT, 
                        OperationConstants.Module.WEBSOCKET,
                        isManualRestart ? "主动重启连接成功" : "异常断开后重连成功", 
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.WEBSOCKET, 
                        String.valueOf(accountId),
                        null, null, null, null);
                } else {
                    log.error("【账号{}】❌ 重连失败（第{}次），将继续尝试...", accountId, currentAttempt);
                    
                    operationLogService.log(accountId, 
                        OperationConstants.Type.RECONNECT, 
                        OperationConstants.Module.WEBSOCKET,
                        "重连失败（第" + currentAttempt + "次）", 
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.WEBSOCKET, 
                        String.valueOf(accountId),
                        null, null, null, null);
                    
                    // 重连失败达到阈值时触发邮件通知
                    if (currentAttempt >= RECONNECT_NOTIFY_THRESHOLD) {
                        triggerWsDisconnectNotify(accountId);
                    }
                    
                    // 参考Python: 重连失败后继续尝试（while True循环）
                    scheduleReconnect(
                            accountId, config.getReconnectDelay(), false, onlyIfDisconnected);
                }
            } catch (CaptchaRequiredException e) {
                // 验证期间不再把滑块异常当作普通网络失败循环重试。
                nextReconnectAt.remove(accountId);
                reconnectAttemptCounts.remove(accountId);
                scheduleCaptchaProbeLocked(accountId);
                log.warn("【账号{}】重连触发滑块验证，已转为低频探测", accountId);
            } catch (Exception e) {
                log.error("【账号{}】重连异常，将继续尝试: errorType={}",
                        accountId, e.getClass().getSimpleName());
                if (currentAttempt >= RECONNECT_NOTIFY_THRESHOLD) {
                    triggerWsDisconnectNotify(accountId);
                }
                scheduleReconnect(accountId, config.getReconnectDelay(), false, onlyIfDisconnected);
            }
            }), actualDelay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            nextReconnectAt.remove(accountId);
            log.debug("重连执行器已关闭，忽略重连调度: accountId={}", accountId);
            return;
        }
        
        reconnectTasks.put(accountId, reconnectTask);
    }
    
    /**
     * 更新心跳响应时间
     * 由消息处理器调用
     */
    public void updateHeartbeatResponseTime(Long accountId) {
        withLifecycleLock(accountId, () -> {
            if (!isPermanentlyStopped(accountId)) {
                lastHeartbeatResponseTimes.put(accountId, System.currentTimeMillis() / 1000);
            }
        });
    }

    /**
     * 停止心跳任务
     */
    private void stopHeartbeat(Long accountId) {
        // 停止心跳任务
        ScheduledFuture<?> heartbeatTask = heartbeatTasks.remove(accountId);
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            log.info("心跳任务已停止: accountId={}", accountId);
        }
        
        // 停止Token刷新任务
        ScheduledFuture<?> tokenRefreshTask = tokenRefreshTasks.remove(accountId);
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(false);
            log.info("Token刷新任务已停止: accountId={}", accountId);
        }
        
        // 取消重连任务，并使已经进入执行器但尚未取得生命周期锁的旧任务失效。
        cancelReconnectTaskLocked(accountId);

        ScheduledFuture<?> tokenRefreshRetryTask = tokenRefreshRetryTasks.remove(accountId);
        if (tokenRefreshRetryTask != null) {
            tokenRefreshRetryTask.cancel(false);
            log.info("Token刷新重试任务已取消: accountId={}", accountId);
        }

        cancelCaptchaProbeLocked(accountId);
        
        // 清理状态
        lastHeartbeatResponseTimes.remove(accountId);
        lastHeartbeatSendTimes.remove(accountId);
        lastTokenRefreshTimes.remove(accountId);
        connectionRestartFlags.remove(accountId);
    }

    private boolean isCaptchaPending(Long accountId) {
        return tokenService != null && tokenService.isCaptchaPending(accountId);
    }

    private boolean isAutoConnectEnabled(Long accountId) {
        try {
            XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            return account != null && !Integer.valueOf(0).equals(account.getWebsocketEnabled());
        } catch (Exception e) {
            log.warn("检查WebSocket自动连接开关失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isEligibleForAutomaticConnection(Long accountId) {
        try {
            XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            if (account == null
                    || (!Integer.valueOf(1).equals(account.getStatus())
                    && !Integer.valueOf(-2).equals(account.getStatus()))
                    || Integer.valueOf(0).equals(account.getWebsocketEnabled())) {
                return false;
            }

            XianyuCookie cookie = xianyuCookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1"));
            return cookie != null
                    && Integer.valueOf(1).equals(cookie.getCookieStatus())
                    && cookie.getCookieText() != null
                    && !cookie.getCookieText().isBlank();
        } catch (Exception e) {
            log.warn("检查WebSocket自动连接资格失败: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isPermanentlyStopped(Long accountId) {
        if (!permanentlyStoppedAccounts.contains(accountId)) {
            return false;
        }
        // 删除账号会重置自增计数器，ID 可被新账号复用；此时墓碑已过期，自动解除
        if (accountService != null && accountService.getById(accountId) != null) {
            permanentlyStoppedAccounts.remove(accountId);
            log.info("账号ID已被新账号复用，解除永久停止标记: accountId={}", accountId);
            return false;
        }
        return true;
    }

    @Override
    public boolean sendMessage(Long accountId, String cid, String toId, String text) {
        return withLifecycleLock(accountId, () -> sendMessageLocked(accountId, cid, toId, text));
    }

    private boolean sendMessageLocked(Long accountId, String cid, String toId, String text) {
        try {
            if (isPermanentlyStopped(accountId)) {
                log.warn("账号已永久停止，拒绝发送消息: accountId={}", accountId);
                return false;
            }
            log.info("发送消息: accountId={}, cid={}, toId={}, textLength={}",
                    accountId, cid, toId, text != null ? text.length() : 0);
            
            // 获取WebSocket客户端
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }
            
            // 发送消息（不等待服务端确认；本地发送失败如实上报，由调用方决定重试）
            return client.sendMessage(cid, toId, text);
            
        } catch (Exception e) {
            log.error("发送消息失败: accountId={}, cid={}, toId={}, errorType={}",
                    accountId, cid, toId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean sendMessageWithResult(Long accountId, String cid, String toId, String text) {
        return sendMessageWithOutcome(accountId, cid, toId, text) == MessageSendResult.SUCCESS;
    }

    @Override
    public MessageSendResult sendMessageWithOutcome(Long accountId, String cid, String toId, String text) {
        return withLifecycleLock(accountId,
                () -> sendMessageWithOutcomeLocked(accountId, cid, toId, text));
    }

    private MessageSendResult sendMessageWithOutcomeLocked(Long accountId, String cid, String toId, String text) {
        try {
            if (isPermanentlyStopped(accountId)) {
                log.warn("账号已永久停止，拒绝发送消息: accountId={}", accountId);
                return MessageSendResult.FAILED;
            }
            log.info("发送消息(等待结果): accountId={}, cid={}, toId={}, textLength={}",
                    accountId, cid, toId, text != null ? text.length() : 0);
            
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return MessageSendResult.FAILED;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return MessageSendResult.FAILED;
            }
            
            MessageSendResult result = client.sendMessageWithOutcome(cid, toId, text);
            return result != null ? result : MessageSendResult.FAILED;
            
        } catch (Exception e) {
            // 异常可能发生在帧已写出之后，服务端/买家是否收到无法确认。
            // 返回 FAILED 会让发货链路回滚卡密，导致同一张卡被二次售卖。
            log.error("发送消息异常，结果未知: accountId={}, cid={}, toId={}, errorType={}",
                    accountId, cid, toId, e.getClass().getSimpleName());
            return MessageSendResult.UNKNOWN;
        }
    }

    public void completePendingResponse(Long accountId, String mid, int code) {
        if (isPermanentlyStopped(accountId)) {
            return;
        }
        XianyuWebSocketClient client = webSocketClients.get(accountId);
        if (client != null) {
            client.completePendingResponse(mid, code);
        }
    }
    
    @Override
    public boolean sendImageMessage(Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        return withLifecycleLock(accountId,
                () -> sendImageMessageLocked(accountId, cid, toId, imageUrl, width, height));
    }

    private boolean sendImageMessageLocked(
            Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        try {
            if (isPermanentlyStopped(accountId)) {
                log.warn("账号已永久停止，拒绝发送图片消息: accountId={}", accountId);
                return false;
            }
            log.info("发送图片消息: accountId={}, cid={}, toId={}, size={}x{}",
                    accountId, cid, toId, width, height);
            
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }
            
            client.sendImageMessage(cid, toId, imageUrl, width, height);
            return true;
            
        } catch (Exception e) {
            log.error("发送图片消息失败: accountId={}, cid={}, toId={}, errorType={}",
                    accountId, cid, toId, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean sendImageMessageWithResult(Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        return sendImageMessageWithOutcome(accountId, cid, toId, imageUrl, width, height)
                == MessageSendResult.SUCCESS;
    }

    @Override
    public MessageSendResult sendImageMessageWithOutcome(
            Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        return withLifecycleLock(accountId,
                () -> sendImageMessageWithOutcomeLocked(accountId, cid, toId, imageUrl, width, height));
    }

    private MessageSendResult sendImageMessageWithOutcomeLocked(
            Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        try {
            if (isPermanentlyStopped(accountId)) {
                log.warn("账号已永久停止，拒绝发送图片消息: accountId={}", accountId);
                return MessageSendResult.FAILED;
            }
            log.info("发送图片消息(等待结果): accountId={}, cid={}, toId={}, size={}x{}",
                    accountId, cid, toId, width, height);

            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return MessageSendResult.FAILED;
            }

            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return MessageSendResult.FAILED;
            }

            MessageSendResult result = client.sendImageMessageWithOutcome(cid, toId, imageUrl, width, height);
            return result != null ? result : MessageSendResult.FAILED;

        } catch (Exception e) {
            log.error("发送图片消息失败: accountId={}, cid={}, toId={}, errorType={}",
                    accountId, cid, toId, e.getClass().getSimpleName());
            return MessageSendResult.FAILED;
        }
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        shuttingDown = true;
        log.info("应用关闭，清理WebSocket资源");
        stopAllWebSockets();
        cancelOutstandingTasks();
        heartbeatScheduler.shutdownNow();
        tokenRefreshScheduler.shutdownNow();
        reconnectExecutor.shutdownNow();
    }

    private void cancelOutstandingTasks() {
        heartbeatTasks.values().forEach(task -> task.cancel(false));
        tokenRefreshTasks.values().forEach(task -> task.cancel(false));
        tokenRefreshRetryTasks.values().forEach(task -> task.cancel(false));
        captchaProbeTasks.values().forEach(task -> task.cancel(false));
        reconnectTasks.values().forEach(task -> task.cancel(false));
        tokenExpiredRecoveryTasks.values().forEach(task -> task.cancel(false));
        heartbeatTasks.clear();
        tokenRefreshTasks.clear();
        tokenRefreshRetryTasks.clear();
        captchaProbeTasks.clear();
        captchaProbeTaskGenerations.clear();
        reconnectTasks.clear();
        reconnectTaskGenerations.clear();
        tokenExpiredRecoveryTasks.clear();
        manualCaptchaRecoveryRequests.clear();
        captchaProbeAttemptCounts.clear();
    }

    private void triggerWsDisconnectNotify(Long accountId) {
        try {
            if (emailNotifyService == null || !emailNotifyService.isWsDisconnectNotifyEnabled()) {
                return;
            }
            // 防抖：10分钟内只发送一次
            Long lastNotifyTime = lastDisconnectNotifyTimes.get(accountId);
            long now = System.currentTimeMillis();
            if (lastNotifyTime != null && (now - lastNotifyTime) < NOTIFY_INTERVAL_MS) {
                log.debug("【账号{}】邮件通知防抖中，跳过本次发送", accountId);
                return;
            }
            lastDisconnectNotifyTimes.put(accountId, now);

            String accountNote = "";
            try {
                com.feijimiao.xianyuassistant.entity.XianyuAccount account = xianyuAccountMapper.selectById(accountId);
                if (account != null) {
                    accountNote = account.getAccountNote() != null ? account.getAccountNote() : "";
                }
            } catch (Exception e) {
                log.debug("获取账号备注失败: errorType={}", e.getClass().getSimpleName());
            }
            emailNotifyService.sendWsDisconnectNotifyEmail(accountId, accountNote);
        } catch (Exception e) {
            log.warn("触发WebSocket断开连接邮件通知异常: errorType={}",
                    e.getClass().getSimpleName());
        }
    }
}
