package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.config.PlaywrightManager;
import com.feijimiao.xianyuassistant.config.WebSocketConfig;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.exception.CookieExpiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.service.TokenRefreshService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.utils.SessionCookieJar;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token刷新服务实现
 *
 * <p>功能：</p>
 * <ul>
 *   <li>定期刷新_m_h5_tk token（15-20分钟随机间隔，作为兜底机制）</li>
 *   <li>定期Cookie保活检查（15-20分钟随机间隔）</li>
 *   <li>提供手动刷新websocket_token能力；自动到期刷新由WebSocket连接生命周期统一负责</li>
 *   <li>监控token过期时间</li>
 *   <li>自动重新获取过期的token</li>
 * </ul>
 *
 * <p>优化策略（参考Python实现）：</p>
 * <ul>
 *   <li>Python采用"按需刷新"策略：只在token获取失败时才调用hasLogin</li>
 *   <li>Java保留按需刷新，并增加15-20分钟的随机兜底刷新，避免固定节奏</li>
 *   <li>主要依赖token刷新失败时的自动重试机制来触发hasLogin</li>
 *   <li>WebSocket token只保留一个自动调度所有者，避免并发清空和重复请求</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenRefreshServiceImpl implements TokenRefreshService {

    private static final long ONE_MINUTE_MS = 60 * 1000L;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuCookieMapper cookieMapper;

    @Autowired
    private WebSocketTokenService webSocketTokenService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private CookieRefreshService cookieRefreshService;

    @Autowired
    private WebSocketConfig webSocketConfig;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired(required = false)
    private com.feijimiao.xianyuassistant.service.EmailNotifyService emailNotifyService;

    private volatile long nextCookieKeepAliveTime = 0;

    // 保活巡检线程池（按配置并发执行，单账号慢请求不阻塞其他账号）
    private ExecutorService keepAliveExecutor;

    // 浏览器兜底刷新串行化：Playwright 单实例，多账号并发刷新会互相争抢
    private final Semaphore browserRefreshSemaphore = new Semaphore(1);

    // 防重入：上一轮保活仍在执行时跳过本轮
    private final AtomicBoolean keepAliveRunning = new AtomicBoolean(false);

    private final AtomicInteger keepAliveThreadSequence = new AtomicInteger();

    // 保活编排线程：@Scheduled 共享调度池只有 4 个线程承载 8 个任务，
    // 保活单轮最长等待 10 分钟，绝不能占住调度线程。
    private ExecutorService keepAliveDispatcher;

    @PostConstruct
    public void initRefreshSchedules() {
        int poolSize = Math.max(1, webSocketConfig.getKeepAlivePoolSize());
        keepAliveExecutor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable,
                    "cookie-keep-alive-" + keepAliveThreadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        keepAliveDispatcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cookie-keep-alive-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        log.info("Cookie保活线程池已初始化: poolSize={}", poolSize);
        scheduleNextCookieKeepAlive();
    }

    @PreDestroy
    public void destroyKeepAliveExecutor() {
        if (keepAliveDispatcher != null) {
            keepAliveDispatcher.shutdownNow();
        }
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            log.info("Cookie保活线程池已关闭");
        }
    }

    private long randomRefreshDelayMinutes() {
        int minMinutes = Math.max(1, webSocketConfig.getCredentialRefreshMinMinutes());
        int maxMinutes = Math.max(minMinutes, webSocketConfig.getCredentialRefreshMaxMinutes());
        return ThreadLocalRandom.current().nextLong(minMinutes, maxMinutes + 1L);
    }

    private void scheduleNextCookieKeepAlive() {
        long delayMinutes = randomRefreshDelayMinutes();
        nextCookieKeepAliveTime = System.currentTimeMillis() + delayMinutes * ONE_MINUTE_MS;
        log.info("📅 下次Cookie保活检查将在 {} 分钟后执行", delayMinutes);
    }
    
    /**
     * 闲鱼API地址（用于刷新_m_h5_tk）
     */
    private static final String API_H5_TK = XianyuProtocolProfile.H5_BASE_URL
            + XianyuProtocolProfile.H5_TOKEN_REFRESH_API + "/" + XianyuProtocolProfile.H5_API_VERSION + "/";
    
    /**
     * 刷新_m_h5_tk token
     * 通过调用闲鱼API，服务器会返回新的_m_h5_tk
     * 
     * 参考Python逻辑：
     * 1. 调用H5 API获取新的_m_h5_tk
     * 2. 如果失败，重试最多2次
     * 3. 重试失败后，调用hasLogin刷新Cookie
     * 4. hasLogin成功后，重新尝试获取_m_h5_tk
     */
    @Override
    public boolean refreshMh5tkToken(Long accountId) {
        return refreshMh5tkTokenWithRetry(accountId, 0, false);
    }
    
    /**
     * 刷新_m_h5_tk token（带重试机制）
     * 参考Python XianyuApis.get_token的重试逻辑
     * 
     * @param accountId 账号ID
     * @param retryCount 当前重试次数
     * @param hasLoginAttempted 是否已经尝试过hasLogin刷新
     * @return 是否成功
     */
    private boolean refreshMh5tkTokenWithRetry(Long accountId, int retryCount, boolean hasLoginAttempted) {
        try {
            log.info("【账号{}】开始刷新_m_h5_tk token... (重试次数: {})", accountId, retryCount);

            // 同一账号可能存在多条Cookie记录（历史遗留），必须取最新一条，否则 selectOne 命中多条会抛异常
            XianyuCookie cookie = cookieMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );
            if (cookie == null || cookie.getCookieText() == null) {
                log.warn("【账号{}】未找到Cookie，无法刷新token", accountId);
                return false;
            }

            String oldCookieStr = cookie.getCookieText();
            SessionCookieJar cookieJar = new SessionCookieJar(oldCookieStr);

            Request request = new Request.Builder()
                    .url(API_H5_TK)
                    .header("User-Agent", XianyuProtocolProfile.WEB_USER_AGENT)
                    .header("Referer", "https://market.m.goofish.com/")
                    .get()
                    .build();

            OkHttpClient okHttpClient = cookieJar.createHttpClient();

            try (Response response = okHttpClient.newCall(request).execute()) {
                String newCookieStr = cookieJar.getCookieString();
                Map<String, String> newCookies = XianyuSignUtils.parseCookies(newCookieStr);
                String newMh5tk = newCookies.get("_m_h5_tk");

                if (newMh5tk != null && !newMh5tk.isEmpty()) {
                    Map<String, String> oldCookies = XianyuSignUtils.parseCookies(oldCookieStr);
                    String oldMh5tk = oldCookies.get("_m_h5_tk");
                    boolean mh5tkChanged = !newMh5tk.equals(oldMh5tk);

                    if (mh5tkChanged) {
                        // 关键字段保护：刷新返回的 Cookie 若丢失 unb/_m_h5_tk，
                        // 说明响应不完整，禁止覆盖数据库中完整的登录凭据。
                        if (!XianyuSignUtils.hasCriticalCookieFields(newCookieStr)) {
                            log.warn("【账号{}】_m_h5_tk 刷新响应丢失关键 Cookie 字段(unb/_m_h5_tk)，放弃覆盖数据库", accountId);
                            return true;
                        }
                        cookie.setCookieText(newCookieStr);
                        // 仅当 _m_h5_tk 为合法 token 格式时才更新，防止密文/损坏内容嵌套写入
                        if (XianyuSignUtils.isValidMh5tk(newMh5tk)) {
                            cookie.setMH5Tk(newMh5tk);
                        } else {
                            // 本次不更新 _m_h5_tk：置空跳过该字段，防止把读取到的嵌套密文再次写回膨胀
                            cookie.setMH5Tk(null);
                        }
                        cookieMapper.updateById(cookie);

                        log.info("【账号{}】_m_h5_tk token刷新成功，长度: {}",
                                accountId, newMh5tk.length());

                        operationLogService.log(accountId,
                            com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                            com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                            "_m_h5_tk Token刷新成功（通过SessionCookieJar自动吸收Set-Cookie）",
                            com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                            com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                            String.valueOf(accountId),
                            null, null, null, null);

                        return true;
                    } else {
                        log.info("【账号{}】_m_h5_tk未变化，Token仍然有效", accountId);
                        return true;
                    }
                }

                log.warn("【账号{}】⚠️ 响应中未包含新的_m_h5_tk", accountId);
                return handleMh5tkRefreshFailure(accountId, retryCount, hasLoginAttempted, "响应中未包含新Token");
            }

        } catch (CaptchaRequiredException | CookieExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("【账号{}】刷新_m_h5_tk token失败", accountId, e);
            return handleMh5tkRefreshFailure(accountId, retryCount, hasLoginAttempted, "异常: " + e.getMessage());
        }
    }
    
    /**
     * 处理_m_h5_tk刷新失败的情况
     * 参考Python XianyuApis.get_token的失败处理逻辑
     * 
     * @param accountId 账号ID
     * @param retryCount 当前重试次数
     * @param hasLoginAttempted 是否已经尝试过hasLogin刷新
     * @param reason 失败原因
     * @return 是否成功
     */
    private boolean handleMh5tkRefreshFailure(Long accountId, int retryCount, boolean hasLoginAttempted, String reason) {
        // 参考Python: retry_count < 2 时直接重试
        if (retryCount < 2) {
            log.warn("【账号{}】_m_h5_tk刷新失败({})，准备重试... (重试次数: {}/2)",
                    accountId, reason, retryCount + 1);

            try {
                // 随机间隔500-1500ms，避免固定间隔被识别为机器人
                long randomInterval = 500 + new java.util.Random().nextLong(1001);
                Thread.sleep(randomInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return refreshMh5tkTokenWithRetry(accountId, retryCount + 1, hasLoginAttempted);
        }
        
        // hasLogin 已成功时，只能判定“本次 Token 刷新未下发新值”，不能判定整个登录态过期。
        // 部分有效会话的刷新接口不会重复返回 _m_h5_tk；错误地写入 cookie_status=2 会阻断
        // WebSocket 重连、商品同步和订单接口，形成登录有效但业务永久离线的状态机死锁。
        if (hasLoginAttempted) {
            log.warn("【账号{}】hasLogin确认登录有效，但本次响应未下发新的_m_h5_tk；保留现有Cookie状态", accountId);

            operationLogService.log(accountId,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                "_m_h5_tk Token本次未刷新：登录态仍有效，保留现有Cookie",
                com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                String.valueOf(accountId),
                null, null, "响应未下发新Token，未判定Cookie过期", null);

            return false;
        }
        
        // 参考Python: retry_count >= 2 时，调用hasLogin刷新Cookie后重试
        log.warn("【账号{}】_m_h5_tk刷新重试已达上限，尝试通过hasLogin刷新Cookie...", accountId);
        return refreshMh5tkViaHasLogin(accountId, 0);
    }
    
    /**
     * 通过hasLogin刷新Cookie后重新获取_m_h5_tk
     * 参考Python: get_token中retry_count >= 2时的逻辑
     * 
     * @param accountId 账号ID
     * @param hasLoginRetryCount hasLogin重试次数
     * @return 是否成功
     */
    private boolean refreshMh5tkViaHasLogin(Long accountId, int hasLoginRetryCount) {
        if (hasLoginRetryCount >= 2) {
            // 与上方hasLoginAttempted保护一致：登录态仍有效时不能判定Cookie过期，
            // 避免把平台瞬时故障误写为cookie_status=2，形成登录有效但业务永久离线的死锁。
            if (cookieRefreshService.checkLoginStatusQuietly(accountId)) {
                log.warn("【账号{}】_m_h5_tk刷新重试已达上限，但hasLogin确认登录态有效，保留现有Cookie状态", accountId);

                operationLogService.log(accountId,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                    "_m_h5_tk Token本次未刷新：登录态仍有效，保留现有Cookie",
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(accountId),
                    null, null, "响应未下发新Token，未判定Cookie过期", null);

                return false;
            }
            log.error("【账号{}】hasLogin刷新重试次数已达上限，Cookie已彻底过期", accountId);
            
            // 更新Cookie状态为过期
            cookieMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .set(XianyuCookie::getCookieStatus, 2)
            );
            
            // 记录操作日志
            operationLogService.log(accountId,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                "_m_h5_tk Token刷新失败：Cookie过期且自动刷新失败",
                com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                String.valueOf(accountId),
                null, null, "Cookie过期且自动刷新失败", null);
            
            return false;
        }
        
        log.info("【账号{}】开始通过hasLogin刷新Cookie... (重试次数: {}/2)", 
                accountId, hasLoginRetryCount);
        
        try {
            // 调用CookieRefreshService的checkLoginStatus方法（即hasLogin）
            boolean refreshSuccess = cookieRefreshService.checkLoginStatus(accountId);
            
            if (refreshSuccess) {
                log.info("【账号{}】hasLogin成功，登录态有效，准备重新获取_m_h5_tk（重置重试计数）", accountId);

                try {
                    // 随机间隔500-1500ms，避免固定间隔被识别为机器人
                    long randomInterval = 500 + new java.util.Random().nextLong(1001);
                    Thread.sleep(randomInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 重置retryCount为0，重新开始获取_m_h5_tk流程
                // 标记hasLoginAttempted=true，防止无限循环
                return refreshMh5tkTokenWithRetry(accountId, 0, true);
            } else {
                log.warn("【账号{}】hasLogin失败", accountId);
            }
        } catch (com.feijimiao.xianyuassistant.exception.CaptchaRequiredException e) {
            log.warn("【账号{}】hasLogin后继续刷新Token时触发滑块验证，停止自动重试，等待人工处理", accountId);
            throw e;
        } catch (com.feijimiao.xianyuassistant.exception.CookieExpiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("【账号{}】hasLogin刷新过程发生异常", accountId, e);
        }
        
        // hasLogin失败，重试
        return refreshMh5tkViaHasLogin(accountId, hasLoginRetryCount + 1);
    }
    
    /**
     * 刷新WebSocket token
     */
    @Override
    public boolean refreshWebSocketToken(Long accountId) {
        try {
            log.info("【账号{}】开始刷新WebSocket token...", accountId);
            
            // 调用WebSocketTokenService重新获取token
            String newToken = webSocketTokenService.refreshToken(accountId);
            
            if (newToken != null && !newToken.isEmpty()) {
                log.info("【账号{}】✅ WebSocket token刷新成功", accountId);
                return true;
            } else {
                log.warn("【账号{}】⚠️ WebSocket token刷新失败", accountId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("【账号{}】刷新WebSocket token失败", accountId, e);
            return false;
        }
    }
    
    /**
     * 检查token是否需要刷新
     */
    @Override
    public boolean needsRefresh(Long accountId) {
        try {
            // 同一账号可能存在多条Cookie记录（历史遗留），必须取最新一条，否则 selectOne 命中多条会抛异常
            XianyuCookie cookie = cookieMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );
            if (cookie == null) {
                return false;
            }
            
            // 检查WebSocket token是否即将过期（提前1小时刷新）
            if (cookie.getTokenExpireTime() != null) {
                long currentTime = System.currentTimeMillis();
                long expireTime = cookie.getTokenExpireTime();
                long oneHour = 60 * 60 * 1000;
                
                if (expireTime - currentTime < oneHour) {
                    log.info("【账号{}】WebSocket token即将过期，需要刷新", accountId);
                    return true;
                }
            }
            
            // _m_h5_tk没有明确的过期时间，建议每2小时刷新一次
            // 这里可以通过记录上次刷新时间来判断
            
            return false;
            
        } catch (Exception e) {
            log.error("【账号{}】检查token状态失败", accountId, e);
            return false;
        }
    }
    
    /**
     * 定时任务：Cookie保活 + _m_h5_tk Token刷新（合并任务）
     *
     * 问题背景：
     * - 原设计有两个独立定时任务：scheduledRefreshMh5tk 和 scheduledCookieKeepAlive
     * - 两个任务都是15-20分钟随机间隔，存在竞争和时序冲突
     * - hasLogin成功后，响应Set-Cookie可能不包含_m_h5_tk
     * - 如果单独刷新_m_h5_tk时Cookie已过期，H5 API不会返回新Token
     *
     * 优化策略：
     * 1. 合并两个任务为单一任务，确保执行顺序
     * 2. 先执行hasLogin保活，确保Cookie有效
     * 3. hasLogin成功后，立即使用最新Cookie刷新_m_h5_tk
     * 4. 如果hasLogin失败，触发浏览器兜底刷新
     * 5. 使用15-20分钟随机间隔，避免固定周期请求
     * 6. 添加随机间隔（5-15秒），避免多账号同时请求被识别为机器人
     */
    @Scheduled(fixedDelay = ONE_MINUTE_MS, initialDelay = ONE_MINUTE_MS)
    public void scheduledCookieKeepAlive() {
        if (System.currentTimeMillis() < nextCookieKeepAliveTime) {
            return;
        }
        scheduleNextCookieKeepAlive();
        keepAliveDispatcher.execute(this::runKeepAliveRound);
    }

    private void runKeepAliveRound() {
        if (!keepAliveRunning.compareAndSet(false, true)) {
            log.debug("上一轮Cookie保活仍在执行，跳过本轮");
            return;
        }
        try {
            log.info("🔄 开始定期Cookie保活 + Token刷新检查...");

            List<XianyuAccount> accounts = accountMapper.selectList(null);
            AtomicInteger keepAliveSuccessCount = new AtomicInteger();
            AtomicInteger tokenRefreshSuccessCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (XianyuAccount account : accounts) {
                if (account.getStatus() == 1 && account.getId() != null) {
                    futures.add(CompletableFuture.runAsync(
                            () -> keepAliveSingleAccount(account,
                                    keepAliveSuccessCount, tokenRefreshSuccessCount, failCount),
                            keepAliveExecutor));
                }
            }

            if (!futures.isEmpty()) {
                // 并行执行所有账号保活；总等待上限10分钟，避免定时任务长期占用
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.MINUTES);
            }

            log.info("✅ Cookie保活 + Token刷新完成: 保活成功{}个, Token刷新成功{}个, 失败{}个",
                    keepAliveSuccessCount.get(), tokenRefreshSuccessCount.get(), failCount.get());

        } catch (Exception e) {
            log.error("定期Cookie保活检查失败", e);
        } finally {
            keepAliveRunning.set(false);
        }
    }

    /**
     * 单账号保活：hasLogin -> _m_h5_tk 刷新；失败则浏览器兜底。
     *
     * <p>并行化说明：原实现逐账号串行执行，一个账号触发浏览器兜底刷新
     * （Playwright 打开页面可达30-60秒）或 hasLogin 超时（最长60秒）时，
     * 后续账号全部延迟保活，多账号场景成为掉线风险放大器。
     * 改为按账号并行执行，互不阻塞；浏览器兜底刷新通过信号量串行化，
     * 避免多账号同时操作 Playwright 单实例。</p>
     */
    private void keepAliveSingleAccount(XianyuAccount account,
                                        AtomicInteger keepAliveSuccessCount,
                                        AtomicInteger tokenRefreshSuccessCount,
                                        AtomicInteger failCount) {
        Long accountId = account.getId();
        try {
            // 第1步：通过hasLogin保持Cookie活跃
            boolean loginOk = cookieRefreshService.checkLoginStatus(accountId);

            if (loginOk) {
                keepAliveSuccessCount.incrementAndGet();
                log.debug("【账号{}】hasLogin保活成功", accountId);
                operationLogService.log(accountId,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Type.UPDATE,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Module.COOKIE,
                        "hasLogin保活成功",
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, null, null);

                // 第2步：hasLogin成功后，立即刷新_m_h5_tk（使用最新Cookie）
                // 关键：必须等hasLogin更新Cookie后，才能获取新Token
                boolean tokenOk = refreshMh5tkToken(accountId);
                if (tokenOk) {
                    tokenRefreshSuccessCount.incrementAndGet();
                    log.info("【账号{}】✅ Cookie保活 + Token刷新成功", accountId);
                } else {
                    log.warn("【账号{}】⚠️ Cookie保活成功但Token刷新失败", accountId);
                }
            } else {
                // 第3步兜底：hasLogin失败，触发浏览器刷新Cookie（信号量串行化）
                log.warn("【账号{}】hasLogin保活失败，开始触发浏览器兜底刷新Cookie...", accountId);
                boolean browserRefreshOk = false;
                try {
                    browserRefreshSemaphore.acquire();
                    browserRefreshOk = cookieRefreshService.refreshCookie(accountId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("【账号{}】浏览器兜底刷新等待被中断", accountId);
                } finally {
                    browserRefreshSemaphore.release();
                }
                if (browserRefreshOk) {
                    keepAliveSuccessCount.incrementAndGet();
                    // 浏览器刷新成功后，也尝试刷新Token
                    boolean tokenOk = refreshMh5tkToken(accountId);
                    if (tokenOk) {
                        tokenRefreshSuccessCount.incrementAndGet();
                    }
                    log.info("【账号{}】浏览器兜底刷新Cookie成功", accountId);
                } else {
                    failCount.incrementAndGet();
                    log.error("【账号{}】hasLogin和浏览器兜底刷新均失败，Cookie已过期，需手动更新", accountId);
                    triggerCookieExpireNotify(accountId);
                }
            }
        } catch (Exception e) {
            failCount.incrementAndGet();
            log.warn("【账号{}】Cookie保活异常: {}", accountId, e.getMessage());
        }

        // 随机间隔5-15秒，避免频繁请求和被识别为机器人。
        // 并行任务内各自等待，不再阻塞其他账号的保活。
        try {
            int randomInterval = 5000 + new java.util.Random().nextInt(10001);
            Thread.sleep(randomInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 兼容保留的按需批量检查入口。此方法不得注册为定时任务；
     * WebSocketServiceImpl 是 websocket_token 自动到期刷新的唯一调度所有者。
     */
    public void scheduledRefreshWebSocketToken() {
        try {
            // 与Python完全一致：每分钟检查一次，判断是否需要刷新（1小时）
            log.debug("🔄 检查WebSocket token是否需要刷新...");

            List<XianyuAccount> accounts = accountMapper.selectList(null);

            for (XianyuAccount account : accounts) {
                if (account.getStatus() == 1) { // 只刷新正常状态的账号
                    // 检查是否需要刷新（提前1小时刷新，与Python一致）
                    if (needsRefresh(account.getId())) {
                        log.info("🔄 账号{}的WebSocket token即将过期，开始刷新...", account.getId());
                        boolean success = refreshWebSocketToken(account.getId());

                        if (success) {
                            log.info("✅ 账号{}的WebSocket token刷新成功", account.getId());
                        } else {
                            log.warn("⚠️ 账号{}的WebSocket token刷新失败，将在下次检查时重试", account.getId());
                        }

                        // 随机间隔3-8秒，避免频繁请求
                        int randomInterval = 3000 + new java.util.Random().nextInt(5001);
                        Thread.sleep(randomInterval);
                    }
                }
            }

        } catch (Exception e) {
            log.error("定时检查WebSocket token失败", e);
        }
    }
    
    /**
     * 刷新所有账号的token
     *
     * 优化策略：
     * 1. 添加随机间隔（5-10秒），避免多账号同时请求被识别为机器人
     */
    @Override
    public void refreshAllAccountsTokens() {
        try {
            List<XianyuAccount> accounts = accountMapper.selectList(null);

            int successCount = 0;
            int failCount = 0;

            for (XianyuAccount account : accounts) {
                if (account.getStatus() == 1) { // 只刷新正常状态的账号
                    boolean success = refreshMh5tkToken(account.getId());
                    if (success) {
                        successCount++;
                    } else {
                        failCount++;
                    }

                    // 随机间隔5-10秒，避免频繁请求被检测
                    int randomInterval = 5000 + new java.util.Random().nextInt(5001);
                    Thread.sleep(randomInterval);
                }
            }

            log.info("✅ _m_h5_tk token刷新完成: 成功{}个, 失败{}个", successCount, failCount);

        } catch (Exception e) {
            log.error("刷新所有账号token失败", e);
        }
    }

    private final Map<Long, Long> lastCookieExpireNotifyTimes = new ConcurrentHashMap<>();
    private static final long COOKIE_NOTIFY_INTERVAL_MS = 10 * 60 * 1000L;

    private void triggerCookieExpireNotify(Long accountId) {
        try {
            if (emailNotifyService == null || !emailNotifyService.isCookieExpireNotifyEnabled()) {
                return;
            }
            long now = System.currentTimeMillis();
            Long lastTime = lastCookieExpireNotifyTimes.get(accountId);
            if (lastTime != null && (now - lastTime) < COOKIE_NOTIFY_INTERVAL_MS) {
                log.debug("【账号{}】Cookie过期邮件通知防抖中，跳过", accountId);
                return;
            }
            lastCookieExpireNotifyTimes.put(accountId, now);
            String accountNote = "";
            try {
                XianyuAccount account = accountMapper.selectById(accountId);
                if (account != null) {
                    accountNote = account.getAccountNote() != null ? account.getAccountNote() : "";
                }
            } catch (Exception e) {
                log.debug("获取账号备注失败: {}", e.getMessage());
            }
            emailNotifyService.sendCookieExpireNotifyEmail(accountId, accountNote);
        } catch (Exception e) {
            log.warn("触发Cookie过期邮件通知异常: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60 * ONE_MINUTE_MS, initialDelay = 5 * ONE_MINUTE_MS)
    public void scheduledCleanPlaywrightTempFiles() {
        try {
            playwrightManager.cleanTempFiles();
        } catch (Exception e) {
            log.warn("清理Playwright临时文件异常: {}", e.getMessage());
        }
    }

    @Override
    public void removeAccountState(Long accountId) {
        lastCookieExpireNotifyTimes.remove(accountId);
    }
}