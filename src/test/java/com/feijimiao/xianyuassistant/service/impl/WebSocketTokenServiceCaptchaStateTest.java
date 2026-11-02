package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTokenServiceCaptchaStateTest {

    @Test
    void captchaNotificationContractReportsTheAsynchronousDeliveryOutcome() throws Exception {
        assertThat(EmailNotifyService.class.getMethod(
                "sendCaptchaRequiredEmail", Long.class, String.class, String.class).getReturnType())
                .isEqualTo(CompletableFuture.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void captchaWarningRemainsPendingUntilTheUserExplicitlyClearsIt() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        Map<Long, String> pendingAccounts =
                (Map<Long, String>) ReflectionTestUtils.getField(service, "pendingCaptchaAccounts");
        Map<Long, Long> timestamps =
                (Map<Long, Long>) ReflectionTestUtils.getField(service, "captchaTimestamps");

        assertThat(pendingAccounts).isNotNull();
        assertThat(timestamps).isNotNull();
        pendingAccounts.put(3L, "https://example.invalid/expired-captcha");
        timestamps.put(3L, System.currentTimeMillis() - (10 * 60 * 1000L));

        assertThat(service.isCaptchaPending(3L)).isTrue();
        assertThat(pendingAccounts).containsKey(3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualVerificationProbeBypassesTheShortTermCaptchaHold() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        Map<Long, String> pendingAccounts =
                (Map<Long, String>) ReflectionTestUtils.getField(service, "pendingCaptchaAccounts");
        Map<Long, Long> timestamps =
                (Map<Long, Long>) ReflectionTestUtils.getField(service, "captchaTimestamps");

        assertThat(pendingAccounts).isNotNull();
        assertThat(timestamps).isNotNull();
        pendingAccounts.put(3L, "https://example.invalid/captcha");
        timestamps.put(3L, System.currentTimeMillis());

        assertThrows(CaptchaRequiredException.class, () -> service.probeAccessToken(3L));

        assertNull(service.probeAccessTokenAfterManualVerification(3L));
        assertThat(pendingAccounts).doesNotContainKey(3L);
        assertThat(timestamps).doesNotContainKey(3L);
    }

    @Test
    void persistedCaptchaAccountStatusSurvivesAnApplicationRestart() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(3L);
        account.setStatus(-2);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        when(accountMapper.selectById(3L)).thenReturn(account);

        assertThat(service.isCaptchaPending(3L)).isTrue();
    }

    @Test
    void captchaNotificationIsDeduplicatedAndManualClearResetsIt() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setAccountNote("main shop");
        account.setStatus(1);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(emailNotifyService.sendCaptchaRequiredEmail(
                accountId, "main shop", "FAIL_SYS_USER_VALIDATE"))
                .thenReturn(CompletableFuture.completedFuture(true));

        ReflectionTestUtils.invokeMethod(service, "updateAccountStatusToCaptchaRequired", accountId);
        ReflectionTestUtils.invokeMethod(service, "updateAccountStatusToCaptchaRequired", accountId);

        verify(emailNotifyService, times(1))
                .sendCaptchaRequiredEmail(accountId, "main shop", "FAIL_SYS_USER_VALIDATE");

        service.clearCaptchaWait(accountId);
        ReflectionTestUtils.invokeMethod(service, "updateAccountStatusToCaptchaRequired", accountId);

        verify(emailNotifyService, times(2))
                .sendCaptchaRequiredEmail(accountId, "main shop", "FAIL_SYS_USER_VALIDATE");
    }

    @Test
    void failedCaptchaNotificationEnqueueCanBeRetried() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setAccountNote("main shop");
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(emailNotifyService.sendCaptchaRequiredEmail(
                accountId, "main shop", "FAIL_SYS_USER_VALIDATE"))
                .thenReturn(
                        CompletableFuture.completedFuture(false),
                        CompletableFuture.completedFuture(true));

        ReflectionTestUtils.invokeMethod(service, "updateAccountStatusToCaptchaRequired", accountId);
        ReflectionTestUtils.invokeMethod(service, "updateAccountStatusToCaptchaRequired", accountId);

        verify(emailNotifyService, times(2))
                .sendCaptchaRequiredEmail(accountId, "main shop", "FAIL_SYS_USER_VALIDATE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void captchaWithoutUrlStillEntersPendingStateAndAlerts() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setAccountNote("main shop");
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(emailNotifyService.sendCaptchaRequiredEmail(
                accountId, "main shop", "FAIL_SYS_USER_VALIDATE"))
                .thenReturn(CompletableFuture.completedFuture(true));

        CaptchaRequiredException exception = assertThrows(
                CaptchaRequiredException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "handleCaptchaRequired", accountId, Map.of()));

        assertThat(exception.getCaptchaUrl()).isEmpty();
        Map<Long, String> pendingAccounts =
                (Map<Long, String>) ReflectionTestUtils.getField(service, "pendingCaptchaAccounts");
        assertThat(pendingAccounts).containsEntry(accountId, "");
        verify(emailNotifyService).sendCaptchaRequiredEmail(
                accountId, "main shop", "FAIL_SYS_USER_VALIDATE");
    }

    @Test
    void rgvRiskEntersTheSamePersistentCaptchaIncident() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setAccountNote("main shop");
        account.setStatus(1);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(emailNotifyService.sendCaptchaRequiredEmail(
                accountId, "main shop", "FAIL_SYS_USER_VALIDATE"))
                .thenReturn(CompletableFuture.completedFuture(true));

        assertThrows(CaptchaRequiredException.class, () -> ReflectionTestUtils.invokeMethod(
                service,
                "handleTokenFailure",
                accountId,
                0,
                "FAIL_SYS_RGV587_ERROR",
                "risk",
                true,
                true));

        verify(accountMapper).updateById(argThat((XianyuAccount updated) -> updated.getStatus() == -2));
        verify(emailNotifyService).sendCaptchaRequiredEmail(
                accountId, "main shop", "FAIL_SYS_USER_VALIDATE");
    }

    @Test
    void serverBusyResponseDoesNotMasqueradeAsCaptcha() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);

        String token = ReflectionTestUtils.invokeMethod(
                service,
                "handleTokenFailure",
                accountId,
                0,
                "被挤爆啦",
                "server busy",
                true,
                true);

        assertNull(token);
        verify(accountMapper, times(0)).updateById(argThat((XianyuAccount updated) -> true));
        verify(emailNotifyService, times(0)).sendCaptchaRequiredEmail(
                accountId, null, "FAIL_SYS_USER_VALIDATE");
    }
}
