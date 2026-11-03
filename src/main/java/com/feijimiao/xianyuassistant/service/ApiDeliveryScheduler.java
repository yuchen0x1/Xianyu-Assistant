package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 后台 API 兜底发货调度器：默认由系统设置控制启用状态，避免仅依赖环境变量。
 *
 * <p>它和前台的 {@code executeDelivery(...)} 是两层不同机制：</p>
 * <ul>
 *   <li>前台自动/手动发货：只要 WebSocket 断开，就在当前调用内自动走虚拟发货 API。</li>
 *   <li>本调度器：额外提供后台轮询兜底，用于 missed WebSocket 推送的场景。</li>
 * </ul>
 */
@Slf4j
@Service
public class ApiDeliveryScheduler {

    private static final String SETTING_KEY_ENABLED = "api_delivery_fallback_enabled";
    private static final String SETTING_KEY_ACCOUNT_IDS = "api_delivery_fallback_account_ids";

    @Value("${app.api-delivery-fallback.enabled:false}")
    private String defaultApiFallbackEnabled;

    @Value("${app.api-delivery-fallback.account-ids:}")
    private String defaultApiFallbackAccountIds;

    @Autowired
    private SysSettingService sysSettingService;

    @Autowired
    private WebSocketService webSocketService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PendingOrderPollService pendingOrderPollService;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;

    @Autowired
    private XianyuGoodsOrderMapper orderMapper;

    /** 复用全局异步池：兜底发货含 20-30s 防风控延迟，绝不能占用 @Scheduled 调度线程。 */
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /** 在跑的账号集合：防止上一轮兜底未完成时同账号重复提交。 */
    private final Set<Long> inFlightAccounts = ConcurrentHashMap.newKeySet();

    /** 单笔订单兜底失败的最大次数，超过后不再自动重试，转人工处理。 */
    private static final int MAX_FALLBACK_ATTEMPTS = 8;
    /** 失败退避基数与上限：25s 一轮的调度不能对坏单持续打 API。 */
    private static final long BASE_BACKOFF_MS = 60_000L;
    private static final long MAX_BACKOFF_MS = 30 * 60_000L;
    private static final int RETRY_STATE_MAX_ENTRIES = 2000;

    /**
     * 兜底发货失败退避状态（进程内）。
     *
     * <p>旧实现每 25 秒把同一笔坏单再打一次 API，占满账号限流后拖累同账号所有
     * 订单，并产生告警洪泛。这里按「账号|订单」记录失败次数与下次可重试时间，
     * 指数退避并在达到上限后停止。进程重启会重置，属可接受的降级。</p>
     */
    private final ConcurrentHashMap<String, RetryState> retryStates = new ConcurrentHashMap<>();

    private record RetryState(int attempts, long nextAttemptAt) {
    }

    private volatile boolean apiFallbackEnabled = false;
    private volatile String apiFallbackAccountIds = "";

