package com.feijimiao.xianyuassistant.service.delivery;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.monitor.AlertService;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KamiReservationSweeperTest {

    @Mock
    private XianyuKamiItemMapper kamiItemMapper;
    @Mock
    private XianyuKamiUsageRecordMapper usageRecordMapper;
    @Mock
    private XianyuGoodsOrderMapper orderMapper;
    @Mock
    private KamiConfigService kamiConfigService;
    @Mock
    private AlertService alertService;

    private KamiReservationSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new KamiReservationSweeper(kamiItemMapper, usageRecordMapper, orderMapper,
                kamiConfigService, alertService);
        ReflectionTestUtils.setField(sweeper, "graceMinutes", 30L);
    }

    @Test
    void deliveredOrderIsConfirmedInsteadOfReleased() {
        arrangeStaleItem(77L, "order-1#delivery-1");
        arrangeUsage(77L, "order-1", 20L);
        arrangeOrder("order-1", 1);

        sweeper.sweep();

        verify(kamiConfigService).confirmKamiDelivered(77L, "order-1#delivery-1");
        verify(kamiConfigService, never()).releaseStaleKamiReservation(anyLong(), anyString(), anyString());
        verify(alertService).resolve("kami_reservation_stranded_77");
    }

    @Test
    void failedOrderReleasesTheCardBackToStock() {
        arrangeStaleItem(78L, "order-2#delivery-1");
        arrangeUsage(78L, "order-2", 20L);
        arrangeOrder("order-2", -1);
        when(kamiConfigService.releaseStaleKamiReservation(eq(78L), eq("order-2#delivery-1"), anyString()))
                .thenReturn(true);

        sweeper.sweep();

        verify(kamiConfigService).releaseStaleKamiReservation(eq(78L), eq("order-2#delivery-1"), anyString());
        verify(kamiConfigService, never()).confirmKamiDelivered(anyLong(), anyString());
    }

    @Test
    void pendingOrderIsNeverAutoReleasedAndRaisesAnAlert() {
        arrangeStaleItem(79L, "order-3#delivery-1");
        arrangeUsage(79L, "order-3", 20L);
        arrangeOrder("order-3", 0);

        sweeper.sweep();

        // 订单没结论时自动释放会把「可能已发给买家」的卡再卖一次，必须留给人工核对。
        verify(kamiConfigService, never()).releaseStaleKamiReservation(anyLong(), anyString(), anyString());
        verify(kamiConfigService, never()).confirmKamiDelivered(anyLong(), anyString());
        verify(alertService).alert(eq("kami_reservation_stranded_79"), anyString(), anyString(), eq("WARNING"), eq(20L));
    }

    @Test
    void missingOrderRecordRaisesAnAlertWithoutTouchingTheCard() {
        arrangeStaleItem(80L, "order-4#delivery-1");
        arrangeUsage(80L, "order-4", 20L);
        when(orderMapper.selectByAccountIdAndOrderId(20L, "order-4")).thenReturn(null);

        sweeper.sweep();

        verify(kamiConfigService, never()).releaseStaleKamiReservation(anyLong(), anyString(), anyString());
        verify(alertService).alert(eq("kami_reservation_stranded_80"), anyString(), anyString(), eq("WARNING"), eq(20L));
    }

    @Test
    void emptySweepDoesNothing() {
        when(kamiItemMapper.findStaleReservations(anyString(), anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verify(kamiConfigService, never()).confirmKamiDelivered(anyLong(), anyString());
    }

    private void arrangeStaleItem(Long itemId, String reservationKey) {
        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(itemId);
        item.setKamiConfigId(66L);
        item.setStatus(1);
        item.setOrderId(reservationKey);
        item.setUsedTime(LocalDateTime.now().minusHours(2));
        when(kamiItemMapper.findStaleReservations(anyString(), anyInt())).thenReturn(List.of(item));
    }

    private void arrangeUsage(Long itemId, String orderId, Long accountId) {
        XianyuKamiUsageRecord usage = new XianyuKamiUsageRecord();
        usage.setKamiItemId(itemId);
        usage.setOrderId(orderId);
        usage.setXianyuAccountId(accountId);
        when(usageRecordMapper.findByItemId(itemId)).thenReturn(List.of(usage));
    }

    private void arrangeOrder(String orderId, int state) {
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setOrderId(orderId);
        order.setXianyuAccountId(20L);
        order.setState(state);
        when(orderMapper.selectByAccountIdAndOrderId(20L, orderId)).thenReturn(order);
    }
}
