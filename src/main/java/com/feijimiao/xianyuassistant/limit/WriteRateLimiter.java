package com.feijimiao.xianyuassistant.limit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 滑动窗口写操作限流器（按账号 + 桶命名空间隔离）。
 *
 * <p>参考 goofish-cli 的 limiter.py：对每个 (账号, 桶) 维护一个时间戳滑动窗口，
 * 窗口内超过上限即拒绝，并返回需要等待的秒数。窗口是内存态，进程重启后自动清零。</p>
 */
@Slf4j
@Component
public class WriteRateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取一个写操作额度。
     *
     * @param accountId     账号ID（null 时不做限制）
     * @param bucket        限流桶名
     * @param limit         窗口内最大次数
     * @param windowSeconds 窗口时长（秒）
     * @return 0 表示放行；大于 0 表示需等待的秒数
     */
    public long tryAcquire(Long accountId, String bucket, int limit, long windowSeconds) {
        if (accountId == null || limit <= 0 || windowSeconds <= 0) {
            return 0L;
        }
        String key = accountId + ":" + bucket;
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;

        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            // 清理过期时间戳
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                long oldest = timestamps.peekFirst();
                long waitMs = oldest + windowSeconds * 1000L - now;
                long waitSeconds = Math.max(1L, (waitMs + 999L) / 1000L);
                log.warn("账号{}写操作触发限流: bucket={}, limit={}/{}{}s, 需等待{}秒",
                        accountId, bucket, limit, windowSeconds, waitSeconds);
                return waitSeconds;
            }
            timestamps.addLast(now);
            return 0L;
        }
    }

    /** 删除某账号的全部限流窗口（账号删除/解绑时调用）。 */
    public void clear(Long accountId) {
        if (accountId == null) {
            return;
        }
        windows.keySet().removeIf(key -> key.startsWith(accountId + ":"));
    }
}
