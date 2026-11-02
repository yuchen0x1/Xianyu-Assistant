package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.AutoReplyRecordService;
import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
import com.feijimiao.xianyuassistant.service.OrderQueryService;
import com.feijimiao.xianyuassistant.cache.CacheService;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuOperationLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataPanelControllerScopeTest {

    @Mock private CacheService cacheService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private AutoReplyRecordService autoReplyRecordService;
    @Mock private RbacService rbacService;
    @Mock private XianyuChatMessageMapper chatMessageMapper;
    @Mock private XianyuGoodsInfoMapper goodsInfoMapper;
    @Mock private XianyuAccountMapper accountMapper;
    @Mock private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock private XianyuOperationLogMapper operationLogMapper;

    private DataPanelController controller;

    @BeforeEach
    void setUp() {
        controller = new DataPanelController();
        ReflectionTestUtils.setField(controller, "cacheService", cacheService);
        ReflectionTestUtils.setField(controller, "orderQueryService", orderQueryService);
        ReflectionTestUtils.setField(controller, "autoReplyRecordService", autoReplyRecordService);
        ReflectionTestUtils.setField(controller, "rbacService", rbacService);
        ReflectionTestUtils.setField(controller, "chatMessageMapper", chatMessageMapper);
        ReflectionTestUtils.setField(controller, "goodsInfoMapper", goodsInfoMapper);
        ReflectionTestUtils.setField(controller, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(controller, "autoReplyRecordMapper", autoReplyRecordMapper);
        ReflectionTestUtils.setField(controller, "operationLogMapper", operationLogMapper);
        UserContext.set(11L, "viewer");
        when(orderQueryService.sumDeliverySuccessAmount(any(DataPanelAccountScope.class))).thenReturn(42.0);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void adminNullVisibleIdsProducesAllAccountScope() {
        when(rbacService.getCommandVisibleAccountIds(11L)).thenReturn(null);

        controller.getRealtimeRevenue();

        org.mockito.ArgumentCaptor<DataPanelAccountScope> scopeCaptor =
                org.mockito.ArgumentCaptor.forClass(DataPanelAccountScope.class);
        verify(orderQueryService).sumDeliverySuccessAmount(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getUserId()).isEqualTo(11L);
        assertThat(scopeCaptor.getValue().getAccountIds()).isNull();
    }

    @Test
    void restrictedEmptyVisibleIdsRemainEmpty() {
        when(rbacService.getCommandVisibleAccountIds(11L)).thenReturn(List.of());

        controller.getRealtimeRevenue();

        org.mockito.ArgumentCaptor<DataPanelAccountScope> scopeCaptor =
                org.mockito.ArgumentCaptor.forClass(DataPanelAccountScope.class);
        verify(orderQueryService).sumDeliverySuccessAmount(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getUserId()).isEqualTo(11L);
        assertThat(scopeCaptor.getValue().getAccountIds()).isEmpty();
    }
}
