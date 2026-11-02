package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.SyncProgressRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.service.AutoDeliveryService;
import com.feijimiao.xianyuassistant.service.GoodsInfoService;
import com.feijimiao.xianyuassistant.service.GoodsSkuPropertyService;
import com.feijimiao.xianyuassistant.service.GoodsSkuService;
import com.feijimiao.xianyuassistant.service.ItemDetailSyncService;
import com.feijimiao.xianyuassistant.service.PriceHistoryService;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndirectResourceAccountScopeTest {

    private static final Long USER_ID = 42L;

    @Mock
    private GoodsInfoService goodsInfoService;
    @Mock
    private GoodsSkuService goodsSkuService;
    @Mock
    private GoodsSkuPropertyService goodsSkuPropertyService;
    @Mock
    private PriceHistoryService priceHistoryService;
    @Mock
    private ItemDetailSyncService itemDetailSyncService;
    @Mock
    private AutoDeliveryService autoDeliveryService;
    @Mock
    private RbacService rbacService;

    private GoodsSkuController goodsSkuController;
    private PriceHistoryController priceHistoryController;
    private ItemController itemController;

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID, "restricted-user");
        lenient().doAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            Long accountId = invocation.getArgument(1);
            if (USER_ID.equals(userId) && !Long.valueOf(7L).equals(accountId)) {
                throw new BusinessException(403, "无权访问该账号的数据");
            }
            return null;
        }).when(rbacService).assertAccountVisible(nullable(Long.class), nullable(Long.class));

        goodsSkuController = new GoodsSkuController();
        ReflectionTestUtils.setField(goodsSkuController, "goodsSkuService", goodsSkuService);
        ReflectionTestUtils.setField(goodsSkuController, "goodsSkuPropertyService", goodsSkuPropertyService);
        ReflectionTestUtils.setField(goodsSkuController, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(goodsSkuController, "rbacService", rbacService);

        priceHistoryController = new PriceHistoryController();
        ReflectionTestUtils.setField(priceHistoryController, "priceHistoryService", priceHistoryService);
        ReflectionTestUtils.setField(priceHistoryController, "goodsInfoService", goodsInfoService);
        ReflectionTestUtils.setField(priceHistoryController, "rbacService", rbacService);

        itemController = new ItemController();
        ReflectionTestUtils.setField(itemController, "itemDetailSyncService", itemDetailSyncService);
        ReflectionTestUtils.setField(itemController, "autoDeliveryService", autoDeliveryService);
        ReflectionTestUtils.setField(itemController, "rbacService", rbacService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void skuListAllowsGoodsOwnedByVisibleAccount() {
        when(goodsInfoService.getByXyGoodId("goods-1")).thenReturn(goods("goods-1", 7L));
        when(goodsSkuService.listByXyGoodsId("goods-1")).thenReturn(List.of());

        ResultObject<?> result = goodsSkuController.listByGoodsId("goods-1");

        assertThat(result.getCode()).isEqualTo(200);
        verify(goodsSkuService).listByXyGoodsId("goods-1");
    }

    @Test
    void skuDetailRejectsGoodsOwnedByAnotherAccountBeforeReadingSkuData() {
        when(goodsInfoService.getByXyGoodId("goods-2")).thenReturn(goods("goods-2", 8L));

        ResultObject<?> result = goodsSkuController.skuDetail("goods-2");

        assertThat(result.getCode()).isEqualTo(403);
        verify(goodsSkuService, never()).listByXyGoodsId(any());
        verify(goodsSkuPropertyService, never()).listByXyGoodsId(any());
    }

    @Test
    void skuReadRejectsMissingGoodsAndGoodsWithoutKnownOwnerForRestrictedUsers() {
        when(goodsInfoService.getByXyGoodId("missing")).thenReturn(null);
        when(goodsInfoService.getByXyGoodId("owner-unknown")).thenReturn(goods("owner-unknown", null));

        assertThat(goodsSkuController.listByGoodsId("missing").getCode()).isEqualTo(403);
        assertThat(goodsSkuController.listByGoodsId("owner-unknown").getCode()).isEqualTo(403);
        verify(rbacService, times(2)).assertAccountVisible(USER_ID, null);
        verify(goodsSkuService, never()).listByXyGoodsId(any());
    }

    @Test
    void priceTrendAllowsGoodsOwnedByVisibleAccount() {
        when(goodsInfoService.getByXyGoodId("goods-1")).thenReturn(goods("goods-1", 7L));
        when(priceHistoryService.queryTrend("goods-1", 30)).thenReturn(List.of());
        PriceHistoryController.TrendReq req = new PriceHistoryController.TrendReq();
        req.setXyGoodId("goods-1");

        ResultObject<?> result = priceHistoryController.trend(req);

        assertThat(result.getCode()).isEqualTo(200);
        verify(priceHistoryService).queryTrend("goods-1", 30);
    }

    @Test
    void priceTrendRejectsGoodsOwnedByAnotherAccountBeforeReadingHistory() {
        when(goodsInfoService.getByXyGoodId("goods-2")).thenReturn(goods("goods-2", 8L));
        PriceHistoryController.TrendReq req = new PriceHistoryController.TrendReq();
        req.setXyGoodId("goods-2");

        ResultObject<?> result = priceHistoryController.trend(req);

        assertThat(result.getCode()).isEqualTo(403);
        verify(priceHistoryService, never()).queryTrend(any(), any(Integer.class));
    }

    @Test
    void priceTrendRejectsMissingGoodsAndGoodsWithoutKnownOwnerForRestrictedUsers() {
        when(goodsInfoService.getByXyGoodId("missing")).thenReturn(null);
        when(goodsInfoService.getByXyGoodId("owner-unknown")).thenReturn(goods("owner-unknown", null));
        PriceHistoryController.TrendReq missing = new PriceHistoryController.TrendReq();
        missing.setXyGoodId("missing");
        PriceHistoryController.TrendReq unknownOwner = new PriceHistoryController.TrendReq();
        unknownOwner.setXyGoodId("owner-unknown");

        assertThat(priceHistoryController.trend(missing).getCode()).isEqualTo(403);
        assertThat(priceHistoryController.trend(unknownOwner).getCode()).isEqualTo(403);
        verify(rbacService, times(2)).assertAccountVisible(USER_ID, null);
        verify(priceHistoryService, never()).queryTrend(any(), any(Integer.class));
    }

    @Test
    void syncSingleRejectsAnotherAccountBeforeStartingSync() {
        ResultObject<?> result = itemController.syncSingleItem(Map.of(
                "xianyuAccountId", "8", "xyGoodsId", "goods-2"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(itemDetailSyncService, never()).syncSingleItem(any(), any());
    }

    @Test
    void updateAutoConfirmShipmentRejectsAnotherAccountBeforeMutation() {
        ResultObject<?> result = itemController.updateAutoConfirmShipment(Map.of(
                "xianyuAccountId", "8", "xyGoodsId", "goods-2", "autoConfirmShipment", "1"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(autoDeliveryService, never()).updateAutoConfirmShipment(any(), any(), any());
    }

    @Test
    void syncSingleAndAutoConfirmShipmentAllowVisibleAccount() {
        when(itemDetailSyncService.syncSingleItem(7L, "goods-1")).thenReturn(true);
        Map<String, Object> params = Map.of("xianyuAccountId", "7", "xyGoodsId", "goods-1");

        assertThat(itemController.syncSingleItem(params).getCode()).isEqualTo(200);
        assertThat(itemController.updateAutoConfirmShipment(Map.of(
                "xianyuAccountId", 7, "xyGoodsId", "goods-1", "autoConfirmShipment", 1)).getCode())
                .isEqualTo(200);

        verify(itemDetailSyncService).syncSingleItem(7L, "goods-1");
        verify(autoDeliveryService).updateAutoConfirmShipment(7L, "goods-1", 1);
    }

    @Test
    void syncingStatusRejectsAnotherAccountBeforeCheckingService() {
        ResultObject<?> result = itemController.isSyncing(8L);

        assertThat(result.getCode()).isEqualTo(403);
        verify(itemDetailSyncService, never()).isSyncing(any());
    }

    @Test
    void syncingStatusAllowsVisibleAccount() {
        when(itemDetailSyncService.isSyncing(7L)).thenReturn(true);

        ResultObject<Boolean> result = itemController.isSyncing(7L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isTrue();
        verify(itemDetailSyncService).isSyncing(7L);
    }

    @Test
    void syncStatusErrorsDoNotExposeExceptionMessages() {
        String sensitiveMessage = "sync-secret-token-789";
        when(itemDetailSyncService.getProgress("sync-error"))
                .thenThrow(new RuntimeException(sensitiveMessage));
        when(itemDetailSyncService.isSyncing(7L))
                .thenThrow(new RuntimeException(sensitiveMessage));

        ResultObject<?> progressResult = itemController.getSyncProgress("sync-error");
        ResultObject<?> syncingResult = itemController.isSyncing(7L);

        assertThat(progressResult.getMsg()).isEqualTo("获取同步进度失败");
        assertThat(syncingResult.getMsg()).isEqualTo("检查同步状态失败");
        assertThat(progressResult.getMsg()).doesNotContain(sensitiveMessage);
        assertThat(syncingResult.getMsg()).doesNotContain(sensitiveMessage);
    }

    @Test
    void syncProgressAllowsVisibleAccountOwner() {
        SyncProgressRespDTO progress = progress(7L);
        when(itemDetailSyncService.getProgress("sync-1")).thenReturn(progress);

        ResultObject<SyncProgressRespDTO> result = itemController.getSyncProgress("sync-1");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isSameAs(progress);
    }

    @Test
    void syncProgressRejectsAnotherAccountAndUnknownOwner() {
        when(itemDetailSyncService.getProgress("sync-other")).thenReturn(progress(8L));
        when(itemDetailSyncService.getProgress("sync-unowned")).thenReturn(progress(null));

        assertThat(itemController.getSyncProgress("sync-other").getCode()).isEqualTo(403);
        assertThat(itemController.getSyncProgress("sync-unowned").getCode()).isEqualTo(403);
    }

    @Test
    void missingSyncProgressIsDeniedToRestrictedUsers() {
        when(itemDetailSyncService.getProgress("missing-sync")).thenReturn(null);

        ResultObject<?> result = itemController.getSyncProgress("missing-sync");

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getData()).isNull();
    }

    @Test
    void adminAndUnscopedRequestsAreNotRejectedForUnknownOwners() {
        UserContext.set(99L, "admin");
        when(goodsInfoService.getByXyGoodId("missing-goods")).thenReturn(null);
        when(goodsSkuService.listByXyGoodsId("missing-goods")).thenReturn(List.of());
        when(priceHistoryService.queryTrend("missing-goods", 30)).thenReturn(List.of());
        when(itemDetailSyncService.getProgress("missing-sync")).thenReturn(null);
        PriceHistoryController.TrendReq req = new PriceHistoryController.TrendReq();
        req.setXyGoodId("missing-goods");

        assertThat(goodsSkuController.listByGoodsId("missing-goods").getCode()).isEqualTo(200);
        assertThat(priceHistoryController.trend(req).getCode()).isEqualTo(200);
        assertThat(itemController.getSyncProgress("missing-sync").getCode()).isEqualTo(500);
        verify(rbacService, times(3)).assertAccountVisible(99L, null);
    }

    private static XianyuGoodsInfo goods(String goodsId, Long accountId) {
        XianyuGoodsInfo goods = new XianyuGoodsInfo();
        goods.setXyGoodId(goodsId);
        goods.setXianyuAccountId(accountId);
        return goods;
    }

    private static SyncProgressRespDTO progress(Long accountId) {
        SyncProgressRespDTO progress = new SyncProgressRespDTO();
        progress.setSyncId("sync");
        progress.setAccountId(accountId);
        return progress;
    }
}
