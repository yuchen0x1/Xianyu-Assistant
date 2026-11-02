package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiDeliverySchedulerTest {

    private static final long ACCOUNT_ID = 20L;

    @Mock private WebSocketService webSocketService;
    @Mock private OrderService orderService;
    @Mock private PendingOrderPollService pendingOrderPollService;
    @Mock private XianyuAccountMapper accountMapper;
    @Mock private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock private XianyuGoodsOrderMapper orderMapper;

    private ApiDeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ApiDeliveryScheduler();
        ReflectionTestUtils.setField(scheduler, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(scheduler, "orderService", orderService);
        ReflectionTestUtils.setField(scheduler, "pendingOrderPollService", pendingOrderPollService);
        ReflectionTestUtils.setField(scheduler, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(scheduler, "goodsConfigMapper", goodsConfigMapper);
        ReflectionTestUtils.setField(scheduler, "orderMapper", orderMapper);
        // 同步执行器：任务在当前线程立即跑完，便于对 mock 时序做断言
        ReflectionTestUtils.setField(scheduler, "taskExecutor", (java.util.concurrent.Executor) Runnable::run);
    }

    @Test
    void disconnectedAccountDoesNotPollWhenFallbackWasNotExplicitlyEnabled() {
        configureFallback(false, Long.toString(ACCOUNT_ID));

        scheduler.scheduledApiDelivery();

        verifyNoInteractions(accountMapper);
        verify(orderService, never()).queryPendingOrders(any());
    }

    @Test
    void allowlistedDisconnectedAccountDoesNotPollWhenEveryGoodsSwitchIsOff() {
        configureFallback(true, Long.toString(ACCOUNT_ID));
        when(accountMapper.selectList(null)).thenReturn(List.of(account()));
        when(goodsConfigMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(List.of(goodsConfig(0)));

        scheduler.scheduledApiDelivery();

        verify(webSocketService, never()).isConnected(ACCOUNT_ID);
        verify(orderService, never()).queryPendingOrders(ACCOUNT_ID);
    }

    @Test
    void disconnectedAccountDoesNotPollWithoutExplicitAccountAllowlist() {
        configureFallback(true, "99");
        when(accountMapper.selectList(null)).thenReturn(List.of(account()));

        scheduler.scheduledApiDelivery();

        verify(goodsConfigMapper, never()).selectByAccountId(ACCOUNT_ID);
        verify(orderService, never()).queryPendingOrders(ACCOUNT_ID);
    }

    @Test
    void connectedAccountDoesNotUseApiFallbackEvenWhenExplicitlyEligible() {
        configureFallback(true, Long.toString(ACCOUNT_ID));
        when(accountMapper.selectList(null)).thenReturn(List.of(account()));
        when(goodsConfigMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(List.of(goodsConfig(1)));
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(true);

        scheduler.scheduledApiDelivery();

        verify(orderService, never()).queryPendingOrders(ACCOUNT_ID);
    }

    @Test
    void explicitlyEligibleDisconnectedAccountMayPollAfterLocalChecks() {
        configureFallback(true, Long.toString(ACCOUNT_ID));
        when(accountMapper.selectList(null)).thenReturn(List.of(account()));
        when(goodsConfigMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(List.of(goodsConfig(1)));
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(false);
        when(orderService.queryPendingOrders(ACCOUNT_ID)).thenReturn(List.of());

        scheduler.scheduledApiDelivery();

        verify(orderService).queryPendingOrders(ACCOUNT_ID);
    }

    @Test
    void orderWithUnknownPreviousOutcomeIsNeverRetried() {
        configureFallback(true, Long.toString(ACCOUNT_ID));
        when(accountMapper.selectList(null)).thenReturn(List.of(account()));
        when(goodsConfigMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(List.of(goodsConfig(1)));
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(false);
        when(orderService.queryPendingOrders(ACCOUNT_ID)).thenReturn(List.of(pendingOrder("goods-1")));
        XianyuGoodsOrder unknownOrder = new XianyuGoodsOrder();
        unknownOrder.setState(0);
        unknownOrder.setFailReason("虚拟发货结果未知，请人工核对；卡密已保留预占");
        when(orderMapper.selectByAccountIdAndOrderId(ACCOUNT_ID, "order-1"))
                .thenReturn(unknownOrder);

        scheduler.scheduledApiDelivery();

        verify(pendingOrderPollService, never()).syncOrdersToDb(any(), any());
        verify(orderService, never()).consignDummyDeliveryWithConfig(any(), any(), any());
    }

    @Test
    void orderForNonEnabledGoodsDoesNotTriggerDetailSync() {
        configureFallback(true, Long.toString(ACCOUNT_ID));
        when(accountMapper.selectList(null)).thenReturn(List.of(account()));
        when(goodsConfigMapper.selectByAccountId(ACCOUNT_ID)).thenReturn(List.of(goodsConfig(1)));
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(false);
        when(orderService.queryPendingOrders(ACCOUNT_ID)).thenReturn(List.of(pendingOrder("goods-2")));

        scheduler.scheduledApiDelivery();

        verify(pendingOrderPollService, never()).syncOrdersToDb(any(), any());
        verify(orderService, never()).consignDummyDeliveryWithConfig(any(), any(), any());
    }

    private void configureFallback(boolean enabled, String accountIds) {
        ReflectionTestUtils.setField(scheduler, "apiFallbackEnabled", enabled);
        ReflectionTestUtils.setField(scheduler, "apiFallbackAccountIds", accountIds);
    }

    private XianyuAccount account() {
        XianyuAccount account = new XianyuAccount();
        account.setId(ACCOUNT_ID);
        return account;
    }

    private XianyuGoodsConfig goodsConfig(int enabled) {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAccountId(ACCOUNT_ID);
        config.setXyGoodsId("goods-1");
        config.setXianyuAutoDeliveryOn(enabled);
        return config;
    }

    private Map<String, Object> pendingOrder(String goodsId) {
        return Map.of("commonData", Map.of(
                "orderId", "order-1",
                "orderStatus", "待发货",
                "itemId", goodsId));
    }
}
