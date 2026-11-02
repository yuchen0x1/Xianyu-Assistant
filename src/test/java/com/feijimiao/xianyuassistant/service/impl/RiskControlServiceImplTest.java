package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.risk.dto.RiskAutomationStatus;
import com.feijimiao.xianyuassistant.risk.entity.AccountAutomationPause;
import com.feijimiao.xianyuassistant.risk.entity.AccountRiskEvent;
import com.feijimiao.xianyuassistant.risk.mapper.AccountAutomationPauseMapper;
import com.feijimiao.xianyuassistant.risk.mapper.AccountRiskEventMapper;
import com.feijimiao.xianyuassistant.utils.XianyuAuthFailureGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskControlServiceImplTest {

    @Mock
    private AccountRiskEventMapper riskEventMapper;
    @Mock
    private AccountAutomationPauseMapper pauseMapper;
    @Mock
    private XianyuAuthFailureGuard authFailureGuard;

    private RiskControlServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RiskControlServiceImpl();
        ReflectionTestUtils.setField(service, "riskEventMapper", riskEventMapper);
        ReflectionTestUtils.setField(service, "pauseMapper", pauseMapper);
        ReflectionTestUtils.setField(service, "authFailureGuard", authFailureGuard);
    }

    @Test
    void automationIsBlockedWhileManuallyPaused() {
        AccountAutomationPause pause = new AccountAutomationPause();
        pause.setAccountId(1L);
        pause.setPaused(1);
        pause.setPauseReason("人工核对");
        when(pauseMapper.selectById(1L)).thenReturn(pause);

        assertThat(service.isAutomationAllowed(1L)).isFalse();
        assertThat(service.getAutomationStatus(1L).isPaused()).isTrue();
        assertThat(service.getAutomationStatus(1L).getPauseReason()).isEqualTo("人工核对");
    }

    @Test
    void automationIsAllowedWhenNoPauseRecord() {
        when(pauseMapper.selectById(1L)).thenReturn(null);
        when(authFailureGuard.isCoolingDown(1L)).thenReturn(false);

        assertThat(service.isAutomationAllowed(1L)).isTrue();
    }

    @Test
    void automationIsBlockedWhileGuardCoolingDown() {
        when(pauseMapper.selectById(1L)).thenReturn(null);
        when(authFailureGuard.isCoolingDown(1L)).thenReturn(true);

        assertThat(service.isAutomationAllowed(1L)).isFalse();
    }

    @Test
    void pausePersistsAndRecordsEvent() {
        when(pauseMapper.selectById(1L)).thenReturn(null);

        service.pauseAutomation(1L, "账号异常");

        ArgumentCaptor<AccountAutomationPause> pauseCaptor = ArgumentCaptor.forClass(AccountAutomationPause.class);
        verify(pauseMapper).insert(pauseCaptor.capture());
        assertThat(pauseCaptor.getValue().getPaused()).isEqualTo(1);
        assertThat(pauseCaptor.getValue().getPauseReason()).isEqualTo("账号异常");
        ArgumentCaptor<AccountRiskEvent> eventCaptor = ArgumentCaptor.forClass(AccountRiskEvent.class);
        verify(riskEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("MANUAL_PAUSE");
    }

    @Test
    void resumeClearsPauseAndCooldownAndRecordsEvent() {
        AccountAutomationPause pause = new AccountAutomationPause();
        pause.setAccountId(1L);
        pause.setPaused(1);
        pause.setPauseReason("旧原因");
        when(pauseMapper.selectById(1L)).thenReturn(pause);

        service.resumeAutomation(1L);

        assertThat(pause.getPaused()).isZero();
        assertThat(pause.getPauseReason()).isNull();
        verify(pauseMapper).updateById(pause);
        verify(authFailureGuard).clear(1L);
        ArgumentCaptor<AccountRiskEvent> eventCaptor = ArgumentCaptor.forClass(AccountRiskEvent.class);
        verify(riskEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("RESUME");
    }

    @Test
    void recordRiskEventSanitizesReasonAndSwallowsMapperFailure() {
        when(riskEventMapper.insert(any(AccountRiskEvent.class)))
                .thenThrow(new RuntimeException("db down"));

        // 不应抛出异常，主流程继续
        service.recordRiskEvent(1L, "RISK_CONTROL", "RGV587_ERROR\n\t多行原因");

        ArgumentCaptor<AccountRiskEvent> captor = ArgumentCaptor.forClass(AccountRiskEvent.class);
        verify(riskEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RISK_CONTROL");
        assertThat(captor.getValue().getReason()).doesNotContain("\n");
    }

    @Test
    void getAutomationStatusExposesCooldownSeconds() {
        when(pauseMapper.selectById(1L)).thenReturn(null);
        when(authFailureGuard.remainingCooldownMillis(1L)).thenReturn(65_000L);

        RiskAutomationStatus status = service.getAutomationStatus(1L);

        assertThat(status.isPaused()).isFalse();
        assertThat(status.getCooldownRemainingSeconds()).isEqualTo(65L);
    }

    @Test
    void nullAccountIsTreatedAsAllowed() {
        assertThat(service.isAutomationAllowed(null)).isTrue();
        verify(pauseMapper, never()).selectById(any());
    }
}
