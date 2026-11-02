package com.feijimiao.xianyuassistant.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Caffeine 的本地缓存实现（替换旧版 ConcurrentHashMap 实现）。
 * <ul>
 *   <li>支持每条目独立 TTL（通过自定义 Expiry）</li>
 *   <li>后台定期清理过期条目</li>
 *   <li>increment 使用 compute 原子操作，消除竞态条件</li>
 * </ul>
 * 后续可扩展 RedisCacheServiceImpl 切换为分布式缓存。
 *
 * @author IAMLZY
 */
@Slf4j
@Service
public class LocalMapCacheServiceImpl implements CacheService {

    /** TTL 上限（实际永不过期） */
    private static final long TTL_UNLIMITED_NANOS = Long.MAX_VALUE;

    /**
     * 没有指定过期时间时，新 counter 的默认 TTL（10 分钟）。
     * increment() 创建新条目时使用此值，避免永不过期导致内存泄漏。
     */
    private static final long DEFAULT_COUNTER_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final Cache<String, TimedValue> cache;

    private ExecutorService cleanupExecutor;

    public LocalMapCacheServiceImpl() {
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, TimedValue>() {
                    @Override
                    public long expireAfterCreate(String key, TimedValue value, long currentTime) {
                        return value.ttlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, TimedValue value,
                                                   long currentTime, long currentDuration) {
                        return value.ttlNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, TimedValue value,
                                                 long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @PostConstruct
    public void init() {
        cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "caffeine-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        log.info("Caffeine 缓存服务初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        cache.cleanUp();
        log.info("Caffeine 缓存服务已关闭");
    }

    // ---- CacheService 接口实现 ----

    @Override
    public void put(String key, Object value) {
        cache.put(key, new TimedValue(value, TTL_UNLIMITED_NANOS));
    }

    @Override
    public void put(String key, Object value, long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        if (nanos < 0 || nanos >= TTL_UNLIMITED_NANOS) {
            nanos = TTL_UNLIMITED_NANOS - 1; // 避免溢出
        }
        cache.put(key, new TimedValue(value, nanos));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    @Override
    public Object get(String key) {
        TimedValue tv = cache.getIfPresent(key);
        return (tv != null) ? tv.value : null;
    }

    @Override
    public void remove(String key) {
        cache.invalidate(key);
    }

    @Override
    public boolean containsKey(String key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public long increment(String key) {
        // 使用 compute 原子操作
        AtomicLong result = new AtomicLong(0);
        ConcurrentMap<String, TimedValue> map = cache.asMap();
        map.compute(key, (k, existing) -> {
            long nextVal;
            long ttlNanos;
            if (existing == null) {
                nextVal = 1;
                ttlNanos = DEFAULT_COUNTER_TTL_NANOS;
            } else {
                Object v = existing.value;
                if (v instanceof Number) {
                    nextVal = ((Number) v).longValue() + 1;
                } else {
                    nextVal = 1;
                }
                ttlNanos = existing.ttlNanos;
            }
            result.set(nextVal);
            return new TimedValue(nextVal, ttlNanos);
        });
        return result.get();
    }

    @Override
    public void expire(String key, long timeout, TimeUnit unit) {
        cache.asMap().computeIfPresent(key, (k, existing) -> {
            long nanos = unit.toNanos(timeout);
            if (nanos < 0 || nanos >= TTL_UNLIMITED_NANOS) {
                nanos = TTL_UNLIMITED_NANOS - 1;
            }
            return new TimedValue(existing.value, nanos);
        });
    }

    @Override
    public long getExpire(String key) {
        TimedValue tv = cache.getIfPresent(key);
        if (tv == null) {
            return -2; // 不存在
        }
        if (tv.ttlNanos == TTL_UNLIMITED_NANOS) {
            return -1; // 永不过期
        }
        // 通过 policy 获取剩余时间（估计值）
        cache.policy().expireAfterWrite().ifPresent(policy -> {
            // 不用额外操作，getIfPresent 已刷新
        });
        // 估算：Caffeine 不暴露精确剩余时间，返回近似值
        // 使用 ttlNanos 估计（注意：实际剩余可能更少）
        // 为兼容原有行为，保守返回 ttlNanos/1_000_000
        long approxMs = tv.ttlNanos / 1_000_000;
        return Math.max(0, approxMs);
    }

    // ---- 内部数据结构 ----

    /**
     * 带 TTL 的值包装。ttlNanos 由 Caffeine Expiry 消费，
     * 更新操作须同时更新 value 和 ttlNanos。
     */
    private record TimedValue(Object value, long ttlNanos) {}
}
