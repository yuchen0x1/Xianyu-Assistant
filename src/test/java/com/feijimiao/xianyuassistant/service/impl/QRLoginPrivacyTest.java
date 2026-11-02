package com.feijimiao.xianyuassistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.controller.dto.QRLoginSession;
import com.feijimiao.xianyuassistant.controller.dto.QRStatusResponse;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.QRLoginService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QRLoginPrivacyTest {

    private static final String SESSION_ID = "qr-session-id";
    private static final String SENSITIVE_UNB = "unb-secret-value";
    private static final String SENSITIVE_COOKIE = "cookie-secret-value";

    private QRLoginServiceImpl service;
    private AccountService accountService;
    private WebSocketService webSocketService;
    private WebSocketTokenService webSocketTokenService;

    @BeforeEach
    void setUp() {
        service = new QRLoginServiceImpl();
        accountService = mock(AccountService.class);
        webSocketService = mock(WebSocketService.class);
        webSocketTokenService = mock(WebSocketTokenService.class);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "webSocketTokenService", webSocketTokenService);
        when(webSocketService.resumeAutoConnectionAfterCredentialUpdate(anyLong())).thenReturn(true);
    }

    @Test
    void statusResponseAndServiceContractDoNotExposeReusableCredentials() throws Exception {
        QRLoginSession session = session(null);
        session.setStatus("success");
        sessions().put(SESSION_ID, session);

        QRStatusResponse response = service.getSessionStatus(SESSION_ID);
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(QRStatusResponse.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("cookies", "cookie", "unb", "sessionId");
        assertThat(QRLoginService.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("getSessionCookies");
        assertThat(json)
                .doesNotContain(SENSITIVE_COOKIE, SENSITIVE_UNB, "qr-secret", "verify-secret")
                .contains("\"status\":\"confirmed\"");
    }

    @Test
    void newAccountCredentialsArePersistedOnlyByTheBackendAndThenCleared() {
        QRLoginSession session = session(null);
        when(accountService.saveAccountAndCookie(
                anyString(),
                org.mockito.ArgumentMatchers.eq(SENSITIVE_UNB),
                org.mockito.ArgumentMatchers.contains(SENSITIVE_COOKIE),
                org.mockito.ArgumentMatchers.eq("h5-secret")))
                .thenReturn(12L);

        persist(session);

        assertThat(session.getStatus()).isEqualTo("success");
        assertCredentialsCleared(session);
        verify(accountService).saveAccountAndCookie(
                anyString(),
                org.mockito.ArgumentMatchers.eq(SENSITIVE_UNB),
                org.mockito.ArgumentMatchers.contains(SENSITIVE_COOKIE),
                org.mockito.ArgumentMatchers.eq("h5-secret"));
        verify(webSocketTokenService).clearCaptchaWait(12L);
        verify(webSocketService).resumeAutoConnectionAfterCredentialUpdate(12L);
    }

    @Test
    void matchingAccountCanBeUpdatedWithoutReturningCredentialsToTheBrowser() {
        QRLoginSession session = session(7L);
        when(accountService.getXianyuUserId(7L)).thenReturn(SENSITIVE_UNB);
        when(accountService.updateAccountCookie(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(SENSITIVE_UNB),
                org.mockito.ArgumentMatchers.contains(SENSITIVE_COOKIE)))
                .thenReturn(true);

        persist(session);

        assertThat(session.getStatus()).isEqualTo("success");
        assertCredentialsCleared(session);
        verify(accountService).updateAccountCookie(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(SENSITIVE_UNB),
                org.mockito.ArgumentMatchers.contains(SENSITIVE_COOKIE));
        verify(accountService, never()).saveAccountAndCookie(
                anyString(), anyString(), anyString(), anyString());
        verify(webSocketTokenService).clearCaptchaWait(7L);
        verify(webSocketService).resumeAutoConnectionAfterCredentialUpdate(7L);
    }

    @Test
    void accountWithoutAnExistingUnbCanStillBeInitializedByQrUpdate() {
        QRLoginSession session = session(7L);
        when(accountService.getXianyuUserId(7L)).thenReturn(null);
        when(accountService.updateAccountCookie(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(SENSITIVE_UNB),
                org.mockito.ArgumentMatchers.contains(SENSITIVE_COOKIE)))
                .thenReturn(true);

        persist(session);

        assertThat(session.getStatus()).isEqualTo("success");
        assertCredentialsCleared(session);
        verify(accountService).updateAccountCookie(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(SENSITIVE_UNB),
                org.mockito.ArgumentMatchers.contains(SENSITIVE_COOKIE));
        verify(webSocketTokenService).clearCaptchaWait(7L);
        verify(webSocketService).resumeAutoConnectionAfterCredentialUpdate(7L);
    }

    @Test
    void differentScannedAccountCannotOverwriteTheTargetAccount() {
        QRLoginSession session = session(7L);
        when(accountService.getXianyuUserId(7L)).thenReturn("different-unb");

        persist(session);

        assertThat(session.getStatus()).isEqualTo("account_mismatch");
        assertCredentialsCleared(session);
        verify(accountService, never()).updateAccountCookie(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
        verify(accountService, never()).saveAccountAndCookie(
                anyString(), anyString(), anyString(), anyString());
        verify(webSocketService, never()).resumeAutoConnectionAfterCredentialUpdate(anyLong());
    }

    private QRLoginSession session(Long targetAccountId) {
        QRLoginSession session = new QRLoginSession(SESSION_ID, targetAccountId);
        session.setStatus("processing");
        session.setUnb(SENSITIVE_UNB);
        session.getCookies().put("unb", SENSITIVE_UNB);
        session.getCookies().put("cookie-key", SENSITIVE_COOKIE);
        session.getCookies().put("_m_h5_tk", "h5-secret");
        session.getParams().put("login-token", "login-secret");
        session.setQrContent("qr-secret");
        session.setQrCodeUrl("data:image/png;base64,qr-secret");
        session.setVerificationUrl("https://example.invalid/verify-secret");
        return session;
    }

    private void persist(QRLoginSession session) {
        ReflectionTestUtils.invokeMethod(service, "saveCookieToDatabase", session);
    }

    private void assertCredentialsCleared(QRLoginSession session) {
        assertThat(session.getCookies()).isEmpty();
        assertThat(session.getParams()).isEmpty();
        assertThat(session.getUnb()).isNull();
        assertThat(session.getQrContent()).isNull();
        assertThat(session.getQrCodeUrl()).isNull();
        assertThat(session.getVerificationUrl()).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, QRLoginSession> sessions() {
        return (Map<String, QRLoginSession>) ReflectionTestUtils.getField(service, "sessions");
    }
}
