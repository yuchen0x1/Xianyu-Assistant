package com.feijimiao.xianyuassistant.service.delivery;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KamiDeliveryStrategyTest {

    @Mock
    private KamiConfigService kamiConfigService;
    @Mock
    private XianyuKamiUsageRecordMapper usageRecordMapper;

    private KamiDeliveryStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KamiDeliveryStrategy();
        ReflectionTestUtils.setField(strategy, "kamiConfigService", kamiConfigService);
        ReflectionTestUtils.setField(strategy, "kamiUsageRecordMapper", usageRecordMapper);
    }

    @Test
    void usageRecordFailureStopsDeliveryPreparation() {
        DeliveryContext context = context("66", "order-1#delivery-1");
        XianyuKamiItem item = item(77L, 66L, "secret-code");
        when(kamiConfigService.acquireKami(66L, "order-1#delivery-1")).thenReturn(item);
        when(usageRecordMapper.insert(any(XianyuKamiUsageRecord.class))).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> strategy.resolve(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), anyString(), anyString());
    }

    @Test
    void retryFindsExistingActionAcrossAllWarehousesWithoutConsumingAgain() {
        DeliveryContext context = context("1,2", "order-1#delivery-1");
        XianyuKamiItem item = item(88L, 2L, "same-secret-code");
        XianyuKamiUsageRecord usageRecord = new XianyuKamiUsageRecord();
        usageRecord.setKamiItemId(88L);
        usageRecord.setOrderId("order-1");
        when(kamiConfigService.findReservedKami(anyLong(), anyString()))
                .thenAnswer(invocation -> Long.valueOf(2L).equals(invocation.getArgument(0)) ? item : null);
        when(usageRecordMapper.findByItemIdAndOrderId(88L, "order-1")).thenReturn(usageRecord);

        String content = strategy.resolve(context);
        context.getReservation().rollback();

        assertThat(content).isEqualTo("same-secret-code");
        verify(kamiConfigService, never()).acquireKami(anyLong(), anyString());
        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), anyString(), anyString());
    }

    @Test
    void retryMigratesTheLegacyReservationForTheRequestedUnitBeforeAcquiringStock() {
        DeliveryContext context = context("1,2", "order-1#delivery-2");
        XianyuKamiItem item = item(89L, 2L, "legacy-second-code");
        item.setOrderId("order-1#delivery-2");
        XianyuKamiUsageRecord usageRecord = new XianyuKamiUsageRecord();
        usageRecord.setKamiItemId(89L);
        usageRecord.setOrderId("order-1");
        when(kamiConfigService.findOrMigrateLegacyReservation(
                java.util.List.of(1L, 2L), "order-1", "order-1#delivery-2", 1))
                .thenReturn(item);
        when(usageRecordMapper.findByItemIdAndOrderId(89L, "order-1")).thenReturn(usageRecord);

        String content = strategy.resolve(context);
        context.getReservation().rollback();

        assertThat(content).isEqualTo("legacy-second-code");
        verify(kamiConfigService, never()).acquireKami(anyLong(), anyString());
        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), anyString(), anyString());
    }

    @Test
    void migratedLegacyReservationWithoutUsageHistoryIsNotReturnedToStock() {
        DeliveryContext context = context("66", "order-1#delivery-1");
        XianyuKamiItem item = item(90L, 66L, "legacy-code-without-history");
        item.setOrderId("order-1#delivery-1");
        when(kamiConfigService.findOrMigrateLegacyReservation(
                java.util.List.of(66L), "order-1", "order-1#delivery-1", 0))
                .thenReturn(item);
        when(usageRecordMapper.insert(any(XianyuKamiUsageRecord.class))).thenReturn(1);

        String content = strategy.resolve(context);
        context.getReservation().rollback();

        assertThat(content).isEqualTo("legacy-code-without-history");
        verify(usageRecordMapper).insert(any(XianyuKamiUsageRecord.class));
        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), anyString(), anyString());
    }

    @Test
    void successfulDeliveryConfirmsTheCardInsteadOfLeavingItPending() {
        DeliveryContext context = context("66", "order-1#delivery-1");
        XianyuKamiItem item = item(91L, 66L, "fresh-code");
        when(kamiConfigService.acquireKami(66L, "order-1#delivery-1")).thenReturn(item);
        when(usageRecordMapper.insert(any(XianyuKamiUsageRecord.class))).thenReturn(1);

        String content = strategy.resolve(context);
        context.getReservation().commit();
        context.getReservation().rollback();

        assertThat(content).isEqualTo("fresh-code");
        verify(kamiConfigService).confirmKamiDelivered(91L, "order-1#delivery-1");
        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), anyString(), anyString());
    }

    @Test
    void unknownOutcomeKeepsTheCardReservedForManualReconciliation() {
        DeliveryContext context = context("66", "order-1#delivery-1");
        XianyuKamiItem item = item(92L, 66L, "uncertain-code");
        when(kamiConfigService.acquireKami(66L, "order-1#delivery-1")).thenReturn(item);
        when(usageRecordMapper.insert(any(XianyuKamiUsageRecord.class))).thenReturn(1);

        strategy.resolve(context);
        context.getReservation().hold();
        context.getReservation().rollback();

        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), anyString(), anyString());
        verify(kamiConfigService, never()).confirmKamiDelivered(anyLong(), anyString());
    }

    private DeliveryContext context(String configIds, String actionId) {
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setKamiConfigIds(configIds);
        return DeliveryContext.builder()
                .recordId(10L)
                .accountId(20L)
                .xyGoodsId("goods-1")
                .sId("buyer-1@goofish")
                .orderId("order-1")
                .buyerUserName("buyer")
                .deliveryConfig(config)
                .actionId(actionId)
                .build();
    }

    private XianyuKamiItem item(Long id, Long configId, String content) {
        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(id);
        item.setKamiConfigId(configId);
        item.setKamiContent(content);
        item.setStatus(1);
        return item;
    }
}
