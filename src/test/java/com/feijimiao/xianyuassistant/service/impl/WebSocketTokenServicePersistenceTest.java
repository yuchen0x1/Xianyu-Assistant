package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.exception.CookieExpiredException;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTokenServicePersistenceTest {

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                XianyuCookie.class);
    }

    @Test
    void forcedRefreshFailureDoesNotClearThePersistedTokenFirst() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        when(cookieMapper.selectOne(any())).thenReturn(null);

        assertNull(service.refreshToken(3L));

        verify(cookieMapper, never()).update(isNull(), any());
    }

    @Test
    void savingTheSameLiveTokenDoesNotExtendItsOriginalExpiry() {
        Long accountId = 3L;
        String token = "existing-token";
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setWebsocketToken(token);
        cookie.setTokenExpireTime(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(10));
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        when(cookieMapper.selectOne(any())).thenReturn(cookie);

        service.saveToken(accountId, token);

        verify(cookieMapper, never()).update(isNull(), any());
    }

    @Test
    void confirmedFreshTokenRestoresAPreviouslyInvalidatedCookieRecord() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieStatus(2);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        when(cookieMapper.selectOne(any())).thenReturn(cookie);
        when(cookieMapper.updateById(any(XianyuCookie.class))).thenReturn(1);

        ReflectionTestUtils.invokeMethod(
                service, "saveTokenToDatabase", accountId, "fresh-token", true);

        var entityCaptor = forClass(XianyuCookie.class);
        verify(cookieMapper).updateById(entityCaptor.capture());
        XianyuCookie updated = entityCaptor.getValue();
        assertThat(updated.getCookieStatus()).isEqualTo(1);
        assertThat(updated.getWebsocketToken()).isEqualTo("fresh-token");
    }

    @Test
    void sessionRecoveryBudgetStopsRetryingAfterTheLimit() {
        Long accountId = 3L;
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        CookieRefreshService cookieRefreshService = mock(CookieRefreshService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "cookieRefreshService", cookieRefreshService);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        when(cookieMapper.selectOne(any())).thenReturn(null);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                CookieExpiredException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "refreshTokenAfterSessionExpired", accountId, 2, true, false)))
                .hasMessageContaining("Cookie已过期");
        verify(cookieRefreshService, never()).refreshCookieWithBrowser(accountId);
        verify(cookieRefreshService, never()).refreshCookie(accountId);
    }
}
