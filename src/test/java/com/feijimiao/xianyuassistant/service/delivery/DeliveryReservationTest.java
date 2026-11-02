package com.feijimiao.xianyuassistant.service.delivery;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryReservationTest {

    @Test
    void commitRunsCommitActionAndBlocksLaterRollback() {
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rolledBack = new AtomicInteger();
        DeliveryReservation reservation = DeliveryReservation.rollbackAndCommitWith(
                rolledBack::incrementAndGet, committed::incrementAndGet);

        reservation.commit();
        reservation.rollback();

        assertThat(committed).hasValue(1);
        assertThat(rolledBack).hasValue(0);
    }

    @Test
    void rollbackRunsRollbackActionAndBlocksLaterCommit() {
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rolledBack = new AtomicInteger();
        DeliveryReservation reservation = DeliveryReservation.rollbackAndCommitWith(
                rolledBack::incrementAndGet, committed::incrementAndGet);

        reservation.rollback();
        reservation.commit();

        assertThat(rolledBack).hasValue(1);
        assertThat(committed).hasValue(0);
    }

    @Test
    void holdFreezesReservationWithoutConfirmingDelivery() {
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rolledBack = new AtomicInteger();
        DeliveryReservation reservation = DeliveryReservation.rollbackAndCommitWith(
                rolledBack::incrementAndGet, committed::incrementAndGet);

        reservation.hold();
        reservation.rollback();
        reservation.commit();

        // 结果未知时既不能释放（会重复卖卡），也不能确认交付（会谎报库存已消耗）。
        assertThat(rolledBack).hasValue(0);
        assertThat(committed).hasValue(0);
    }

    @Test
    void commitIsIdempotent() {
        AtomicInteger committed = new AtomicInteger();
        DeliveryReservation reservation = DeliveryReservation.commitWith(committed::incrementAndGet);

        reservation.commit();
        reservation.commit();

        assertThat(committed).hasValue(1);
    }

    @Test
    void reusedReservationCanNeverBeReturnedToStock() {
        AtomicInteger committed = new AtomicInteger();
        DeliveryReservation reservation = DeliveryReservation.commitWith(committed::incrementAndGet);

        reservation.rollback();

        assertThat(committed).hasValue(0);
    }
}
