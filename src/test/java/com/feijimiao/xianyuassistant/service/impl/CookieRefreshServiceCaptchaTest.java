package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.utils.SessionCookieJar;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CookieRefreshServiceCaptchaTest {

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                XianyuCookie.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void userValidationRiskStopsRefreshAndMarksAccountAsCaptchaPending() throws Exception {
        Long accountId = 3L;
        CookieRefreshServiceImpl service = new CookieRefreshServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        ResponseBody responseBody = mock(ResponseBody.class);

        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieText("unb=123; cookie2=abc; cna=device; XSRF-TOKEN=csrf");
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setAccountNote("main shop");
        account.setStatus(1);

        ReflectionTestUtils.setField(service, "cookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        when(cookieMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cookie);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);
        when(response.code()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(responseBody.string()).thenReturn(
                "{\"ret\":[\"FAIL_SYS_USER_VALIDATE::SESSION_EXPIRED\"]}");

        try (MockedConstruction<SessionCookieJar> ignored = mockConstruction(
                SessionCookieJar.class,
                (mockedJar, context) -> when(mockedJar.createHttpClient()).thenReturn(httpClient))) {
            assertThrows(CaptchaRequiredException.class, () -> service.refreshCookie(accountId));
        }

        verify(call, times(1)).execute();
        verify(cookieMapper, never()).update(isNull(), any());
        verify(accountMapper).updateById(argThat((XianyuAccount updated) -> updated.getStatus() == -2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverBusyDoesNotInvalidateCredentialsOrMasqueradeAsCaptcha() throws Exception {
        Long accountId = 3L;
        CookieRefreshServiceImpl service = new CookieRefreshServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        ResponseBody responseBody = mock(ResponseBody.class);

        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieText("unb=123; cookie2=abc; cna=device; XSRF-TOKEN=csrf");

        ReflectionTestUtils.setField(service, "cookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        when(cookieMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cookie);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);
        when(response.code()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(responseBody.string()).thenReturn("{\"ret\":[\"FAIL_SYS_TRAFFIC_LIMIT::被挤爆啦\"]}");

        try (MockedConstruction<SessionCookieJar> ignored = mockConstruction(
                SessionCookieJar.class,
                (mockedJar, context) -> when(mockedJar.createHttpClient()).thenReturn(httpClient))) {
            assertFalse(service.refreshCookie(accountId));
        }

        verify(call, times(1)).execute();
        verify(cookieMapper, never()).update(isNull(), any());
        verify(accountMapper, never()).updateById(any(XianyuAccount.class));
        verify(emailNotifyService, never()).sendCaptchaRequiredEmail(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rgvRiskPreservesCookieWhileMarkingCaptchaPending() throws Exception {
        Long accountId = 3L;
        CookieRefreshServiceImpl service = new CookieRefreshServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        EmailNotifyService emailNotifyService = mock(EmailNotifyService.class);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        ResponseBody responseBody = mock(ResponseBody.class);

        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieText("unb=123; cookie2=abc; cna=device; XSRF-TOKEN=csrf");
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setStatus(1);

        ReflectionTestUtils.setField(service, "cookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        when(cookieMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cookie);
        when(accountMapper.selectById(accountId)).thenReturn(account);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(true);
        when(response.code()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(responseBody.string()).thenReturn("{\"ret\":[\"FAIL_SYS_RGV587_ERROR\"]}");

        try (MockedConstruction<SessionCookieJar> ignored = mockConstruction(
                SessionCookieJar.class,
                (mockedJar, context) -> when(mockedJar.createHttpClient()).thenReturn(httpClient))) {
            assertThrows(CaptchaRequiredException.class, () -> service.refreshCookie(accountId));
        }

        verify(cookieMapper, never()).update(isNull(), any());
        verify(accountMapper).updateById(argThat((XianyuAccount updated) -> updated.getStatus() == -2));
    }
}
