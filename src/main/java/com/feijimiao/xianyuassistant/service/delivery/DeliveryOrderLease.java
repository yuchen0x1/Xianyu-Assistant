package com.feijimiao.xianyuassistant.service.delivery;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单笔订单的发货租约，必须用完即关（推荐 try-with-resources 或 finally）。
 *
 * <p>租约的作用范围是「账号 + 订单号」：持有期间，其他线程不得对同一订单再发起发货，
 * 从根上避免两条发货链路（实时消息 / API 兜底 / 人工触发）同时扣卡密、互相回滚对方的卡。</p>
 *
 * <p>重复调用 {@link #close()} 是安全的，只有第一次生效。</p>
 */
public final class DeliveryOrderLease implements AutoCloseable {

    /** 订单号缺失时使用：不做互斥，但保持调用方写法一致。 */
    private static final DeliveryOrderLease NOOP = new DeliveryOrderLease(null, null, null);

    private final String key;
    private final DeliveryOrderLeaseManager manager;
    private final Thread owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DeliveryOrderLease(String key, DeliveryOrderLeaseManager manager, Thread owner) {
        this.key = key;
        this.manager = manager;
        this.owner = owner;
    }

    static DeliveryOrderLease noop() {
        return NOOP;
    }

    public boolean isNoop() {
        return manager == null;
    }

    public String getKey() {
        return key;
    }

    @Override
    public void close() {
        if (manager == null) {
            return;
        }
        if (Thread.currentThread() != owner) {
            // 跨线程关闭不生效，也不能把句柄标记为已关闭，否则真正持有者将无法释放
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        manager.release(key);
    }
}
