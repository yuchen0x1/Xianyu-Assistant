package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.ApiDeliveryResult;
import com.feijimiao.xianyuassistant.service.DeliveryBlockDecision;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryContext;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLease;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLeaseManager;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryStrategyResolver;
import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplDeliveryReservationTest {

    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper configMapper;
    @Mock
    private XianyuGoodsOrderMapper orderMapper;
    @Mock
    private DeliveryStrategyResolver resolver;
    @Mock
    private com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter apiCallUtils;
    @Mock
    private AccountService accountService;
    @Mock
    private DeliveryBlockRuleService deliveryBlockRuleService;

    private OrderServiceImpl service;

    private final DeliveryOrderLeaseManager leaseManager = new DeliveryOrderLeaseManager();

    @BeforeEach
    void setUp() {
        service = spy(new OrderServiceImpl());
        ReflectionTestUtils.setField(service, "deliveryOrderLeaseManager", leaseManager);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "deliveryStrategyResolver", resolver);
        ReflectionTestUtils.setField(service, "xianyuAdapter", apiCallUtils);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "deliveryBlockRuleService", deliveryBlockRuleService);
        lenient().when(deliveryBlockRuleService.evaluate(any(), any(), any(), any()))
                .thenReturn(DeliveryBlockDecision.allow());
    }

    @Test
    void configuredConsignFailureRollsBackPreparedCard() {
        AtomicBoolean rolledBack = new AtomicBoolean();
        ArgumentCaptor<DeliveryContext> contextCaptor = ArgumentCaptor.forClass(DeliveryContext.class);
        arrangeCardConfig();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            context.setReservation(DeliveryReservation.rollbackWith(() -> rolledBack.set(true)));
            return "secret-code";
        }).when(resolver).resolve(eq(2), contextCaptor.capture());
        doReturn(ApiDeliveryResult.FAILED).when(service).consignDummyDelivery(
                eq(20L), eq("order-1"), eq("secret-code"), any(List.class));

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
        assertThat(rolledBack).isTrue();
        assertThat(contextCaptor.getValue().getActionId()).isEqualTo("order-1#delivery-1");
        verify(orderMapper, never()).insert(any(XianyuGoodsOrder.class));
    }

    @Test
    void concurrentDeliveryAttemptIsSkippedInsteadOfConsumingAnotherCard() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (DeliveryOrderLease ignored = leaseManager.tryAcquire(20L, "order-1").orElseThrow()) {
                held.countDown();
                released.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(held.await(3, TimeUnit.SECONDS)).isTrue();

        ApiDeliveryResult result;
        try {
            result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");
        } finally {
            released.countDown();
            holder.join(3000);
        }

        assertThat(result).isEqualTo(ApiDeliveryResult.SKIPPED);
        // 没有取卡、没有发请求：跳过必须发生在任何副作用之前
        verify(resolver, never()).resolve(anyInt(), any());
        verify(service, never()).consignDummyDelivery(any(), any(), any(), any());
        assertThat(leaseManager.isHeld(20L, "order-1")).isFalse();
    }

    @Test
    void blockRuleStopsConfiguredApiDeliveryBeforeReservation() {
        arrangeCardConfig();
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setId(9L);
        order.setBuyerUserId("buyer-1");
        when(orderMapper.selectByAccountIdAndOrderId(20L, "order-1")).thenReturn(order);
        when(deliveryBlockRuleService.evaluate(20L, "goods-1", "order-1", "buyer-1"))
                .thenReturn(DeliveryBlockDecision.block(
                        "buyer_credit_zero", "买家评价数为0（阈值0）", null));

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
        verify(orderMapper).updateStateContentAndFailReason(
                9L, -1, null, "发货风控拦截: 买家评价数为0（阈值0）");
        verify(resolver, never()).resolve(anyInt(), any());
        verify(service, never()).consignDummyDelivery(any(), any(), any(), any());
    }

    @Test
    void configuredConsignSuccessCommitsPreparedCard() {
        AtomicBoolean rolledBack = new AtomicBoolean();
        ArgumentCaptor<DeliveryContext> contextCaptor = ArgumentCaptor.forClass(DeliveryContext.class);
        arrangeCardConfig();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            context.setReservation(DeliveryReservation.rollbackWith(() -> rolledBack.set(true)));
            return "secret-code";
        }).when(resolver).resolve(eq(2), contextCaptor.capture());
        doReturn(ApiDeliveryResult.SUCCESS).when(service).consignDummyDelivery(
                eq(20L), eq("order-1"), eq("secret-code"), any(List.class));

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");
        contextCaptor.getValue().getReservation().rollback();

        assertThat(result).isEqualTo(ApiDeliveryResult.SUCCESS);
        assertThat(rolledBack).isFalse();
    }

    @Test
    void configuredConsignUnknownKeepsPreparedCardReservedAndMarksOrderPending() {
        AtomicBoolean rolledBack = new AtomicBoolean();
        ArgumentCaptor<DeliveryContext> contextCaptor = ArgumentCaptor.forClass(DeliveryContext.class);
        XianyuGoodsOrder order = arrangeCardConfig(1);
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            context.setReservation(DeliveryReservation.rollbackWith(() -> rolledBack.set(true)));
            return "secret-code";
        }).when(resolver).resolve(eq(2), contextCaptor.capture());
        doReturn(ApiDeliveryResult.UNKNOWN).when(service).consignDummyDelivery(
                eq(20L), eq("order-1"), eq("secret-code"), any(List.class));

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");

        assertThat(result).isEqualTo(ApiDeliveryResult.UNKNOWN);
        assertThat(rolledBack).isFalse();
        verify(orderMapper).updateStateContentAndFailReason(
                order.getId(), 0, "secret-code", "虚拟发货结果未知，请人工核对；卡密已保留预占");

        contextCaptor.getValue().getReservation().rollback();
        assertThat(rolledBack).isTrue();
    }

    @Test
    void tokenExpiredIsAConsignFailure() {
        when(accountService.getCookieByAccountId(20L)).thenReturn("cookie");
        when(apiCallUtils.callApi(
                eq(20L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(),
                eq("cookie"),
                anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        false, "{}", "令牌过期且自动刷新失败", true));

        ApiDeliveryResult result = service.consignDummyDelivery(20L, "order-1", "secret-code", List.of());

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
    }

    @Test
    void responseLossAfterRequestIsAnUnknownConsignOutcome() {
        when(accountService.getCookieByAccountId(20L)).thenReturn("cookie");
        when(apiCallUtils.callApi(
                eq(20L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(),
                eq("cookie"),
                anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        false, null, "响应为空", false, true));

        ApiDeliveryResult result = service.consignDummyDelivery(
                20L, "order-1", "secret-code", List.of());

        assertThat(result).isEqualTo(ApiDeliveryResult.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 401", "HTTP 500"})
    void explicitHttpRejectionIsAFailedConsignOutcome(String errorMessage) {
        when(accountService.getCookieByAccountId(20L)).thenReturn("cookie");
        when(apiCallUtils.callApi(
                eq(20L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                anyMap(),
                eq("cookie"),
                anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        false, "{}", errorMessage, false, false));

        ApiDeliveryResult result = service.consignDummyDelivery(
                20L, "order-1", "secret-code", List.of());

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
    }

    @Test
    void consignRejectsTradeTextLongerThanTwoHundredCharacters() {
        when(accountService.getCookieByAccountId(20L)).thenReturn("cookie");

        ApiDeliveryResult result = service.consignDummyDelivery(20L, "order-1", "x".repeat(201), List.of());

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
        verify(apiCallUtils, never()).callApi(any(), any(), anyMap(), any(), anyMap());
    }

    @Test
    void consignAcceptsExactlyTwoHundredCharactersWithoutTruncation() {
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        String tradeText = "x".repeat(200);
        when(accountService.getCookieByAccountId(20L)).thenReturn("cookie");
        when(apiCallUtils.callApi(
                eq(20L),
                eq("mtop.taobao.idle.logistics.merchant.consign.dummy"),
                dataCaptor.capture(),
                eq("cookie"),
                anyMap()))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        true, "{\"data\":{\"success\":true}}", null, false));

        ApiDeliveryResult result = service.consignDummyDelivery(20L, "order-1", tradeText, List.of());

        assertThat(result).isEqualTo(ApiDeliveryResult.SUCCESS);
        assertThat(dataCaptor.getValue().get("tradeText")).isEqualTo(tradeText);
    }

    @Test
    void configuredConsignPreparesEveryPurchasedCardAndCallsApiOnce() {
        XianyuGoodsOrder order = arrangeCardConfig(2);
        AtomicInteger rollbackCount = new AtomicInteger();
        AtomicInteger resolveCount = new AtomicInteger();
        List<String> actionIds = new java.util.ArrayList<>();
        List<DeliveryReservation> reservations = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            actionIds.add(context.getActionId());
            DeliveryReservation reservation = DeliveryReservation.rollbackWith(rollbackCount::incrementAndGet);
            reservations.add(reservation);
            context.setReservation(reservation);
            return resolveCount.incrementAndGet() == 1 ? "first-code" : "second-code";
        }).when(resolver).resolve(eq(2), any(DeliveryContext.class));
        doReturn(ApiDeliveryResult.SUCCESS).when(service).consignDummyDelivery(
                20L, "order-1", "first-code\nsecond-code", List.of());

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");
        reservations.forEach(DeliveryReservation::rollback);

        assertThat(result).isEqualTo(ApiDeliveryResult.SUCCESS);
        assertThat(actionIds).containsExactly("order-1#delivery-1", "order-1#delivery-2");
        assertThat(rollbackCount).hasValue(0);
        verify(service).consignDummyDelivery(20L, "order-1", "first-code\nsecond-code", List.of());
        verify(orderMapper).updateStateAndContent(order.getId(), 1, "first-code\nsecond-code");
    }

    @Test
    void configuredCardConsignFailsClosedWhenPurchaseQuantityIsMissing() {
        arrangeCardConfig(null);

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
        verify(resolver, never()).resolve(anyInt(), any(DeliveryContext.class));
        verify(service, never()).consignDummyDelivery(any(), any(), any(), any());
    }

    @Test
    void configuredConsignRollsBackAllCardsWhenCombinedTextExceedsLimit() {
        arrangeCardConfig(2);
        AtomicInteger rollbackCount = new AtomicInteger();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            context.setReservation(DeliveryReservation.rollbackWith(rollbackCount::incrementAndGet));
            return "x".repeat(100);
        }).when(resolver).resolve(eq(2), any(DeliveryContext.class));

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");

        assertThat(result).isEqualTo(ApiDeliveryResult.FAILED);
        assertThat(rollbackCount).hasValue(2);
        verify(service, never()).consignDummyDelivery(any(), any(), any(), any());
    }

    @Test
    void textConsignKeepsSingleMessageCompatibilityWithoutPurchaseQuantity() {
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setDeliveryMode(1);
        when(configMapper.findByAccountIdAndGoodsIdAndSkuId(20L, "goods-1", null))
                .thenReturn(config);
        when(resolver.resolve(eq(1), any(DeliveryContext.class))).thenReturn("plain-text");
        doReturn(ApiDeliveryResult.SUCCESS).when(service).consignDummyDelivery(
                20L, "order-1", "plain-text", List.of());

        ApiDeliveryResult result = service.consignDummyDeliveryWithConfig(20L, "goods-1", "order-1");

        assertThat(result).isEqualTo(ApiDeliveryResult.SUCCESS);
        verify(resolver).resolve(eq(1), any(DeliveryContext.class));
    }

    private void arrangeCardConfig() {
        arrangeCardConfig(1);
    }

    private XianyuGoodsOrder arrangeCardConfig(Integer buyNum) {
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setDeliveryMode(2);
        when(configMapper.findByAccountIdAndGoodsIdAndSkuId(20L, "goods-1", null))
                .thenReturn(config);
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setId(30L);
        order.setBuyNum(buyNum);
        when(orderMapper.selectByAccountIdAndOrderId(20L, "order-1")).thenReturn(order);
        return order;
    }
}
