package com.feijimiao.xianyuassistant.webhook.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.webhook.dto.WebhookDeliveryVO;
import com.feijimiao.xianyuassistant.webhook.dto.WebhookVO;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookConfig;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookDelivery;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookConfigMapper;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookDeliveryMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Webhook 事件分发服务：支持事件驱动推送，可配置多个 Webhook 端点。
 * <p>
 * 支持事件类型：
 * - delivery.completed  发货完成
 * - delivery.failed     发货失败
 * - order.created       订单创建
 * - connection.lost     连接断开
 * - connection.restored 连接恢复
 * - alert.triggered     告警触发
 * - kami.low_stock      卡密库存不足
 * - test                测试事件
 * </p>
 * <p>投递历史持久化到 webhook_delivery（V3_17）：dispatch 先写 PENDING，
 * 每次尝试后更新状态/attempts/http_status/last_error，失败按 retryCount 指数退避。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final int MAX_RETRY_COUNT = 10;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final String SECRET_MASK = "********";
    /** 退避基数（秒）：第 n 次失败后 next_attempt_at = now + min(2^n * BACKOFF_BASE, 300) */
    private static final int BACKOFF_BASE_SECONDS = 2;
    private static final int BACKOFF_MAX_SECONDS = 300;
    private static final int LAST_ERROR_MAX = 500;
    private static final int IDEMPOTENCY_KEY_MAX = 100;
    /** 单次失败重投扫描最多取出的投递记录数 */
    private static final int REDELIVERY_BATCH_SIZE = 20;
    /** 落库后超过该时长仍是 PENDING，视为"发送未开始"，由重投扫描接管 */
    private static final int STALE_PENDING_MINUTES = 2;

    private final WebhookConfigMapper configMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final ObjectMapper objectMapper;

    /** 正在投递的记录，防止 60 秒一轮的扫描与上一轮未完成的投递并发重复发送。 */
    private final Set<Long> inFlightDeliveries = ConcurrentHashMap.newKeySet();

    /** 所有 Webhook 请求共用连接池；发送时通过 clientFor 派生绑定已校验 IP 的派生客户端。 */
    private final OkHttpClient baseClient = new OkHttpClient.Builder()
            // 禁止跟随重定向，避免把请求转发到 SSRF 白名单之外的私网目标。
            .followRedirects(false)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ExecutorService executor;

    @Value("${app.webhook.executor.pool-size:4}")
    private int poolSize;

    @Value("${app.webhook.executor.queue-capacity:200}")
    private int queueCapacity;

    @Value("${app.webhook.executor.shutdown-await-seconds:10}")
    private int shutdownAwaitSeconds;

    @PostConstruct
    public void init() {
        // 有界队列：端点全部变慢时队列打满即拒绝，任务转 FAILED 交给重投兜底，
        // 避免无界队列在长时间阻塞 IO 下无限堆积拖垮内存。
        executor = new ThreadPoolExecutor(
                Math.max(1, poolSize), Math.max(1, poolSize), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "webhook-worker");
                    t.setDaemon(true);
                    return t;
                });
        log.info("Webhook 服务已初始化: poolSize={}, queueCapacity={}", poolSize, queueCapacity);
    }

    @PreDestroy
    public void shutdown() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownAwaitSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 提交异步投递；线程池/队列打满被拒绝时，把已落库的 PENDING 记录转为 FAILED 并安排退避，
     * 交给 {@link #redeliverFailedDeliveries()} 兜底，不让投递记录悬挂在 PENDING。
     */
    private void submitDelivery(WebhookConfig hook, String event, Long deliveryId, Runnable task) {
        try {
            CompletableFuture.runAsync(task, executor);
        } catch (RejectedExecutionException e) {
            log.warn("Webhook 投递队列已满，转后台重试: hookId={}, event={}, deliveryId={}",
                    hook.getId(), event, deliveryId);
            updateDelivery(deliveryId, "FAILED", null, 1, "投递队列已满，等待重试", null);
        }
    }

    // ==================== CRUD ====================

    public List<WebhookVO> listAll() {
        List<WebhookConfig> configs = configMapper.selectList(null);
        List<WebhookVO> result = new ArrayList<>(configs.size());
        for (WebhookConfig config : configs) {
            result.add(toVO(config));
        }
        return result;
    }

    public WebhookVO getById(Long id) {
        WebhookConfig config = configMapper.selectById(id);
        return config == null ? null : toVO(config);
    }

    @Transactional
    public WebhookVO create(WebhookConfig config) {
        validateWebhookUrl(config.getUrl());
        config.setEnabled(config.getEnabled() == null ? 1 : config.getEnabled());
        config.setRetryCount(clampRetry(config.getRetryCount() == null ? 3 : config.getRetryCount()));
        config.setTimeoutSeconds(clampTimeout(config.getTimeoutSeconds() == null ? 10 : config.getTimeoutSeconds()));
        configMapper.insert(config);
        return toVO(config);
    }

    @Transactional
    public void update(WebhookConfig config) {
        if (config.getUrl() != null && !config.getUrl().isBlank()) {
            validateWebhookUrl(config.getUrl());
        }
        boolean needsExisting = (config.getUrl() == null || config.getUrl().isBlank())
                || (config.getSecret() == null || config.getSecret().isBlank()
                || SECRET_MASK.equals(config.getSecret().trim()));
        WebhookConfig existing = needsExisting ? configMapper.selectById(config.getId()) : null;
        if (existing != null) {
            if (config.getUrl() == null || config.getUrl().isBlank()) {
                config.setUrl(existing.getUrl());
            }
            if (config.getSecret() == null || config.getSecret().isBlank()
                    || SECRET_MASK.equals(config.getSecret().trim())) {
                // 掩码回填/留空：保留原密钥，避免用户仅改名称/URL 时清空签名。
                config.setSecret(existing.getSecret());
            }
        }
        if (config.getRetryCount() != null) {
            config.setRetryCount(clampRetry(config.getRetryCount()));
        }
        if (config.getTimeoutSeconds() != null) {
            config.setTimeoutSeconds(clampTimeout(config.getTimeoutSeconds()));
        }
        config.setUpdatedTime(LocalDateTime.now());
        configMapper.updateById(config);
    }

    @Transactional
    public void delete(Long id) {
        configMapper.deleteById(id);
    }

    @Transactional
    public void toggle(Long id, boolean enabled) {
        WebhookConfig config = configMapper.selectById(id);
        if (config != null) {
            config.setEnabled(enabled ? 1 : 0);
            config.setUpdatedTime(LocalDateTime.now());
            configMapper.updateById(config);
        }
    }

    // ==================== 事件分发 ====================

    /**
     * 触发一个事件，向所有监听该事件的已启用 Webhook 发送请求。
     * 每个匹配的 Webhook 先落一条 PENDING delivery 记录，再异步投递。
     */
    public void dispatch(String event, Map<String, Object> payload) {
        List<WebhookConfig> hooks = configMapper.selectList(
                new LambdaQueryWrapper<WebhookConfig>()
                        .eq(WebhookConfig::getEnabled, 1));
        for (WebhookConfig hook : hooks) {
            if (!matchesEvent(hook, event)) continue;
            Long deliveryId;
            try {
                deliveryId = createPendingDelivery(hook, event, payload);
            } catch (Exception e) {
                log.warn("写 Webhook PENDING 投递记录失败: hookId={}, event={}, error={}", hook.getId(), event, e.getMessage());
                continue;
            }
            submitDelivery(hook, event, deliveryId,
                    () -> sendWebhook(hook, event, payload, deliveryId));
        }
    }

    /**
     * 测试 Webhook：发送测试事件到指定 URL。
     * secret 为空且提供 id 时，使用该 Webhook 已保存的签名密钥。
     */
    public boolean test(String url, String secret, Long webhookId) {
        try {
            PinnedTarget target = resolveValidatedTarget(url);
            String effectiveSecret = secret;
            if ((effectiveSecret == null || effectiveSecret.isBlank()) && webhookId != null) {
                WebhookConfig hook = configMapper.selectById(webhookId);
                if (hook != null) {
                    effectiveSecret = hook.getSecret();
                }
            }
            Map<String, Object> payload = Map.of(
                    "event", "test",
                    "timestamp", System.currentTimeMillis(),
                    "message", "这是一条来自闲鱼助手的测试消息"
            );
            String body = objectMapper.writeValueAsString(payload);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("X-Webhook-Event", List.of("test"));
            if (effectiveSecret != null && !effectiveSecret.isBlank()) {
                headers.put("X-Webhook-Signature", List.of(sign(body, effectiveSecret)));
            }
            int statusCode = postJson(target, headers, body, 10);
            return statusCode >= 200 && statusCode < 300;
        } catch (BusinessException e) {
            log.warn("Webhook 测试被拒绝: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Webhook 测试失败: url={}, error={}", redactUrl(url, e.getMessage()), e.getMessage());
            return false;
        }
    }

    // ==================== 投递历史查询 ====================

    /**
     * 查询指定 Webhook 的投递历史（含脱敏摘要），按创建时间倒序。
     */
    public List<WebhookDeliveryVO> listDeliveries(Long webhookId, int limit) {
        LambdaQueryWrapper<WebhookDelivery> wrapper = new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getWebhookId, webhookId)
                .orderByDesc(WebhookDelivery::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200)));
        List<WebhookDelivery> records = deliveryMapper.selectList(wrapper);
        List<WebhookDeliveryVO> result = new ArrayList<>(records.size());
        for (WebhookDelivery record : records) {
            result.add(toDeliveryVO(record));
        }
        return result;
    }

    // ==================== 内部方法 ====================

    @Transactional
    protected Long createPendingDelivery(WebhookConfig hook, String event, Map<String, Object> payload) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookId(hook.getId());
        delivery.setEvent(event);
        try {
            delivery.setPayloadJson(objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
        } catch (Exception e) {
            throw new BusinessException("Webhook payload 序列化失败");
        }
        delivery.setStatus("PENDING");
        delivery.setAttempts(0);
        delivery.setIdempotencyKey(buildIdempotencyKey(hook.getId(), event, payload));
        delivery.setCreatedTime(LocalDateTime.now());
        deliveryMapper.insert(delivery);
        return delivery.getId();
    }

    /**
     * 强唯一业务键，按优先级取第一个非空值。
     * accountId 刻意不在此列：它只标识账号，无法区分同账号下的不同订单，
     * 放进强键会让同分钟的第二笔订单事件被幂等约束吞掉。
     */
    private static final List<String> STRONG_IDEMPOTENCY_KEYS =
            List.of("orderId", "kamiConfigId", "goodsId", "alertKey");

    private String buildIdempotencyKey(Long webhookId, String event, Map<String, Object> payload) {
        String businessKey = null;
        if (payload != null) {
            for (String key : STRONG_IDEMPOTENCY_KEYS) {
                Object value = payload.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    businessKey = key + ":" + value;
                    break;
                }
            }
            if (businessKey == null) {
                // 没有强业务键时退回 accountId 兜底，并追加载荷摘要：
                // 只在载荷完全一致（真正的重复投递）时去重，避免同分钟不同事件互相顶掉。
                Object accountId = payload.get("accountId");
                String scope = accountId != null && !String.valueOf(accountId).isBlank()
                        ? "accountId:" + accountId
                        : "none";
                businessKey = scope + ":h" + Integer.toHexString(payload.hashCode());
            }
        }
        if (businessKey == null) {
            businessKey = "none";
        }
        // 分钟级时间片：同分钟内的重复分发去重；跨分钟后同业务事件可再次投递
        // （connection.lost 等无单调业务键的事件第二次触发不会被 UNIQUE 约束永久拦截）。
        String minuteSlice = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String key = webhookId + ":" + event + ":" + businessKey + ":" + minuteSlice;
        return key.length() > IDEMPOTENCY_KEY_MAX ? key.substring(0, IDEMPOTENCY_KEY_MAX) : key;
    }

    /**
     * 单次投递只尝试一次，失败交给 outbox 重投（{@link #redeliverFailedDeliveries()}）。
     *
     * <p>旧实现在 worker 线程里内联重试 `retryCount` 次并 `Thread.sleep(1s,2s,...)`：
     * 一个慢端点最坏占用 worker `retryCount × timeout + 退避睡眠 ≈ 33s`，而池只有
     * 2 个线程 —— 一个挂掉的集成方就能把队列堵死，其他端点的事件全部排在其后
     * （队头阻塞）。现在失败当场落 FAILED + `next_attempt_at` 退避时间，由每分钟
     * 一次的扫描按退避重投，worker 占用上限收敛到单次 timeout。</p>
     *
     * <p>总尝试次数语义不变：仍以 webhook 配置的 `retryCount` 为上限，只是从
     * "一次调用内连打"变成"跨轮重投"，退避期间不再空转占线程。</p>
     */
    private void sendWebhook(WebhookConfig hook, String event, Map<String, Object> payload, Long deliveryId) {
        int maxAttempts = Math.max(1, clampRetry(hook.getRetryCount() != null ? hook.getRetryCount() : 3));
        int timeout = clampTimeout(hook.getTimeoutSeconds() != null ? hook.getTimeoutSeconds() : 10);
        String lastError = null;
        Integer httpStatus = null;
        boolean success = false;

        Map<String, Object> enrichedPayload = new LinkedHashMap<>();
        enrichedPayload.put("event", event);
        enrichedPayload.put("timestamp", System.currentTimeMillis());
        enrichedPayload.put("data", payload);

        // 发送前再次校验 URL 并解析（历史数据或越权修改可能引入 SSRF 目标），
        // 后续请求只连接本次校验通过的地址。
        PinnedTarget target;
        try {
            target = resolveValidatedTarget(hook.getUrl());
        } catch (BusinessException e) {
            lastError = e.getMessage();
            log.warn("Webhook 地址不合法，已阻止发送: hookId={}, error={}", hook.getId(), lastError);
            updateDelivery(deliveryId, "FAILED", null, 1, lastError, null, maxAttempts);
            return;
        }

        try {
            String body = objectMapper.writeValueAsString(enrichedPayload);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("X-Webhook-Event", List.of(event));
            headers.put("X-Webhook-Attempt", List.of("1"));
            if (hook.getSecret() != null && !hook.getSecret().isBlank()) {
                headers.put("X-Webhook-Signature", List.of(sign(body, hook.getSecret())));
            }
            int statusCode = postJson(target, headers, body, timeout);
            httpStatus = statusCode;
            if (statusCode >= 200 && statusCode < 300) {
                success = true;
            } else {
                lastError = "HTTP " + statusCode;
                log.warn("Webhook 返回非成功状态码: hookId={}, status={}", hook.getId(), statusCode);
            }
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (lastError.length() > LAST_ERROR_MAX) {
                lastError = lastError.substring(0, LAST_ERROR_MAX);
            }
            log.warn("Webhook 发送失败: hookId={}, error={}", hook.getId(), lastError);
        }

        String status = success ? "SUCCESS" : "FAILED";
        // 成功时清空历史失败次数；失败时只累加真实的 1 次尝试，由 updateDelivery
        // 负责按累计次数算退避，累计到 maxAttempts 后不再安排重投。
        updateDelivery(deliveryId, status, httpStatus, success ? 0 : 1, lastError,
                success ? LocalDateTime.now() : null, maxAttempts);

        recordResult(hook, success, success ? null : lastError);
        log.info("Webhook 事件分发: event={}, hookId={}, success={}", event, hook.getId(), success);
    }

    /**
     * 失败投递重投（outbox 消费侧）：扫描可重投的记录重新发送。
     *
     * <p>除 {@code FAILED} 外还要扫 {@code PENDING}：投递记录是先落库、再交给
     * 异步线程发送的，若进程在"落库成功、发送未开始"之间被杀，记录会永远停在
     * PENDING，而旧实现只扫 FAILED，事件实质丢失。PENDING 只取创建超过 2 分钟的，
     * 避免和正在发送的同一条记录抢。</p>
     *
     * <p>累计尝试次数达到 {@link #MAX_RETRY_COUNT} 后停止，避免对死端点无限重试。</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void redeliverFailedDeliveries() {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<WebhookDelivery> due = new ArrayList<>();
        try {
            due.addAll(deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                    .eq(WebhookDelivery::getStatus, "FAILED")
                    .isNotNull(WebhookDelivery::getNextAttemptAt)
                    .le(WebhookDelivery::getNextAttemptAt, now)
                    .lt(WebhookDelivery::getAttempts, MAX_RETRY_COUNT)
                    .orderByAsc(WebhookDelivery::getNextAttemptAt)
                    .last("LIMIT " + REDELIVERY_BATCH_SIZE)));
            // 陈旧 PENDING：落库后未来得及发送（进程退出/线程池拒绝）
            due.addAll(deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                    .eq(WebhookDelivery::getStatus, "PENDING")
                    .lt(WebhookDelivery::getCreatedTime, now.minusMinutes(STALE_PENDING_MINUTES))
                    .orderByAsc(WebhookDelivery::getId)
                    .last("LIMIT " + REDELIVERY_BATCH_SIZE)));
        } catch (Exception e) {
            log.warn("扫描待重投 Webhook 投递记录失败: {}", e.getMessage());
            return;
        }
        for (WebhookDelivery delivery : due) {
            if (!inFlightDeliveries.add(delivery.getId())) {
                // 上一轮还在发这条，别并发重发造成重复投递
                continue;
            }
            WebhookConfig hook = configMapper.selectById(delivery.getWebhookId());
            if (hook == null || hook.getEnabled() == null || hook.getEnabled() != 1) {
                inFlightDeliveries.remove(delivery.getId());
                continue;
            }
            Map<String, Object> payload = parsePayload(delivery.getPayloadJson());
            if (payload == null) {
                inFlightDeliveries.remove(delivery.getId());
                updateDelivery(delivery.getId(), "FAILED", null,
                        delivery.getAttempts() == null ? 1 : delivery.getAttempts() + 1,
                        "payload 已损坏，停止重投", null);
                continue;
            }
            // 队列打满时保持 FAILED 原样，下轮扫描自然重试；记录不会被吞。
            try {
                CompletableFuture.runAsync(() -> {
                    try {
                        sendWebhook(hook, delivery.getEvent(), payload, delivery.getId());
                    } catch (Exception e) {
                        log.warn("Webhook 重投异常: deliveryId={}, error={}", delivery.getId(), e.getMessage());
                    } finally {
                        inFlightDeliveries.remove(delivery.getId());
                    }
                }, executor);
            } catch (RejectedExecutionException e) {
                inFlightDeliveries.remove(delivery.getId());
                log.warn("Webhook 重投队列已满，等待下轮扫描: deliveryId={}", delivery.getId());
            }
        }
    }

    /** 解析持久化的 payload；返回 null 表示内容损坏。 */
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private void updateDelivery(Long deliveryId, String status, Integer httpStatus,
                                int attempts, String lastError, LocalDateTime deliveredAt) {
        updateDelivery(deliveryId, status, httpStatus, attempts, lastError, deliveredAt, Integer.MAX_VALUE);
    }

    /**
     * @param maxAttempts 该 hook 配置的总尝试次数上限；累计达到后把
     *                    {@code next_attempt_at} 置空，重投扫描自然不再捞它。
     *                    传 {@link Integer#MAX_VALUE} 表示只受全局
     *                    {@link #MAX_RETRY_COUNT} 约束。
     */
    private void updateDelivery(Long deliveryId, String status, Integer httpStatus,
                                int attempts, String lastError, LocalDateTime deliveredAt,
                                int maxAttempts) {
        if (deliveryId == null) return;
        try {
            WebhookDelivery delivery = deliveryMapper.selectById(deliveryId);
            if (delivery == null) return;
            delivery.setStatus(status);
            delivery.setHttpStatus(httpStatus);
            if (!"SUCCESS".equals(status)) {
                // 失败时累计尝试次数（跨轮重投），退避随累计次数增长
                int existing = delivery.getAttempts() == null ? 0 : delivery.getAttempts();
                int cumulative = existing + attempts;
                delivery.setAttempts(cumulative);
                delivery.setLastError(truncate(lastError, LAST_ERROR_MAX));
                delivery.setDeliveredAt(null);
                // 达到上限就不再安排下一次：next_attempt_at 为 NULL 时重投扫描
                // 的 `isNotNull` 条件会直接跳过，不需要额外的状态位。
                delivery.setNextAttemptAt(cumulative >= maxAttempts
                        ? null
                        : LocalDateTime.now().plusSeconds(backoffSeconds(cumulative)));
            } else {
                delivery.setAttempts(attempts);
                delivery.setLastError(null);
                delivery.setDeliveredAt(deliveredAt);
                delivery.setNextAttemptAt(null);
            }
            deliveryMapper.updateById(delivery);
        } catch (Exception e) {
            log.warn("更新 Webhook 投递记录失败: deliveryId={}, error={}", deliveryId, e.getMessage());
        }
    }

    /** 指数退避秒数：attempts 越大间隔越长，封顶 BACKOFF_MAX_SECONDS。 */
    private int backoffSeconds(int attempts) {
        if (attempts <= 1) return BACKOFF_BASE_SECONDS;
        long exp = 1L << Math.min(attempts - 1, 8);
        return (int) Math.min(exp * BACKOFF_BASE_SECONDS, BACKOFF_MAX_SECONDS);
    }

    /**
     * 校验 URL 并解析目标，返回本次发送允许连接的地址集合。
     * 发送阶段直接使用这些已校验地址建立连接（保留原始主机名以生成 Host/SNI），
     * 关闭校验与发送之间二次 DNS 解析带来的重绑定窗口。
     */
    private PinnedTarget resolveValidatedTarget(String url) throws BusinessException {
        validateWebhookUrl(url);
        URI original = URI.create(url.trim());
        String host = original.getHost();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException("回调域名无法解析");
        }
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(this::isPrivateOrSpecialAddress)) {
            throw new BusinessException("回调地址不能指向本机或私有网络");
        }
        return new PinnedTarget(original, List.of(addresses));
    }

    /** 已通过 SSRF 校验的发送目标：原始 URI + 校验通过的地址集合。 */
    private record PinnedTarget(URI uri, List<InetAddress> addresses) {}

    /**
     * 派生一个绑定指定超时的客户端；pinned 非空时 DNS 解析固定为已校验地址。
     * newBuilder 复用共享连接池，派生成本可忽略。
     */
    OkHttpClient clientFor(List<InetAddress> pinned, int timeoutSeconds) {
        OkHttpClient.Builder builder = baseClient.newBuilder()
                .callTimeout(Duration.ofSeconds(timeoutSeconds));
        if (pinned != null && !pinned.isEmpty()) {
            builder.dns(hostname -> pinned);
        }
        return builder.build();
    }

    /** 发送 JSON POST，返回 HTTP 状态码；仅使用 target 中已校验的地址建立连接。 */
    private int postJson(PinnedTarget target, Map<String, List<String>> headers, String body, int timeoutSeconds)
            throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(target.uri().toString())
                .post(RequestBody.create(body, MediaType.parse("application/json")));
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        Request request = builder.build();
        OkHttpClient client = clientFor(target.addresses(), timeoutSeconds);
        try (Response response = client.newCall(request).execute()) {
            return response.code();
        }
    }

    private void recordResult(WebhookConfig hook, boolean success, String lastError) {
        hook.setLastTriggerAt(LocalDateTime.now());
        hook.setLastTriggerStatus(success ? "SUCCESS" : "FAILED");
        hook.setLastError(success ? null : truncate(lastError, 500));
        hook.setUpdatedTime(LocalDateTime.now());
        configMapper.updateById(hook);
    }

    private boolean matchesEvent(WebhookConfig hook, String event) {
        if (hook.getEvents() == null || hook.getEvents().isBlank()) return true;
        try {
            List<String> events = objectMapper.readValue(hook.getEvents(), List.class);
            return events.contains(event) || events.contains("*");
        } catch (Exception e) {
            return hook.getEvents().contains(event);
        }
    }

    private String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ==================== 响应 DTO ====================

    private WebhookVO toVO(WebhookConfig config) {
        WebhookVO vo = new WebhookVO();
        vo.setId(config.getId());
        vo.setName(config.getName());
        vo.setUrl(config.getUrl());
        vo.setEvents(config.getEvents());
        vo.setEnabled(config.getEnabled());
        vo.setHasSecret(config.getSecret() != null && !config.getSecret().isBlank());
        vo.setRetryCount(config.getRetryCount());
        vo.setTimeoutSeconds(config.getTimeoutSeconds());
        vo.setLastTriggerAt(config.getLastTriggerAt());
        vo.setLastTriggerStatus(config.getLastTriggerStatus());
        vo.setLastError(config.getLastError());
        vo.setCreatedTime(config.getCreatedTime());
        vo.setUpdatedTime(config.getUpdatedTime());
        return vo;
    }

    private WebhookDeliveryVO toDeliveryVO(WebhookDelivery record) {
        WebhookDeliveryVO vo = new WebhookDeliveryVO();
        vo.setId(record.getId());
        vo.setWebhookId(record.getWebhookId());
        vo.setEvent(record.getEvent());
        vo.setStatus(record.getStatus());
        vo.setHttpStatus(record.getHttpStatus());
        vo.setAttempts(record.getAttempts());
        vo.setNextAttemptAt(record.getNextAttemptAt());
        vo.setLastError(record.getLastError());
        vo.setPayloadSummary(payloadSummary(record.getPayloadJson()));
        vo.setDeliveredAt(record.getDeliveredAt());
        vo.setCreatedTime(record.getCreatedTime());
        return vo;
    }

    /** 脱敏摘要：只列出 payload 的字段名，绝不回显值。 */
    private String payloadSummary(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return "";
        try {
            Map<String, Object> parsed = objectMapper.readValue(payloadJson, Map.class);
            List<String> keys = new ArrayList<>(parsed.keySet());
            Collections.sort(keys);
            return String.join(",", keys);
        } catch (Exception e) {
            return "(unparsed)";
        }
    }

    // ==================== SSRF 防护 ====================

    /**
     * 校验 Webhook 回调地址：仅允许 http/https、必须含主机、不得包含用户信息，
     * 且解析后的所有地址不得指向本机、环回、链路本地、站点本地、组播或保留网段。
     */
    public void validateWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException("回调 URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("回调 URL 格式无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
            throw new BusinessException("仅支持 http/https 回调地址");
        }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null) {
            throw new BusinessException("回调地址必须包含有效主机且不能携带用户信息");
        }
        if (host.length() > 253 || host.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException("回调地址主机无效");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(this::isPrivateOrSpecialAddress)) {
                throw new BusinessException("回调地址不能指向本机或私有网络");
            }
        } catch (UnknownHostException e) {
            throw new BusinessException("回调域名无法解析");
        }
    }

    private boolean isPrivateOrSpecialAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first == 198 && (second == 18 || second == 19);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            boolean mappedIpv4 = true;
            for (int i = 0; i < 10; i++) {
                mappedIpv4 &= bytes[i] == 0;
            }
            mappedIpv4 &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (mappedIpv4) {
                byte[] ipv4Bytes = Arrays.copyOfRange(bytes, 12, 16);
                try {
                    return isPrivateOrSpecialAddress(InetAddress.getByAddress(ipv4Bytes));
                } catch (UnknownHostException impossible) {
                    return true;
                }
            }
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private int clampRetry(int value) {
        return Math.max(0, Math.min(value, MAX_RETRY_COUNT));
    }

    private int clampTimeout(int value) {
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(value, MAX_TIMEOUT_SECONDS));
    }

    private static String redactUrl(String url, String message) {
        return message == null ? "" : message;
    }
}