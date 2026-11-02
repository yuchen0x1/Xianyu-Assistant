package com.feijimiao.xianyuassistant.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 业务指标收集器：通过 Micrometer 记录关键业务指标，
 * 暴露给 Prometheus 采集（/actuator/prometheus）。
 */
@Slf4j
@Service
public class BusinessMetricsService {

    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public BusinessMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        log.info("业务指标收集器已初始化");
    }

    /** 递增一个业务计数器 */
    public void increment(String metricName, String... tags) {
        Counter counter = counterCache.computeIfAbsent(metricName + String.join("", tags),
                k -> Counter.builder("xianyu." + metricName)
                        .description(getDescription(metricName))
                        .tags(tags)
                        .register(meterRegistry));
        counter.increment();
    }

    /** 递增指定数量 */
    public void increment(String metricName, double amount, String... tags) {
        Counter counter = counterCache.computeIfAbsent(metricName + String.join("", tags),
                k -> Counter.builder("xianyu." + metricName)
                        .description(getDescription(metricName))
                        .tags(tags)
                        .register(meterRegistry));
        counter.increment(amount);
    }

    /** 记录操作耗时 */
    public void recordTime(String metricName, long durationMs, String... tags) {
        Timer timer = timerCache.computeIfAbsent(metricName + String.join("", tags),
                k -> Timer.builder("xianyu." + metricName + ".duration")
                        .description(getDescription(metricName) + "耗时")
                        .tags(tags)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry));
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 记录带耗时的方法执行 */
    public <T> T time(String metricName, Supplier<T> supplier, String... tags) {
        long start = System.currentTimeMillis();
        try {
            return supplier.get();
        } finally {
            recordTime(metricName, System.currentTimeMillis() - start, tags);
        }
    }

    /** 记录不带返回值的耗时操作 */
    public void time(String metricName, Runnable runnable, String... tags) {
        long start = System.currentTimeMillis();
        try {
            runnable.run();
        } finally {
            recordTime(metricName, System.currentTimeMillis() - start, tags);
        }
    }

    // ========== 快捷方法 ==========

    public void recordDelivery(String accountId, String result) {
        increment("delivery.total", "account", accountId, "result", result);
    }

    public void recordAutoReply(String accountId, String result) {
        increment("autoreply.total", "account", accountId, "result", result);
    }

    public void recordConnection(String accountId, String event) {
        increment("connection.event", "account", accountId, "event", event);
    }

    public void recordApiCall(String apiName, String result) {
        increment("api.call", "api", apiName, "result", result);
    }

    public void recordJobExecution(String jobType, String result) {
        increment("job.execution", "type", jobType, "result", result);
    }

    private String getDescription(String metricName) {
        return switch (metricName) {
            case "delivery.total" -> "发货总数";
            case "autoreply.total" -> "自动回复总数";
            case "connection.event" -> "连接事件";
            case "api.call" -> "API调用";
            case "job.execution" -> "任务执行";
            default -> metricName;
        };
    }
}
