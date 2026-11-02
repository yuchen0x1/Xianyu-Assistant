package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.service.ApiDeliveryResult;
import com.feijimiao.xianyuassistant.service.DeliveryBlockDecision;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.KamiConfigService;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.service.OrderService;
import com.feijimiao.xianyuassistant.service.SentMessageSaveService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryContext;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLease;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryOrderLeaseManager;
import com.feijimiao.xianyuassistant.service.delivery.DeliveryStrategyResolver;
import com.feijimiao.xianyuassistant.service.delivery.KamiDeliveryStrategy;
import com.feijimiao.xianyuassistant.service.delivery.OrderDetailFetcher;
import com.feijimiao.xianyuassistant.utils.HumanLikeDelayUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoDeliveryServiceImplTest {

    private static final Long RECORD_ID = 11L;
    private static final Long ACCOUNT_ID = 22L;
    private static final String GOODS_ID = "goods-33";
    private static final String ORDER_ID = "order-44";
    private static final String SID = "buyer-55@goofish";

    @Mock
    private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @Mock
    private XianyuGoodsOrderMapper orderMapper;
    @Mock
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock
    private WebSocketService webSocketService;
    @Mock
    private SentMessageSaveService sentMessageSaveService;
    @Mock
    private EmailNotifyService emailNotifyService;
    @Mock
    private OrderService orderService;
    @Mock
    private OrderDetailFetcher orderDetailFetcher;
    @Mock
    private DeliveryStrategyResolver deliveryStrategyResolver;
    @Mock
    private DeliveryBlockRuleService deliveryBlockRuleService;
    @Mock
    private KamiConfigService kamiConfigService;
    @Mock
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;

    private AutoDeliveryServiceImpl service;

    private final DeliveryOrderLeaseManager leaseManager = new DeliveryOrderLeaseManager();

    @BeforeEach
    void setUp() {
        service = new AutoDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "deliveryOrderLeaseManager", leaseManager);
        ReflectionTestUtils.setField(service, "goodsConfigMapper", goodsConfigMapper);
        ReflectionTestUtils.setField(service, "autoDeliveryConfigMapper", autoDeliveryConfigMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "autoReplyRecordMapper", autoReplyRecordMapper);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "sentMessageSaveService", sentMessageSaveService);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        ReflectionTestUtils.setField(service, "orderService", orderService);
        ReflectionTestUtils.setField(service, "orderDetailFetcher", orderDetailFetcher);
        ReflectionTestUtils.setField(service, "deliveryStrategyResolver", deliveryStrategyResolver);
        ReflectionTestUtils.setField(service, "deliveryBlockRuleService", deliveryBlockRuleService);
        lenient().when(deliveryBlockRuleService.evaluate(anyLong(), any(), any(), any()))
                .thenReturn(DeliveryBlockDecision.allow());
    }

    @Test
    void missingSessionIdRecordsExplicitFailureAndDeterministicPnmId() {
        XianyuGoodsConfig goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);
        when(goodsConfigMapper.selectByAccountAndGoodsId(ACCOUNT_ID, GOODS_ID)).thenReturn(goodsConfig);
        XianyuGoodsAutoDeliveryConfig deliveryConfig = new XianyuGoodsAutoDeliveryConfig();
        deliveryConfig.setAutoDeliveryContent("感谢购买");
        when(autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(ACCOUNT_ID, GOODS_ID))
                .thenReturn(deliveryConfig);

        service.handleAutoDelivery(ACCOUNT_ID, GOODS_ID, null, "buyer", "买家", ORDER_ID);

        ArgumentCaptor<XianyuGoodsOrder> captor = ArgumentCaptor.forClass(XianyuGoodsOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(-1);
        assertThat(captor.getValue().getFailReason()).contains("会话ID缺失");
        // pnm_id 参与唯一索引：必须按订单号派生，绝不能是空串（否则同账号第二笔记录撞索引丢失）
        assertThat(captor.getValue().getPnmId()).isEqualTo("order:" + ORDER_ID);
    }

    @Test
    void laterUnitFailureKeepsWholeOrderFailed() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(1);
        arrangeTwoUnitOrder(config);
        when(deliveryStrategyResolver.resolve(eq(1), any(DeliveryContext.class)))
                .thenReturn("first-content", "second-content");
        when(webSocketService.sendMessageWithOutcome(ACCOUNT_ID, "buyer-55", "buyer-55", "first-content"))
                .thenReturn(MessageSendResult.SUCCESS);
        when(webSocketService.sendMessageWithOutcome(ACCOUNT_ID, "buyer-55", "buyer-55", "second-content"))
                .thenReturn(MessageSendResult.FAILED);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(-1), any(), eq("第2/2件消息发送失败"));
        verify(orderMapper, never()).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(1), any(), any());
        verify(orderService, never()).confirmShipment(anyLong(), any());
    }

    @Test
    void blockRuleStopsBeforeDeliveryContentResolution() {
        XianyuGoodsConfig goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);
        when(goodsConfigMapper.selectByAccountAndGoodsId(ACCOUNT_ID, GOODS_ID)).thenReturn(goodsConfig);
        OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
        detail.buyNum = 1;
        when(orderDetailFetcher.fetch(ACCOUNT_ID, GOODS_ID, ORDER_ID)).thenReturn(detail);
        XianyuGoodsOrder currentOrder = new XianyuGoodsOrder();
        currentOrder.setBuyerUserId("buyer-55");
        when(orderMapper.selectByAccountIdAndOrderId(ACCOUNT_ID, ORDER_ID)).thenReturn(currentOrder);
        when(deliveryBlockRuleService.evaluate(ACCOUNT_ID, GOODS_ID, ORDER_ID, "buyer-55"))
                .thenReturn(DeliveryBlockDecision.block(
                        "buyer_has_order", "买家已有1笔其他订单", "请联系客服"));

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, -1, null, "发货风控拦截: 买家已有1笔其他订单");
        verify(deliveryStrategyResolver, never()).resolve(anyInt(), any());
        verify(webSocketService, never()).sendMessageWithOutcome(anyLong(), any(), any(), any());
        verify(orderService, never()).consignDummyDelivery(anyLong(), any(), any(), any());
    }

    @Test
    void concurrentTriggerIsSkippedBeforeAnySideEffect() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (DeliveryOrderLease ignored = leaseManager.tryAcquire(ACCOUNT_ID, ORDER_ID).orElseThrow()) {
                held.countDown();
                released.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(held.await(3, TimeUnit.SECONDS)).isTrue();

        try {
            service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);
        } finally {
            released.countDown();
            holder.join(3000);
        }

        verify(orderDetailFetcher, never()).fetch(anyLong(), any(), any());
        verify(webSocketService, never()).sendMessageWithOutcome(anyLong(), any(), any(), any());
        verify(orderMapper, never()).updateStateContentAndFailReason(anyLong(), any(), any(), any());
    }

    @Test
    void failedKamiMessageReleasesReservedItem() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(2);
        config.setKamiConfigIds("66");
        arrangeTwoUnitOrder(config);

        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(77L);
        item.setKamiConfigId(66L);
        item.setKamiContent("secret-code");
        when(kamiConfigService.acquireKami(eq(66L), any())).thenReturn(item);
        when(kamiUsageRecordMapper.insert(any(XianyuKamiUsageRecord.class))).thenReturn(1);

        KamiDeliveryStrategy kamiStrategy = new KamiDeliveryStrategy();
        ReflectionTestUtils.setField(kamiStrategy, "kamiConfigService", kamiConfigService);
        ReflectionTestUtils.setField(kamiStrategy, "kamiUsageRecordMapper", kamiUsageRecordMapper);
        DeliveryStrategyResolver resolver = new DeliveryStrategyResolver();
        ReflectionTestUtils.setField(resolver, "strategies", List.of(kamiStrategy));
        ReflectionTestUtils.setField(service, "deliveryStrategyResolver", resolver);

        when(webSocketService.sendMessageWithOutcome(ACCOUNT_ID, "buyer-55", "buyer-55", "secret-code"))
                .thenReturn(MessageSendResult.FAILED);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(kamiConfigService).releaseKamiReservation(
                77L, ORDER_ID + "#delivery-1", ORDER_ID);
        verify(orderMapper).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(-1), any(), eq("第1/2件消息发送失败"));
    }

    @Test
    void failedRequiredImageKeepsWholeOrderFailed() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(1);
        config.setAutoDeliveryImageUrl("https://local.invalid/required.png");
        arrangeTwoUnitOrder(config);
        when(deliveryStrategyResolver.resolve(eq(1), any(DeliveryContext.class)))
                .thenReturn("content");
        when(webSocketService.sendMessageWithOutcome(ACCOUNT_ID, "buyer-55", "buyer-55", "content"))
                .thenReturn(MessageSendResult.SUCCESS);
        when(webSocketService.sendImageMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "https://local.invalid/required.png", 800, 800))
                .thenReturn(MessageSendResult.FAILED);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, -1, "content", "第1/2件图片发送失败");
        verify(orderMapper, never()).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(1), any(), any());
    }

    @Test
    void allRequiredUnitsAndImagesStillCompleteNormally() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(1);
        config.setAutoDeliveryImageUrl("https://local.invalid/required.png");
        arrangeTwoUnitOrder(config);
        when(deliveryStrategyResolver.resolve(eq(1), any(DeliveryContext.class)))
                .thenReturn("first-content", "second-content");
        when(webSocketService.sendMessageWithOutcome(eq(ACCOUNT_ID), eq("buyer-55"), eq("buyer-55"), any()))
                .thenReturn(MessageSendResult.SUCCESS);
        when(webSocketService.sendImageMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "https://local.invalid/required.png", 800, 800))
                .thenReturn(MessageSendResult.SUCCESS);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, 1, "first-content\nsecond-content", null);
        verify(webSocketService, atLeastOnce()).sendImageMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "https://local.invalid/required.png", 800, 800);
    }

    @Test
    void unknownKamiMessageKeepsReservationPendingAndDoesNotConfirmOrder() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(2);
        config.setKamiConfigIds("66");
        config.setAutoConfirmShipment(1);
        arrangeOrder(config, 1);

        XianyuKamiItem item = new XianyuKamiItem();
        item.setId(77L);
        item.setKamiConfigId(66L);
        item.setKamiContent("secret-code");
        when(kamiConfigService.acquireKami(eq(66L), any())).thenReturn(item);
        when(kamiUsageRecordMapper.insert(any(XianyuKamiUsageRecord.class))).thenReturn(1);
        installKamiStrategy();
        when(webSocketService.sendMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "secret-code"))
                .thenReturn(MessageSendResult.UNKNOWN);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(kamiConfigService, never()).releaseKamiReservation(anyLong(), any(), any());
        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, 0, "secret-code",
                "第1/1件消息发送结果未知（10秒未收到服务端响应），请人工核对；卡密已保留预占");
        verify(orderMapper, never()).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(1), any(), any());
        verify(orderService, never()).confirmShipment(anyLong(), any());
    }

    @Test
    void onlyAcknowledgedDeliveryReachesAutoConfirmStep() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(1);
        config.setAutoConfirmShipment(1);
        arrangeOrder(config, 1);
        when(deliveryStrategyResolver.resolve(eq(1), any(DeliveryContext.class)))
                .thenReturn("confirmed-content");
        when(webSocketService.sendMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "confirmed-content"))
                .thenReturn(MessageSendResult.SUCCESS);
        AutoDeliveryServiceImpl spyService = spy(service);
        doNothing().when(spyService).executeAutoConfirmShipment(ACCOUNT_ID, ORDER_ID);

        spyService.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, 1, "confirmed-content", null);
        verify(spyService).executeAutoConfirmShipment(ACCOUNT_ID, ORDER_ID);
    }

    @Test
    void unknownRequiredImageLeavesOrderPendingAndUnconfirmed() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(1);
        config.setAutoConfirmShipment(1);
        config.setAutoDeliveryImageUrl("https://local.invalid/required.png");
        arrangeOrder(config, 1);
        when(deliveryStrategyResolver.resolve(eq(1), any(DeliveryContext.class))).thenReturn("content");
        when(webSocketService.sendMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "content"))
                .thenReturn(MessageSendResult.SUCCESS);
        when(webSocketService.sendImageMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "https://local.invalid/required.png", 800, 800))
                .thenReturn(MessageSendResult.UNKNOWN);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, 0, "content",
                "第1/1件图片发送结果未知（10秒未收到服务端响应），请人工核对");
        verify(orderService, never()).confirmShipment(anyLong(), any());
    }

    @Test
    void legacyHandlePathDoesNotTreatTimeoutAsSuccess() {
        XianyuGoodsConfig goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(1);
        config.setAutoDeliveryContent("legacy-content");
        when(goodsConfigMapper.selectByAccountAndGoodsId(ACCOUNT_ID, GOODS_ID)).thenReturn(goodsConfig);
        when(autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(ACCOUNT_ID, GOODS_ID)).thenReturn(config);
        when(webSocketService.sendMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "legacy-content"))
                .thenReturn(MessageSendResult.UNKNOWN);

        try (var ignored = mockStatic(HumanLikeDelayUtils.class)) {
            service.handleAutoDelivery(
                    ACCOUNT_ID, GOODS_ID, SID, "buyer-55", "buyer", ORDER_ID);
        }

        verify(orderMapper).insert(argThat((XianyuGoodsOrder record) ->
                record.getState() == 0
                        && "消息发送结果未知，请人工核对".equals(record.getFailReason())));
        verify(sentMessageSaveService, never()).saveAiAssistantReply(anyLong(), any(), any(), any(), any());
    }

    @Test
    void customDeliveryTimeoutIsPendingRatherThanSuccessful() {
        XianyuGoodsOrder record = new XianyuGoodsOrder();
        record.setId(RECORD_ID);
        record.setSid(SID);
        record.setXyGoodsId(GOODS_ID);
        when(orderMapper.selectByAccountIdAndOrderId(ACCOUNT_ID, ORDER_ID)).thenReturn(record);
        when(webSocketService.sendMessageWithOutcome(
                ACCOUNT_ID, "buyer-55", "buyer-55", "custom-content"))
                .thenReturn(MessageSendResult.UNKNOWN);

        var result = service.manualDelivery(ACCOUNT_ID, ORDER_ID, "custom-content");

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMsg()).contains("请人工核对");
        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, 0, "custom-content", "消息发送结果未知（10秒未收到服务端响应），请人工核对");
    }

    @Test
    void failedVirtualApiReleasesEveryPreparedKami() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(2);
        arrangeTwoUnitOrder(config);
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(false);
        AtomicInteger rollbackCount = new AtomicInteger();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            context.setReservation(com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation
                    .rollbackWith(rollbackCount::incrementAndGet));
            return context.getActionId().endsWith("-1") ? "first-code" : "second-code";
        }).when(deliveryStrategyResolver).resolve(eq(2), any(DeliveryContext.class));
        when(orderService.consignDummyDelivery(
                ACCOUNT_ID, ORDER_ID, "first-code\nsecond-code", List.of()))
                .thenReturn(ApiDeliveryResult.FAILED);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        assertThat(rollbackCount).hasValue(2);
        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, -1, "first-code\nsecond-code", "虚拟发货API失败");
        verify(orderMapper, never()).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(1), any(), any());
    }

    @Test
    void unknownVirtualApiKeepsPreparedKamiReservedAndLeavesOrderPending() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(2);
        arrangeOrder(config, 1);
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(false);
        AtomicInteger rollbackCount = new AtomicInteger();
        List<com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation> reservations =
                new java.util.ArrayList<>();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            var reservation = com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation
                    .rollbackWith(rollbackCount::incrementAndGet);
            reservations.add(reservation);
            context.setReservation(reservation);
            return "secret-code";
        }).when(deliveryStrategyResolver).resolve(eq(2), any(DeliveryContext.class));
        when(orderService.consignDummyDelivery(
                ACCOUNT_ID, ORDER_ID, "secret-code", List.of()))
                .thenReturn(ApiDeliveryResult.UNKNOWN);

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        assertThat(rollbackCount).hasValue(0);
        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, 0, "secret-code", "虚拟发货结果未知，请人工核对；卡密已保留预占");
        verify(orderService, never()).confirmShipment(anyLong(), any());

        reservations.forEach(com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation::rollback);
        assertThat(rollbackCount).hasValue(1);
    }

    @Test
    void oversizedVirtualTradeTextReleasesEveryPreparedKamiWithoutCallingApi() {
        XianyuGoodsAutoDeliveryConfig config = deliveryConfig(2);
        arrangeTwoUnitOrder(config);
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(false);
        AtomicInteger rollbackCount = new AtomicInteger();
        doAnswer(invocation -> {
            DeliveryContext context = invocation.getArgument(1);
            context.setReservation(com.feijimiao.xianyuassistant.service.delivery.DeliveryReservation
                    .rollbackWith(rollbackCount::incrementAndGet));
            return "x".repeat(100);
        }).when(deliveryStrategyResolver).resolve(eq(2), any(DeliveryContext.class));

        service.executeDelivery(RECORD_ID, ACCOUNT_ID, GOODS_ID, SID, ORDER_ID, "buyer", false);

        assertThat(rollbackCount).hasValue(2);
        verify(orderService, never()).consignDummyDelivery(anyLong(), any(), any(), any());
        verify(orderMapper).updateStateContentAndFailReason(
                RECORD_ID, -1, "x".repeat(100) + "\n" + "x".repeat(100),
                "虚拟发货内容超过200字");
        verify(orderMapper, never()).updateStateContentAndFailReason(
                eq(RECORD_ID), eq(1), any(), any());
    }

    private void arrangeTwoUnitOrder(XianyuGoodsAutoDeliveryConfig config) {
        arrangeOrder(config, 2);
    }

    private void arrangeOrder(XianyuGoodsAutoDeliveryConfig config, int buyNum) {
        XianyuGoodsConfig goodsConfig = new XianyuGoodsConfig();
        goodsConfig.setXianyuAutoDeliveryOn(1);
        when(goodsConfigMapper.selectByAccountAndGoodsId(ACCOUNT_ID, GOODS_ID)).thenReturn(goodsConfig);
        when(autoDeliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(ACCOUNT_ID, GOODS_ID)).thenReturn(config);
        when(webSocketService.isConnected(ACCOUNT_ID)).thenReturn(true);

        OrderDetailFetcher.OrderDetailInfo detail = new OrderDetailFetcher.OrderDetailInfo();
        detail.buyNum = buyNum;
        when(orderDetailFetcher.fetch(ACCOUNT_ID, GOODS_ID, ORDER_ID)).thenReturn(detail);
    }

    private void installKamiStrategy() {
        KamiDeliveryStrategy kamiStrategy = new KamiDeliveryStrategy();
        ReflectionTestUtils.setField(kamiStrategy, "kamiConfigService", kamiConfigService);
        ReflectionTestUtils.setField(kamiStrategy, "kamiUsageRecordMapper", kamiUsageRecordMapper);
        DeliveryStrategyResolver resolver = new DeliveryStrategyResolver();
        ReflectionTestUtils.setField(resolver, "strategies", List.of(kamiStrategy));
        ReflectionTestUtils.setField(service, "deliveryStrategyResolver", resolver);
    }

    private XianyuGoodsAutoDeliveryConfig deliveryConfig(int mode) {
        XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
        config.setDeliveryMode(mode);
        config.setAutoConfirmShipment(0);
        return config;
    }
}
