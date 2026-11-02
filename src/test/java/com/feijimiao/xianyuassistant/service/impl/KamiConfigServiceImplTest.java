package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KamiConfigServiceImplTest {

    @Mock
    private XianyuKamiConfigMapper configMapper;
    @Mock
    private XianyuKamiItemMapper itemMapper;
    @Mock
    private XianyuKamiUsageRecordMapper usageRecordMapper;
    @Mock
    private EmailNotifyService emailNotifyService;

    private KamiConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KamiConfigServiceImpl();
        ReflectionTestUtils.setField(service, "kamiConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "kamiItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "kamiUsageRecordMapper", usageRecordMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
    }

    @Test
    void acquireReusesTheSameActionBeforeLookingAtUnusedStock() {
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(66L);
        XianyuKamiItem reserved = reservedItem("order-1#delivery-1");
        when(configMapper.selectById(66L)).thenReturn(config);
        when(itemMapper.findByReservationKey(66L, "order-1#delivery-1")).thenReturn(reserved);

        XianyuKamiItem result = service.acquireKami(66L, "order-1#delivery-1");

        assertThat(result).isSameAs(reserved);
        verify(itemMapper, never()).findNextUnused(66L);
        verify(itemMapper, never()).markUsed(any(), any());
    }

    @Test
    void releaseDeletesUsageAndOnlyFreesTheMatchingReservation() {
        XianyuKamiItem reserved = reservedItem("order-1#delivery-1");
        XianyuKamiConfig config = new XianyuKamiConfig();
        config.setId(66L);
        when(itemMapper.selectById(77L)).thenReturn(reserved);
        when(usageRecordMapper.deleteByItemIdAndOrderId(77L, "order-1")).thenReturn(1);
        when(itemMapper.markUnusedIfReserved(77L, "order-1#delivery-1")).thenReturn(1);
        when(itemMapper.countByConfigId(66L)).thenReturn(3);
        when(itemMapper.countUsed(66L)).thenReturn(1);
        when(configMapper.selectById(66L)).thenReturn(config);

        service.releaseKamiReservation(77L, "order-1#delivery-1", "order-1");

        verify(usageRecordMapper).deleteByItemIdAndOrderId(77L, "order-1");
        verify(itemMapper).markUnusedIfReserved(77L, "order-1#delivery-1");
    }

    @Test
    void releaseRejectsAStaleActionWithoutDeletingAnything() {
        when(itemMapper.selectById(77L)).thenReturn(reservedItem("newer-action"));

        assertThatThrownBy(() ->
                service.releaseKamiReservation(77L, "stale-action", "order-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝释放");

        verify(usageRecordMapper, never()).deleteByItemIdAndOrderId(any(), any());
        verify(itemMapper, never()).markUnusedIfReserved(any(), any());
    }

    @Test
    void releaseFailureAfterUsageDeleteRollsBackTheTransaction() {
        when(itemMapper.selectById(77L)).thenReturn(reservedItem("order-1#delivery-1"));
        when(usageRecordMapper.deleteByItemIdAndOrderId(77L, "order-1")).thenReturn(1);
        when(itemMapper.markUnusedIfReserved(77L, "order-1#delivery-1")).thenReturn(0);

        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        KamiConfigService transactionalService = (KamiConfigService) proxyFactory.getProxy();

        assertThatThrownBy(() -> transactionalService.releaseKamiReservation(
                77L, "order-1#delivery-1", "order-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("预占释放失败");

        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
    }

    @Test
    void legacyReservationsAreMigratedByStableUnitPositionWithoutReusingFirstItem() {
        List<Long> configIds = List.of(66L, 67L);
        XianyuKamiItem first = reservedItem("order-1");
        XianyuKamiItem second = reservedItem("order-1");
        second.setId(78L);
        second.setKamiConfigId(67L);
        when(itemMapper.findOrderReservations(configIds, "order-1", "order-1#delivery-"))
                .thenReturn(List.of(first, second));
        when(itemMapper.migrateLegacyReservation(77L, "order-1", "order-1#delivery-1"))
                .thenReturn(1);
        when(itemMapper.migrateLegacyReservation(78L, "order-1", "order-1#delivery-2"))
                .thenReturn(1);

        XianyuKamiItem unitOne = service.findOrMigrateLegacyReservation(
                configIds, "order-1", "order-1#delivery-1", 0);
        XianyuKamiItem unitTwo = service.findOrMigrateLegacyReservation(
                configIds, "order-1", "order-1#delivery-2", 1);

        assertThat(unitOne).isSameAs(first);
        assertThat(unitTwo).isSameAs(second);
        assertThat(first.getOrderId()).isEqualTo("order-1#delivery-1");
        assertThat(second.getOrderId()).isEqualTo("order-1#delivery-2");
        verify(itemMapper).migrateLegacyReservation(77L, "order-1", "order-1#delivery-1");
        verify(itemMapper).migrateLegacyReservation(78L, "order-1", "order-1#delivery-2");
    }

    @Test
    void legacyMigrationFailsClosedWhenStablePositionBelongsToAnotherUnit() {
        XianyuKamiItem first = reservedItem("order-1#delivery-1");
        when(itemMapper.findOrderReservations(
                List.of(66L), "order-1", "order-1#delivery-"))
                .thenReturn(List.of(first));

        assertThatThrownBy(() -> service.findOrMigrateLegacyReservation(
                List.of(66L), "order-1", "order-1#delivery-2", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝跨件复用");

        verify(itemMapper, never()).migrateLegacyReservation(any(), any(), any());
    }

    private XianyuKamiItem reservedItem(String actionId) {
        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(77L);
        item.setKamiConfigId(66L);
        item.setKamiContent("secret-code");
        item.setStatus(1);
        item.setOrderId(actionId);
        return item;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;
        private boolean rolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource is needed; the interceptor boundary is what this test verifies.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
