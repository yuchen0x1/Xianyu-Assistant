package com.feijimiao.xianyuassistant.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import com.feijimiao.xianyuassistant.monitor.mapper.AlertEventMapper;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.NotifyService;
import com.feijimiao.xianyuassistant.webhook.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertEventServiceTest {

    @Mock
    private AlertEventMapper alertEventMapper;
    @Mock
    private NotifyService notifyService;
    @Mock
    private WebhookService webhookService;

    private AlertEventService alertEventService;

    @BeforeEach
    void setUp() {
        alertEventService = new AlertEventService(alertEventMapper, notifyService, webhookService);
    }

    private AlertEvent existing(String key) {
        AlertEvent event = new AlertEvent();
        event.setId(1L);
        event.setAlertKey(key);
        return event;
    }

    @Test
    void successfulSendAdvancesWindowAndMarksSent() {
        when(alertEventMapper.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);
        when(alertEventMapper.bumpIfSendable("k1")).thenReturn(1);
        when(alertEventMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("k1"));
        when(notifyService.send(any(NotifyMessage.class))).thenReturn(NotifyService.SendResult.SENT);

        alertEventService.trigger("k1", "标题", "内容", "WARNING", null);

        verify(alertEventMapper).reopenIfResolved("k1", "内容", "WARNING");
        verify(alertEventMapper).markSent("k1", 1800);
        verify(alertEventMapper, org.mockito.Mockito.never()).bumpSendAttempts("k1");
        verify(webhookService).dispatch("alert.triggered", Map.of(
                "alertKey", "k1", "title", "标题", "level", "WARNING", "message", "内容"));
    }

    @Test
    void failedSendAccumulatesAttemptsWithoutConsumingWindow() {
        when(alertEventMapper.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);
        when(alertEventMapper.bumpIfSendable("k1")).thenReturn(1);
        when(alertEventMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("k1"));
        when(notifyService.send(any(NotifyMessage.class))).thenReturn(NotifyService.SendResult.FAILED);

        alertEventService.trigger("k1", "标题", "内容", "ERROR", 5L);

        verify(alertEventMapper).bumpSendAttempts("k1");
        verify(alertEventMapper, org.mockito.Mockito.never()).markSent(org.mockito.ArgumentMatchers.eq("k1"), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void noChannelDoesNotAdvanceWindowNorCountAsFailure() {
        when(alertEventMapper.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);
        when(alertEventMapper.bumpIfSendable("k1")).thenReturn(1);
        when(alertEventMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("k1"));
        when(notifyService.send(any(NotifyMessage.class))).thenReturn(NotifyService.SendResult.NO_CHANNEL);

        alertEventService.trigger("k1", "标题", "内容", "WARNING", null);

        verify(alertEventMapper, org.mockito.Mockito.never())
                .markSent(org.mockito.ArgumentMatchers.eq("k1"), org.mockito.ArgumentMatchers.anyInt());
        verify(alertEventMapper, org.mockito.Mockito.never()).bumpSendAttempts("k1");
    }

    @Test
    void suppressedWindowDoesNotSendAgain() {
        when(alertEventMapper.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(0);
        when(alertEventMapper.bumpIfSendable("k1")).thenReturn(0);
        when(alertEventMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("k1"));

        alertEventService.trigger("k1", "标题", "内容", "WARNING", null);

        verify(notifyService, org.mockito.Mockito.never()).send(any(NotifyMessage.class));
        verify(alertEventMapper, org.mockito.Mockito.never())
                .markSent(org.mockito.ArgumentMatchers.eq("k1"), org.mockito.ArgumentMatchers.anyInt());
        verify(alertEventMapper, org.mockito.Mockito.never()).bumpSendAttempts("k1");
    }

    @Test
    void updateStatusValidatesTarget() {
        when(alertEventMapper.selectAlertByKey("k1")).thenReturn(existing("k1"));
        when(alertEventMapper.updateStatusTo("k1", "SILENCED")).thenReturn(1);
        assertThat(alertEventService.updateStatus("k1", "SILENCED")).isTrue();
        assertThat(alertEventService.updateStatus("k1", "UNKNOWN_STATUS")).isFalse();
        assertThat(alertEventService.updateStatus(null, "ACTIVE")).isFalse();
    }

    @Test
    void emptyAccountScopeCountsSystemAlertsThroughMapper() {
        when(alertEventMapper.countActiveByAccountIds(List.of())).thenReturn(2L);
        when(alertEventMapper.countDeliveryFailuresByAccountIds(List.of())).thenReturn(1L);

        assertThat(alertEventService.countActive(List.of())).isEqualTo(2L);
        assertThat(alertEventService.countDeliveryFailures(List.of())).isEqualTo(1L);

        verify(alertEventMapper).countActiveByAccountIds(List.of());
        verify(alertEventMapper).countDeliveryFailuresByAccountIds(List.of());
    }

    @Test
    void listDelegatesWithLimitCap() {
        when(alertEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing("k1")));
        List<AlertEvent> list = alertEventService.list(null, null, 500);
        assertThat(list).hasSize(1);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AlertEvent>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(alertEventMapper).selectList(captor.capture());
    }
}