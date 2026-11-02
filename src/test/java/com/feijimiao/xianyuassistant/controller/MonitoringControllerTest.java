package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.monitor.AlertService;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringControllerTest {

    @Mock
    private AlertService alertService;

    @Mock
    private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;

    private MonitoringController controller;

    @BeforeEach
    void setUp() {
        controller = new MonitoringController();
        ReflectionTestUtils.setField(controller, "alertService", alertService);
        ReflectionTestUtils.setField(controller, "rbacService", rbacService);
        // 默认按管理员（全量可见）语义放行
        org.mockito.Mockito.lenient()
                .when(rbacService.getVisibleAccountIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        org.mockito.Mockito.lenient()
                .when(rbacService.getCommandVisibleAccountIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
    }

    @Test
    void listAlertsDelegatesToService() {
        AlertEvent event = new AlertEvent();
        event.setAlertKey("k1");
        when(alertService.listByScope("ACTIVE", 1L, null, 50)).thenReturn(List.of(event));

        ResultObject<List<AlertEvent>> result = controller.listAlerts("ACTIVE", 1L, 50);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
        verify(alertService).listByScope("ACTIVE", 1L, null, 50);
    }

    @Test
    void ackActionDelegates() {
        when(alertService.ack("k1", null)).thenReturn(true);
        ResultObject<AlertEvent> result = controller.operateAlert(Map.of("key", "k1", "action", "ack"));
        assertThat(result.getCode()).isEqualTo(200);
    }

    @Test
    void silenceAndUnsilenceDelegates() {
        when(alertService.silence("k1", null)).thenReturn(true);
        assertThat(controller.operateAlert(Map.of("key", "k1", "action", "silence")).getCode()).isEqualTo(200);

        when(alertService.unsilence("k1", null)).thenReturn(true);
        assertThat(controller.operateAlert(Map.of("key", "k1", "action", "unsilence")).getCode()).isEqualTo(200);
    }

    @Test
    void alertLookupUsesPreciseAccountScopeEvenForOldKeys() {
        when(rbacService.getVisibleAccountIds(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(7L));
        AlertEvent visible = new AlertEvent();
        visible.setAlertKey("old-visible-key");
        visible.setAccountId(7L);
        when(alertService.getByKeyInScope("old-visible-key", List.of(7L))).thenReturn(visible);

        ResultObject<AlertEvent> visibleResult = controller.getAlert("old-visible-key");
        ResultObject<AlertEvent> hiddenResult = controller.getAlert("old-other-account-key");

        assertThat(visibleResult.getCode()).isEqualTo(200);
        assertThat(hiddenResult.getCode()).isEqualTo(500);
        verify(alertService).getByKeyInScope("old-other-account-key", List.of(7L));
    }

    @Test
    void adminAlertLookupUsesUnrestrictedScope() {
        when(rbacService.getVisibleAccountIds(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        AlertEvent otherAccountAlert = new AlertEvent();
        otherAccountAlert.setAlertKey("other-account-key");
        otherAccountAlert.setAccountId(8L);
        when(alertService.getByKeyInScope("other-account-key", null)).thenReturn(otherAccountAlert);

        ResultObject<AlertEvent> result = controller.getAlert("other-account-key");

        assertThat(result.getCode()).isEqualTo(200);
        verify(alertService).getByKeyInScope("other-account-key", null);
    }

    @Test
    void restrictedAlertOperationsPassScopeAndUnknownKeysFailClosed() {
        when(rbacService.getVisibleAccountIds(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(7L));
        when(alertService.ack("old-visible-key", List.of(7L))).thenReturn(true);

        assertThat(controller.operateAlert(Map.of("key", "old-visible-key", "action", "ack")).getCode())
                .isEqualTo(200);
        assertThat(controller.operateAlert(Map.of("key", "old-other-account-key", "action", "ack")).getCode())
                .isEqualTo(500);

        verify(alertService).ack("old-visible-key", List.of(7L));
        verify(alertService).ack("old-other-account-key", List.of(7L));
    }

    @Test
    void restrictedAlertLookupAndOperationsAllowSystemAlertsWithEmptyScope() {
        when(rbacService.getVisibleAccountIds(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        AlertEvent systemAlert = new AlertEvent();
        systemAlert.setAlertKey("system-key");
        when(alertService.getByKeyInScope("system-key", List.of())).thenReturn(systemAlert);
        when(alertService.resolve("system-key", List.of())).thenReturn(true);

        assertThat(controller.getAlert("system-key").getCode()).isEqualTo(200);
        assertThat(controller.operateAlert(Map.of("key", "system-key", "action", "resolve")).getCode())
                .isEqualTo(200);
    }

    @Test
    void alertErrorsDoNotExposeExceptionMessages() {
        String sensitiveMessage = "monitor-secret-token-456";
        when(alertService.listByScope("ACTIVE", null, null, 50))
                .thenThrow(new RuntimeException(sensitiveMessage));
        when(alertService.getByKeyInScope("secret-key", null))
                .thenThrow(new RuntimeException(sensitiveMessage));
        when(alertService.ack("secret-key", null))
                .thenThrow(new RuntimeException(sensitiveMessage));
        when(alertService.countActive())
                .thenThrow(new RuntimeException(sensitiveMessage));

        ResultObject<List<AlertEvent>> listResult = controller.listAlerts("ACTIVE", null, 50);
        ResultObject<AlertEvent> detailResult = controller.getAlert("secret-key");
        ResultObject<AlertEvent> operationResult = controller.operateAlert(
                Map.of("key", "secret-key", "action", "ack"));
        ResultObject<Map<String, Object>> overviewResult = controller.overview();

        assertThat(listResult.getMsg()).isEqualTo("查询告警列表失败");
        assertThat(detailResult.getMsg()).isEqualTo("查询告警失败");
        assertThat(operationResult.getMsg()).isEqualTo("操作告警失败");
        assertThat(overviewResult.getMsg()).isEqualTo("获取监控概览失败");
        assertThat(listResult.getMsg()).doesNotContain(sensitiveMessage);
        assertThat(detailResult.getMsg()).doesNotContain(sensitiveMessage);
        assertThat(operationResult.getMsg()).doesNotContain(sensitiveMessage);
        assertThat(overviewResult.getMsg()).doesNotContain(sensitiveMessage);
    }

    @Test
    void unknownActionRejected() {
        ResultObject<AlertEvent> result = controller.operateAlert(Map.of("key", "k1", "action", "destroy"));
        assertThat(result.getCode()).isEqualTo(500);
    }

    @Test
    void missingKeyRejected() {
        assertThat(controller.operateAlert(Map.of("action", "ack")).getCode()).isEqualTo(500);
    }

    @Test
    void overviewReportsAggregates() {
        when(alertService.countDeliveryFailures()).thenReturn(2L);
        when(alertService.countActive()).thenReturn(2L);

        ResultObject<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("activeAlerts")).isEqualTo(2L);
        assertThat(result.getData().get("deliveryFailures")).isEqualTo(2L);
    }

    @Test
    void overviewScopesAggregatesToVisibleAccounts() {
        when(rbacService.getCommandVisibleAccountIds(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(7L));
        when(alertService.countActive(List.of(7L))).thenReturn(1L);
        when(alertService.countDeliveryFailures(List.of(7L))).thenReturn(3L);

        ResultObject<Map<String, Object>> result = controller.overview();

        assertThat(result.getData().get("activeAlerts")).isEqualTo(1L);
        assertThat(result.getData().get("deliveryFailures")).isEqualTo(3L);
        verify(alertService).countActive(List.of(7L));
        verify(alertService).countDeliveryFailures(List.of(7L));
    }

    @Test
    void overviewCountsSystemAlertsForEmptyAccountScope() {
        when(rbacService.getCommandVisibleAccountIds(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(alertService.countActive(List.of())).thenReturn(2L);
        when(alertService.countDeliveryFailures(List.of())).thenReturn(3L);

        ResultObject<Map<String, Object>> result = controller.overview();

        assertThat(result.getData().get("activeAlerts")).isEqualTo(2L);
        assertThat(result.getData().get("deliveryFailures")).isEqualTo(3L);
        assertThat(result.getData().get("pendingDeadLetters")).isEqualTo(0L);
        verify(alertService).countActive(List.of());
        verify(alertService).countDeliveryFailures(List.of());
    }

    private AlertEvent activeEvent(String key) {
        AlertEvent event = new AlertEvent();
        event.setAlertKey(key);
        event.setStatus(AlertEvent.STATUS_ACTIVE);
        return event;
    }
}