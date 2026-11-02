package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.AutoDeliveryService;
import com.feijimiao.xianyuassistant.service.ItemDetailSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemControllerAccountScopeTest {

    private static final Long USER_ID = 42L;
    private static final Long VISIBLE_ACCOUNT_ID = 7L;
    private static final Long OTHER_ACCOUNT_ID = 8L;

    @Mock
    private ItemDetailSyncService itemDetailSyncService;
    @Mock
    private AutoDeliveryService autoDeliveryService;
    @Mock
    private RbacService rbacService;

    private ItemController itemController;

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID, "restricted-user");
        org.mockito.Mockito.doAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            Long accountId = invocation.getArgument(1);
            if (USER_ID.equals(userId) && !VISIBLE_ACCOUNT_ID.equals(accountId)) {
                throw new BusinessException(403, "无权访问该账号的数据");
            }
            return null;
        }).when(rbacService).assertAccountVisible(nullable(Long.class), nullable(Long.class));

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
    void syncSingleRejectsAnotherAccountBeforeStartingSync() {
        ResultObject<?> result = itemController.syncSingleItem(Map.of(
                "xianyuAccountId", OTHER_ACCOUNT_ID.toString(), "xyGoodsId", "goods-2"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(itemDetailSyncService, never()).syncSingleItem(anyLong(), anyString());
    }

    @Test
    void updateAutoConfirmShipmentRejectsAnotherAccountBeforeMutation() {
        ResultObject<?> result = itemController.updateAutoConfirmShipment(Map.of(
                "xianyuAccountId", OTHER_ACCOUNT_ID.toString(),
                "xyGoodsId", "goods-2",
                "autoConfirmShipment", "1"));

        assertThat(result.getCode()).isEqualTo(403);
        verify(autoDeliveryService, never()).updateAutoConfirmShipment(anyLong(), anyString(), any());
    }

    @Test
    void syncingRejectsAnotherAccountBeforeCheckingService() {
        ResultObject<?> result = itemController.isSyncing(OTHER_ACCOUNT_ID);

        assertThat(result.getCode()).isEqualTo(403);
        verify(itemDetailSyncService, never()).isSyncing(anyLong());
    }

    @Test
    void visibleAccountCanStartSyncUpdateAutoConfirmAndCheckSyncing() {
        when(itemDetailSyncService.syncSingleItem(VISIBLE_ACCOUNT_ID, "goods-1")).thenReturn(true);
        when(itemDetailSyncService.isSyncing(VISIBLE_ACCOUNT_ID)).thenReturn(true);

        ResultObject<?> syncResult = itemController.syncSingleItem(Map.of(
                "xianyuAccountId", VISIBLE_ACCOUNT_ID.toString(), "xyGoodsId", "goods-1"));
        ResultObject<?> updateResult = itemController.updateAutoConfirmShipment(Map.of(
                "xianyuAccountId", VISIBLE_ACCOUNT_ID,
                "xyGoodsId", "goods-1",
                "autoConfirmShipment", 1));
        ResultObject<Boolean> syncingResult = itemController.isSyncing(VISIBLE_ACCOUNT_ID);

        assertThat(syncResult.getCode()).isEqualTo(200);
        assertThat(updateResult.getCode()).isEqualTo(200);
        assertThat(syncingResult.getCode()).isEqualTo(200);
        assertThat(syncingResult.getData()).isTrue();
        verify(itemDetailSyncService).syncSingleItem(VISIBLE_ACCOUNT_ID, "goods-1");
        verify(autoDeliveryService).updateAutoConfirmShipment(VISIBLE_ACCOUNT_ID, "goods-1", 1);
        verify(itemDetailSyncService).isSyncing(VISIBLE_ACCOUNT_ID);
    }
}
