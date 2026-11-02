package com.feijimiao.xianyuassistant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketConnectionSupervisorTest {

    @Mock private XianyuAccountMapper accountMapper;
    @Mock private XianyuCookieMapper cookieMapper;
    @Mock private WebSocketService webSocketService;

    private WebSocketConnectionSupervisor supervisor;

    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, XianyuAccount.class);
        TableInfoHelper.initTableInfo(assistant, XianyuCookie.class);
    }

    @BeforeEach
    void setUp() {
        supervisor = new WebSocketConnectionSupervisor(accountMapper, cookieMapper, webSocketService);
    }

    @Test
    void startupRecoversNormalAndCaptchaRecoveryAccounts() {
        XianyuAccount disabled = account(4L, 1);
        disabled.setWebsocketEnabled(0);
        when(accountMapper.selectList(any())).thenReturn(List.of(
                account(1L, 1), account(2L, -2), account(3L, -1), disabled));
        when(cookieMapper.selectOne(any())).thenReturn(validCookie(1L), validCookie(2L));

        supervisor.recoverConnectionsOnStartup();

        verify(webSocketService).ensureConnected(1L);
        verify(webSocketService).ensureConnected(2L);
        verify(webSocketService, never()).ensureConnected(3L);
        verify(webSocketService, never()).ensureConnected(4L);
    }

    @Test
    void skipsAccountsWithoutAValidNonBlankCookie() {
        when(accountMapper.selectList(any())).thenReturn(List.of(
                account(1L, 1), account(2L, 1), account(3L, 1)));
        XianyuCookie expired = validCookie(1L);
        expired.setCookieStatus(2);
        XianyuCookie blank = validCookie(2L);
        blank.setCookieText("  ");
        when(cookieMapper.selectOne(any())).thenReturn(expired, blank, null);

        supervisor.maintainConnections();

        verify(webSocketService, never()).ensureConnected(any());
    }

    @Test
    void oneAccountFailureDoesNotBlockTheRemainingAccounts() {
        when(accountMapper.selectList(any())).thenReturn(List.of(account(1L, 1), account(2L, 1)));
        when(cookieMapper.selectOne(any())).thenReturn(validCookie(1L), validCookie(2L));
        doThrow(new IllegalStateException("busy")).when(webSocketService).ensureConnected(1L);

        supervisor.maintainConnections();

        verify(webSocketService).ensureConnected(1L);
        verify(webSocketService).ensureConnected(2L);
    }

    private XianyuAccount account(Long id, int status) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setStatus(status);
        return account;
    }

    private XianyuCookie validCookie(Long accountId) {
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieStatus(1);
        cookie.setCookieText("unb=account-" + accountId);
        return cookie;
    }
}
