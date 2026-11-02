package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.exception.CaptchaRequiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.utils.SessionCookieJar;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenRefreshServiceCaptchaTest {

    @Test
    @SuppressWarnings("unchecked")
    void captchaFromHasLoginEscapesMh5tkRefreshWithoutRecursion() throws Exception {
        Long accountId = 3L;
        TokenRefreshServiceImpl service = new TokenRefreshServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        CookieRefreshService cookieRefreshService = mock(CookieRefreshService.class);
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieText("unb=123");

        ReflectionTestUtils.setField(service, "cookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "cookieRefreshService", cookieRefreshService);
        when(cookieMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cookie);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(cookieRefreshService.checkLoginStatus(accountId))
                .thenThrow(new CaptchaRequiredException(""))
                .thenThrow(new AssertionError("hasLogin must not be retried after CAPTCHA"));

        try (MockedConstruction<SessionCookieJar> ignored = mockConstruction(
                SessionCookieJar.class,
                (mockedJar, context) -> {
                    when(mockedJar.createHttpClient()).thenReturn(httpClient);
                    when(mockedJar.getCookieString()).thenReturn("unb=123");
                })) {
            assertThrows(CaptchaRequiredException.class, () -> ReflectionTestUtils.invokeMethod(
                    service, "refreshMh5tkTokenWithRetry", accountId, 2, false));
        }

        verify(cookieRefreshService, times(1)).checkLoginStatus(accountId);
    }
}
