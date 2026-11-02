package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.controller.dto.QRLoginSession;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QRLoginTokenExchangeTest {

    @Test
    void exchangesConfirmedLoginTokenAndRefreshesAuthenticatedCookies() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call loginCall = mock(Call.class);
        Call refreshCall = mock(Call.class);
        Response loginResponse = response(
                List.of("unb=123456; Path=/", "cookie2=session-cookie; Path=/"),
                "{\"content\":{\"success\":true}}");
        Response refreshResponse = response(
                List.of("_m_h5_tk=fresh-token_9999999999999; Path=/"),
                "{\"ret\":[\"SUCCESS::调用成功\"]}");

        when(client.newCall(any(Request.class))).thenReturn(loginCall, refreshCall);
        when(loginCall.execute()).thenReturn(loginResponse);
        when(refreshCall.execute()).thenReturn(refreshResponse);

        QRLoginServiceImpl service = new QRLoginServiceImpl(client);
        QRLoginSession session = new QRLoginSession("session-id");
        session.getCookies().put("cna", "device-id");
        session.getCookies().put("_m_h5_tk", "pre-login-token_1");

        Boolean completed = ReflectionTestUtils.invokeMethod(
                service, "completeLoginToken", session, "official-login-token");
        ReflectionTestUtils.invokeMethod(service, "refreshPostLoginCookies", session);

        assertThat(completed).isTrue();
        assertThat(session.getUnb()).isEqualTo("123456");
        assertThat(session.getCookies())
                .containsEntry("cookie2", "session-cookie")
                .containsEntry("_m_h5_tk", "fresh-token_9999999999999");

        ArgumentCaptor<Request> requests = ArgumentCaptor.forClass(Request.class);
        verify(client, times(2)).newCall(requests.capture());
        assertThat(requests.getAllValues().get(0).url().toString())
                .contains("/login_token/login.do", "token=official-login-token");
        assertThat(requests.getAllValues().get(0).header("Cookie"))
                .contains("cna=device-id");
        Buffer loginBody = new Buffer();
        requests.getAllValues().get(0).body().writeTo(loginBody);
        assertThat(loginBody.readUtf8()).isEqualTo("deviceId=device-id");
        assertThat(requests.getAllValues().get(1).url().toString())
                .contains("mtop.idle.web.user.page.nav");
        assertThat(requests.getAllValues().get(1).header("Cookie"))
                .contains("unb=123456", "cookie2=session-cookie");
    }

    @Test
    void preservesOfficialVerificationRequirementFromLoginTokenExchange() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = response(List.of(),
                "{\"content\":{\"data\":{\"iframeRedirect\":true,"
                        + "\"iframeRedirectUrl\":\"https://passport.goofish.com/verify\"}}}");
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);

        QRLoginServiceImpl service = new QRLoginServiceImpl(client);
        QRLoginSession session = new QRLoginSession("session-id");

        Boolean completed = ReflectionTestUtils.invokeMethod(
                service, "completeLoginToken", session, "official-login-token");

        assertThat(completed).isFalse();
        assertThat(session.getStatus()).isEqualTo("verification_required");
        assertThat(session.getVerificationUrl())
                .isEqualTo("https://passport.goofish.com/verify");
    }

    private Response response(List<String> setCookies, String body) throws Exception {
        Response response = mock(Response.class);
        ResponseBody responseBody = mock(ResponseBody.class);
        when(response.headers("Set-Cookie")).thenReturn(setCookies);
        when(response.body()).thenReturn(responseBody);
        when(responseBody.string()).thenReturn(body);
        when(response.isSuccessful()).thenReturn(true);
        when(response.code()).thenReturn(200);
        return response;
    }
}
