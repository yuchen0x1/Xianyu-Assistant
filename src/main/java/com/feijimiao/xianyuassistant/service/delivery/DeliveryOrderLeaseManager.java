package com.feijimiao.xianyuassistant.service.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 按「账号 + 订单号」维度的发货互斥租约。
 *
 * <h3>为什么需要它</h3>
 * <p>同一笔订单最多有三条链路会同时发起发货：</p>
 * <ul>
 *   <li>WebSocket 支付消息事件（{@code ChatMessageEventAutoDeliveryListener}）</li>
 *   <li>API 兜底调度（{@link com.feijimiao.xianyuassistant.service.ApiDeliveryScheduler}）</li>
 *   <li>页面上的手动触发 / 手工发货</li>
 * </ul>
 * <p>它们各自独立取卡、独立发送。两条链路并发时，先到达的一方可能已经取走卡密并把内容发给买家，
 * 后到达的一方再失败回滚，就会把「已交付的卡」放回库存——同一张卡被卖两次。
 * 或者两边各取一张卡，买家收到两份内容。</p>
 *
 * <p>因此凡是会「占用卡密并对外发送」的入口，都必须先拿到本租约。</p>
 *
 * <h3>可用性取舍</h3>
 * <ul>
 *   <li>租约是进程内内存态，与项目「单机 + SQLite」的部署形态一致；多实例部署需要换成数据库租约。</li>
 *   <li>持有线程崩溃导致的悬挂会在 {@code app.delivery.lease-ttl-seconds}（默认 10 分钟）后被接管，
 *       避免一笔订单永久卡死。</li>
 *   <li>同一线程重复获取是允许的（计数），关闭时逐层释放，防止未来出现嵌套调用时自锁。</li>
 * </ul>
 */
@Slf4j
@Component
public class DeliveryOrderLeaseManager {

    /** 悬挂租约的接管阈值：留足「多件订单 + 拟人化延迟」的正常执行时间。 */
    @Value("${app.delivery.lease-ttl-seconds:600}")
    private long leaseTtlSeconds = 600;

    private final ConcurrentMap<String, LeaseEntry> leases = new ConcurrentHashMap<>();

    /**
     * 尝试获取订单发货租约。
     *
     * @return 拿到租约时返回可关闭句柄；已有其他线程在发货时返回 {@link Optional#empty()}；
     *         订单号缺失时返回不做互斥的空租约
     */
    public Optional<DeliveryOrderLease> tryAcquire(Long accountId, String orderId) {
        if (accountId == null || orderId == null || orderId.isBlank()) {
            return Optional.of(DeliveryOrderLease.noop());
        }

        String key = buildKey(accountId, orderId);
        Thread current = Thread.currentThread();
        long now = System.currentTimeMillis();
        LeaseEntry[] acquired = new LeaseEntry[1];

        leases.compute(key, (k, existing) -> {
            if (existing == null) {
                LeaseEntry created = new LeaseEntry(current, now);
                acquired[0] = created;
                return created;
            }
            if (existing.owner == current) {
                existing.depth++;
                acquired[0] = existing;
                return existing;
            }
            long heldMillis = now - existing.acquiredAt;
            if (heldMillis >= ttlMillis()) {
                log.warn("发货租约超时未释放，按失效接管: key={}, heldMillis={}", key, heldMillis);
                LeaseEntry taken = new LeaseEntry(current, now);
                acquired[0] = taken;
                return taken;
            }
            return existing;
        });

        return acquired[0] == null
                ? Optional.empty()
                : Optional.of(new DeliveryOrderLease(key, this, current));
    }

    /** 诊断用：该订单当前是否有人在发货。 */
    public boolean isHeld(Long accountId, String orderId) {
        if (accountId == null || orderId == null || orderId.isBlank()) {
            return false;
        }
        return leases.containsKey(buildKey(accountId, orderId));
    }

    /** 诊断用：当前持有的租约数量。 */
    public int activeLeaseCount() {
        return leases.size();
    }

    void release(String key) {
        if (key == null) {
            return;
        }
        Thread current = Thread.currentThread();
        leases.computeIfPresent(key, (k, existing) -> {
            if (existing.owner != current) {
                // 租约已被超时接管，原持有者的关闭不再影响新持有者
                log.warn("发货租约由非持有线程释放，已忽略: key={}", key);
                return existing;
            }
            existing.depth--;
            return existing.depth > 0 ? existing : null;
        });
    }

    private long ttlMillis() {
        return TimeUnit.SECONDS.toMillis(leaseTtlSeconds > 0 ? leaseTtlSeconds : 600);
    }

    private String buildKey(Long accountId, String orderId) {
        return accountId + "|" + orderId;
    }

    private static final class LeaseEntry {
        private final Thread owner;
        private final long acquiredAt;
        private int depth = 1;

        private LeaseEntry(Thread owner, long acquiredAt) {
            this.owner = owner;
            this.acquiredAt = acquiredAt;
        }
    }
}
