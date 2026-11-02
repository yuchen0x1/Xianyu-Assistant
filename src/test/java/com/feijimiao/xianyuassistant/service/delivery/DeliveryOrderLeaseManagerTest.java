package com.feijimiao.xianyuassistant.service.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发货租约的核心契约：同订单互斥、同线程可重入、跨线程关闭无效、悬挂可接管。
 */
class DeliveryOrderLeaseManagerTest {

    private DeliveryOrderLeaseManager manager;

    @BeforeEach
    void setUp() {
        manager = new DeliveryOrderLeaseManager();
    }

    @Test
    void secondThreadCannotAcquireWhileHeld() throws Exception {
        try (DeliveryOrderLease ignored = manager.tryAcquire(1L, "order-1").orElseThrow()) {
            AtomicBoolean acquiredByOtherThread = new AtomicBoolean(true);
            Thread other = new Thread(() ->
                    acquiredByOtherThread.set(manager.tryAcquire(1L, "order-1").isPresent()));
            other.start();
            other.join(2000);

            assertThat(acquiredByOtherThread).isFalse();
            assertThat(manager.isHeld(1L, "order-1")).isTrue();
        }

        assertThat(manager.isHeld(1L, "order-1")).isFalse();
        assertThat(manager.tryAcquire(1L, "order-1")).isPresent();
    }

    @Test
    void differentOrdersDoNotBlockEachOther() {
        try (DeliveryOrderLease first = manager.tryAcquire(1L, "order-1").orElseThrow();
             DeliveryOrderLease second = manager.tryAcquire(1L, "order-2").orElseThrow()) {
            assertThat(first.isNoop()).isFalse();
            assertThat(second.isNoop()).isFalse();
            assertThat(manager.activeLeaseCount()).isEqualTo(2);
        }
    }

    @Test
    void sameThreadCanReenterAndReleasesOnlyAfterAllCloses() {
        DeliveryOrderLease outer = manager.tryAcquire(2L, "order-2").orElseThrow();
        DeliveryOrderLease inner = manager.tryAcquire(2L, "order-2").orElseThrow();

        outer.close();
        assertThat(manager.isHeld(2L, "order-2")).isTrue();

        inner.close();
        assertThat(manager.isHeld(2L, "order-2")).isFalse();
    }

    @Test
    void closingFromAnotherThreadIsIgnored() throws Exception {
        DeliveryOrderLease lease = manager.tryAcquire(3L, "order-3").orElseThrow();
        Thread other = new Thread(lease::close);
        other.start();
        other.join(2000);

        assertThat(manager.isHeld(3L, "order-3")).isTrue();

        lease.close();
        assertThat(manager.isHeld(3L, "order-3")).isFalse();
    }

    @Test
    void expiredLeaseCanBeTakenOverAndOldHolderCannotReleaseIt() throws Exception {
        ReflectionTestUtils.setField(manager, "leaseTtlSeconds", 1L);
        DeliveryOrderLease stale = manager.tryAcquire(4L, "order-4").orElseThrow();

        Thread.sleep(1200);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> takeover = pool.submit(() -> {
                Optional<DeliveryOrderLease> taken = manager.tryAcquire(4L, "order-4");
                // 故意不关闭：模拟接管方继续执行发货
                return taken.isPresent();
            });

            assertThat(takeover.get(2, TimeUnit.SECONDS)).isTrue();

            stale.close();
            assertThat(manager.isHeld(4L, "order-4")).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingOrderIdYieldsNoopLease() {
        assertThat(manager.tryAcquire(5L, null).orElseThrow().isNoop()).isTrue();
        assertThat(manager.tryAcquire(5L, "  ").orElseThrow().isNoop()).isTrue();
        assertThat(manager.tryAcquire(null, "order-5").orElseThrow().isNoop()).isTrue();
        assertThat(manager.activeLeaseCount()).isZero();
    }

    @Test
    void leaseIsReleasedWhenHolderThreadFinishes() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (DeliveryOrderLease ignored = manager.tryAcquire(6L, "order-6").orElseThrow()) {
                acquired.countDown();
            }
        });
        holder.start();
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        holder.join(2000);

        assertThat(manager.isHeld(6L, "order-6")).isFalse();
    }
}
