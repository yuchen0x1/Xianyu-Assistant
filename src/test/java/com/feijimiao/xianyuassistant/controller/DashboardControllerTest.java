package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.DashboardStatsRespDTO;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.DashboardStatsService;
import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
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
class DashboardControllerTest {

    @Mock
    private DashboardStatsService dashboardStatsService;

    @Mock
    private RbacService rbacService;

    private DashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new DashboardController();
        ReflectionTestUtils.setField(controller, "dashboardStatsService", dashboardStatsService);
        ReflectionTestUtils.setField(controller, "rbacService", rbacService);
        UserContext.set(11L, "viewer");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void statsUseVisibleAccountScope() {
        when(rbacService.getCommandVisibleAccountIds(11L)).thenReturn(List.of(7L));
        when(dashboardStatsService.countAccounts(any(DataPanelAccountScope.class))).thenReturn(1L);
        when(dashboardStatsService.countItems(any(DataPanelAccountScope.class))).thenReturn(2L);
        when(dashboardStatsService.countSellingItems(any(DataPanelAccountScope.class))).thenReturn(3L);
        when(dashboardStatsService.countOffShelfItems(any(DataPanelAccountScope.class))).thenReturn(4L);
        when(dashboardStatsService.countSoldItems(any(DataPanelAccountScope.class))).thenReturn(5L);

        ResultObject<DashboardStatsRespDTO> result = controller.getDashboardStats();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getAccountCount()).isEqualTo(1);
        assertThat(result.getData().getItemCount()).isEqualTo(2);
        org.mockito.ArgumentCaptor<DataPanelAccountScope> scopeCaptor =
                org.mockito.ArgumentCaptor.forClass(DataPanelAccountScope.class);
        verify(dashboardStatsService).countAccounts(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getUserId()).isEqualTo(11L);
        assertThat(scopeCaptor.getValue().getAccountIds()).containsExactly(7L);
        verify(dashboardStatsService).countSoldItems(any(DataPanelAccountScope.class));
    }

    @Test
    void statsUseEmptyScopeWhenNoAccountsAreVisible() {
        when(rbacService.getCommandVisibleAccountIds(11L)).thenReturn(List.of());
        when(dashboardStatsService.countAccounts(any(DataPanelAccountScope.class))).thenReturn(0L);
        when(dashboardStatsService.countItems(any(DataPanelAccountScope.class))).thenReturn(0L);
        when(dashboardStatsService.countSellingItems(any(DataPanelAccountScope.class))).thenReturn(0L);
        when(dashboardStatsService.countOffShelfItems(any(DataPanelAccountScope.class))).thenReturn(0L);
        when(dashboardStatsService.countSoldItems(any(DataPanelAccountScope.class))).thenReturn(0L);

        ResultObject<DashboardStatsRespDTO> result = controller.getDashboardStats();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getAccountCount()).isZero();
        org.mockito.ArgumentCaptor<DataPanelAccountScope> scopeCaptor =
                org.mockito.ArgumentCaptor.forClass(DataPanelAccountScope.class);
        verify(dashboardStatsService).countAccounts(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getAccountIds()).isEmpty();
    }

    @Test
    void statsDoesNotExposeExceptionMessage() {
        String sensitiveMessage = "dashboard-secret-token-123";
        when(rbacService.getCommandVisibleAccountIds(11L)).thenReturn(List.of(7L));
        when(dashboardStatsService.countAccounts(any(DataPanelAccountScope.class)))
                .thenThrow(new RuntimeException(sensitiveMessage));

        ResultObject<DashboardStatsRespDTO> result = controller.getDashboardStats();

        assertThat(result.getMsg()).isEqualTo("获取首页统计数据失败");
        assertThat(result.getMsg()).doesNotContain(sensitiveMessage);
    }

    @Test
    void adminUsesUnrestrictedScope() {
        when(rbacService.getCommandVisibleAccountIds(11L)).thenReturn(null);
        when(dashboardStatsService.countAccounts(any(DataPanelAccountScope.class))).thenReturn(9L);
        when(dashboardStatsService.countItems(any(DataPanelAccountScope.class))).thenReturn(8L);
        when(dashboardStatsService.countSellingItems(any(DataPanelAccountScope.class))).thenReturn(7L);
        when(dashboardStatsService.countOffShelfItems(any(DataPanelAccountScope.class))).thenReturn(6L);
        when(dashboardStatsService.countSoldItems(any(DataPanelAccountScope.class))).thenReturn(5L);

        ResultObject<DashboardStatsRespDTO> result = controller.getDashboardStats();

        assertThat(result.getData().getAccountCount()).isEqualTo(9);
        org.mockito.ArgumentCaptor<DataPanelAccountScope> scopeCaptor =
                org.mockito.ArgumentCaptor.forClass(DataPanelAccountScope.class);
        verify(dashboardStatsService).countAccounts(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getUserId()).isEqualTo(11L);
        assertThat(scopeCaptor.getValue().getAccountIds()).isNull();
    }
}