    @PostConstruct
    public void initFallbackConfig() {
        String dbEnabled = sysSettingService.getSettingValue(SETTING_KEY_ENABLED);
        this.apiFallbackEnabled = parseBoolean(dbEnabled != null ? dbEnabled : defaultApiFallbackEnabled, false);

        String dbAccountIds = sysSettingService.getSettingValue(SETTING_KEY_ACCOUNT_IDS);
        this.apiFallbackAccountIds = dbAccountIds != null ? dbAccountIds : defaultApiFallbackAccountIds;
        if (this.apiFallbackAccountIds == null) {
            this.apiFallbackAccountIds = "";
        }
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    public boolean isApiFallbackEnabled() {
        return apiFallbackEnabled;
    }

    public void setApiFallbackEnabled(boolean apiFallbackEnabled) {
        this.apiFallbackEnabled = apiFallbackEnabled;
    }

    public String getApiFallbackAccountIds() {
        return apiFallbackAccountIds;
    }

    public void setApiFallbackAccountIds(String apiFallbackAccountIds) {
        this.apiFallbackAccountIds = apiFallbackAccountIds == null ? "" : apiFallbackAccountIds;
    }

    @Scheduled(fixedDelay = 25000, initialDelay = 60000)
    public void scheduledApiDelivery() {
        // 后台兜底未开启，或允许名单为空，直接跳过本轮。
        if (!apiFallbackEnabled || parseAllowedAccountIds().isEmpty()) {
            return;
        }

        List<XianyuAccount> accounts = accountMapper.selectList(null);
        if (accounts == null || accounts.isEmpty()) return;

        Set<Long> allowedAccountIds = parseAllowedAccountIds();
        for (XianyuAccount account : accounts) {
            if (account == null || account.getId() == null
                    || !allowedAccountIds.contains(account.getId())) {
                continue;
            }

            // 先查商品开关（本地查询），再查连接状态，顺序与既有契约一致
            Set<String> autoDeliveryGoodsIds = findEnabledGoodsIds(account.getId());
            if (autoDeliveryGoodsIds.isEmpty()) {
                continue;
            }

            if (webSocketService.isConnected(account.getId())) {
                // 实时连接已恢复时，由前台自动发货逻辑继续处理，避免后台重复调用 API。
                continue;
            }

            // 兜底发货含 20-30s 防风控 sleep，提交到异步池执行，避免阻塞共享调度线程池
            // （调度池仅 4 线程，还要跑备份、Token 刷新、连接看门狗等任务）。
            if (!inFlightAccounts.add(account.getId())) {
                continue;
            }
            try {
                Long accountId = account.getId();
                taskExecutor.execute(() -> {
                    try {
                        pollAndDeliver(accountId, autoDeliveryGoodsIds);
                    } catch (Exception e) {
                        log.warn("【账号{}】API发货兜底轮询异常: errorType={}",
                                accountId, e.getClass().getSimpleName());
                    } finally {
                        inFlightAccounts.remove(accountId);
                    }
                });
            } catch (Exception e) {
                inFlightAccounts.remove(account.getId());
                log.warn("【账号{}】提交API发货兜底任务失败: errorType={}",
                        account.getId(), e.getClass().getSimpleName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pollAndDeliver(Long accountId, Set<String> autoDeliveryGoodsIds) {
        List<Map<String, Object>> pendingOrders = orderService.queryPendingOrders(accountId);
        if (pendingOrders == null || pendingOrders.isEmpty()) return;

        List<Map<String, Object>> toDeliver = new ArrayList<>();
        for (Map<String, Object> order : pendingOrders) {
            try {
                Object commonDataObj = order.get("commonData");
                if (!(commonDataObj instanceof Map)) continue;
                Map<String, Object> commonData = (Map<String, Object>) commonDataObj;

                String orderId = (String) commonData.get("orderId");
                String orderStatus = (String) commonData.get("orderStatus");
                String itemId = (String) commonData.get("itemId");

                if (orderId == null || !"待发货".equals(orderStatus)) continue;
                if (itemId == null || !autoDeliveryGoodsIds.contains(itemId)) continue;

                XianyuGoodsOrder existing = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
                if (isCompletedOrAwaitingManualReview(existing)) continue;
                if (shouldSkipDueToBackoff(accountId, orderId)) continue;

                toDeliver.add(order);
            } catch (Exception e) {
                log.warn("【账号{}】筛选待发货订单异常: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
        }

        if (toDeliver.isEmpty()) return;

        // 仅同步明确启用自动发货的商品，避免详情补全对其他商品产生额外外联。
        pendingOrderPollService.syncOrdersToDb(accountId, toDeliver);

        log.info("【账号{}】API发货: 发现{}笔待发货订单(WS未连接)", accountId, toDeliver.size());

        int randomDelay = 20 + ThreadLocalRandom.current().nextInt(11);
        log.info("【账号{}】随机延迟{}秒后开始发货", accountId, randomDelay);
        try { Thread.sleep(randomDelay * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

        for (int i = 0; i < toDeliver.size(); i++) {
            Map<String, Object> order = toDeliver.get(i);
            try {
                Map<String, Object> commonData = (Map<String, Object>) order.get("commonData");
                String orderId = (String) commonData.get("orderId");
                String itemId = (String) commonData.get("itemId");

                if (webSocketService.isConnected(accountId)) {
                    log.info("【账号{}】实时连接已恢复，停止API兜底发货", accountId);
                    return;
                }
                if (!findEnabledGoodsIds(accountId).contains(itemId)) {
                    log.info("【账号{}】商品自动发货已关闭，跳过API兜底: itemId={}", accountId, itemId);
                    continue;
                }
                XianyuGoodsOrder current = orderMapper.selectByAccountIdAndOrderId(accountId, orderId);
                if (isCompletedOrAwaitingManualReview(current)) {
                    continue;
                }

                log.info("【账号{}】API发货[{}/{}]: orderId={}, itemId={}", accountId, i + 1, toDeliver.size(), orderId, itemId);
                ApiDeliveryResult result = orderService.consignDummyDeliveryWithConfig(accountId, itemId, orderId);

                if (result == ApiDeliveryResult.SKIPPED) {
                    // 实时链路或人工操作正在发这一单，兜底不应重复发货，也不需要等待
                    log.info("【账号{}】订单已有其他发货流程在处理，跳过本轮兜底: orderId={}", accountId, orderId);
                    continue;
                } else if (result == ApiDeliveryResult.FAILED) {
                    log.error("【账号{}】API发货失败: orderId={}", accountId, orderId);
                    recordFallbackFailure(accountId, orderId);
                } else if (result == ApiDeliveryResult.UNKNOWN) {
                    log.warn("【账号{}】API发货结果未知，停止自动处理并等待人工核对: orderId={}", accountId, orderId);
                    clearFallbackFailure(accountId, orderId);
                    return;
                } else {
                    clearFallbackFailure(accountId, orderId);
                }

                if (i < toDeliver.size() - 1) {
                    int delay = 5 + ThreadLocalRandom.current().nextInt(6);
                    log.info("【账号{}】发货间隔{}秒", accountId, delay);
                    try { Thread.sleep(delay * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            } catch (Exception e) {
                log.warn("【账号{}】API发货异常: errorType={}", accountId, e.getClass().getSimpleName());
                Object commonDataObj = order.get("commonData");
                if (commonDataObj instanceof Map<?, ?> commonData && commonData.get("orderId") instanceof String oid) {
                    recordFallbackFailure(accountId, oid);
                }
            }
        }
    }

    private String retryKey(Long accountId, String orderId) {
        return accountId + "|" + orderId;
    }

    private boolean shouldSkipDueToBackoff(Long accountId, String orderId) {
        RetryState state = retryStates.get(retryKey(accountId, orderId));
        if (state == null) {
            return false;
        }
        if (state.attempts() >= MAX_FALLBACK_ATTEMPTS) {
            return true;
        }
        return System.currentTimeMillis() < state.nextAttemptAt();
    }

    private void recordFallbackFailure(Long accountId, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        if (retryStates.size() > RETRY_STATE_MAX_ENTRIES) {
            // 极端场景（大量坏单）下防止无界增长；重置只会让退避重新开始
            retryStates.clear();
        }
        String key = retryKey(accountId, orderId);
        RetryState updated = retryStates.compute(key, (k, previous) -> {
            int attempts = previous == null ? 1 : previous.attempts() + 1;
            long backoff = Math.min(BASE_BACKOFF_MS << Math.min(attempts - 1, 10), MAX_BACKOFF_MS);
            return new RetryState(attempts, System.currentTimeMillis() + backoff);
        });
        if (updated != null && updated.attempts() == MAX_FALLBACK_ATTEMPTS) {
            log.error("【账号{}】API兜底发货连续失败{}次，停止自动重试，请人工处理: orderId={}",
                    accountId, MAX_FALLBACK_ATTEMPTS, orderId);
        }
    }

    private void clearFallbackFailure(Long accountId, String orderId) {
        if (orderId != null) {
            retryStates.remove(retryKey(accountId, orderId));
        }
    }

    private boolean isCompletedOrAwaitingManualReview(XianyuGoodsOrder order) {
        if (order == null) {
            return false;
        }
        if (Integer.valueOf(DeliveryOrderState.DELIVERED).equals(order.getState())) {
            return true;
        }
        return DeliveryOrderState.isAwaitingManualReview(order.getState(), order.getFailReason())
                || (Integer.valueOf(DeliveryOrderState.FAILED).equals(order.getState())
                && order.getFailReason() != null
                && order.getFailReason().startsWith("发货风控拦截:"));
    }

    private Set<String> findEnabledGoodsIds(Long accountId) {
        List<XianyuGoodsConfig> goodsConfigs = goodsConfigMapper.selectByAccountId(accountId);
        if (goodsConfigs == null || goodsConfigs.isEmpty()) {
            return Set.of();
        }

        Set<String> enabledGoodsIds = new HashSet<>();
        for (XianyuGoodsConfig config : goodsConfigs) {
            if (config != null
                    && Integer.valueOf(1).equals(config.getXianyuAutoDeliveryOn())
                    && config.getXyGoodsId() != null
                    && !config.getXyGoodsId().isBlank()) {
                enabledGoodsIds.add(config.getXyGoodsId());
            }
        }
        return enabledGoodsIds;
    }

    private Set<Long> parseAllowedAccountIds() {
        if (apiFallbackAccountIds == null || apiFallbackAccountIds.isBlank()) {
            return Set.of();
        }

        Set<Long> accountIds = new HashSet<>();
        for (String value : apiFallbackAccountIds.split(",")) {
            try {
                accountIds.add(Long.valueOf(value.trim()));
            } catch (NumberFormatException e) {
                log.warn("忽略无效的API兜底账号ID配置");
            }
        }
        return accountIds;
    }
}
