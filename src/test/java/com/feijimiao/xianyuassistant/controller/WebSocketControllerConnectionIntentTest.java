package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.controller.dto.UpdateCookieReqDTO;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.service.AutomationHealthService;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.service.ManualCaptchaBrowserService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketControllerConnectionIntentTest {

    @Mock private WebSocketService webSocketService;
    @Mock private WebSocketTokenService tokenService;
    @Mock private XianyuAccountMapper accountMapper;
    @Mock private ApplicationContext applicationContext;
    @Mock private AccountService accountService;
    @Mock private XianyuCookieMapper cookieMapper;
    @Mock private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock private OperationLogService operationLogService;
    @Mock private ManualCaptchaBrowserService manualCaptchaBrowserService;

    private WebSocketController controller;

    @BeforeEach
    void setUp() {
        controller = new WebSocketController();
        ReflectionTestUtils.setField(controller, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(controller, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(controller, "automationHealthService", new AutomationHealthService());
        ReflectionTestUtils.setField(controller, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(controller, "manualCaptchaBrowserService", manualCaptchaBrowserService);
    }

    @Test
    void startingAConnectionPersistsAutoConnectBeforeOpeningTheSocket() {
        WebSocketController.StartWebSocketReqDTO request =
                new WebSocketController.StartWebSocketReqDTO();
        request.setXianyuAccountId(7L);
        when(webSocketService.setAutoConnectEnabled(7L, true)).thenReturn(true);
        when(webSocketService.startWebSocket(7L)).thenReturn(true);

        var response = controller.startWebSocket(request);

        assertEquals(200, response.getCode());
        InOrder order = inOrder(webSocketService);
        order.verify(webSocketService).setAutoConnectEnabled(7L, true);
        order.verify(webSocketService).startWebSocket(7L);
    }

    @Test
    void stoppingAConnectionPersistsTheManualPause() {
        WebSocketController.StopWebSocketReqDTO request =
                new WebSocketController.StopWebSocketReqDTO();
        request.setXianyuAccountId(7L);
        when(webSocketService.setAutoConnectEnabled(7L, false)).thenReturn(true);

        var response = controller.stopWebSocket(request);

        assertEquals(200, response.getCode());
        verify(webSocketService).setAutoConnectEnabled(7L, false);
        verifyNoMoreInteractions(webSocketService);
    }

    @Test
    void clearingCaptchaWaitFailsWhenManualRecoveryCannotVerifyTheAccount() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        when(applicationContext.getBean(XianyuAccountMapper.class)).thenReturn(accountMapper);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(webSocketService.recoverAfterManualCaptcha(7L)).thenReturn(false);
        WebSocketController.ClearCaptchaWaitReqDTO request =
                new WebSocketController.ClearCaptchaWaitReqDTO();
        request.setXianyuAccountId(7L);

        var response = controller.clearCaptchaWait(request);

        assertEquals(1001, response.getCode());
        verify(webSocketService).recoverAfterManualCaptcha(7L);
        verifyNoMoreInteractions(webSocketService);
        verifyNoInteractions(tokenService);
    }

    @Test
    void clearingCaptchaWaitRunsTruthCheckedManualRecoveryWithoutClearingCaptchaFirst() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        when(applicationContext.getBean(XianyuAccountMapper.class)).thenReturn(accountMapper);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(webSocketService.recoverAfterManualCaptcha(7L)).thenReturn(true);
        WebSocketController.ClearCaptchaWaitReqDTO request =
                new WebSocketController.ClearCaptchaWaitReqDTO();
        request.setXianyuAccountId(7L);

        var response = controller.clearCaptchaWait(request);

        assertEquals(200, response.getCode());
        verify(webSocketService).recoverAfterManualCaptcha(7L);
        verifyNoInteractions(tokenService);
    }

    @Test
    void openingCaptchaBrowserUsesTheBackendCookieSession() {
        WebSocketController.ClearCaptchaWaitReqDTO request =
                new WebSocketController.ClearCaptchaWaitReqDTO();
        request.setXianyuAccountId(7L);
        when(manualCaptchaBrowserService.openVerificationWindow(7L))
                .thenReturn(new ManualCaptchaBrowserService.LaunchResult(true, "opened"));

        var response = controller.openCaptchaBrowser(request);

        assertEquals(200, response.getCode());
        assertTrue(response.getData().accepted());
        assertEquals("opened", response.getData().message());
        verify(manualCaptchaBrowserService).openVerificationWindow(7L);
    }

    @Test
    void connectedStatusSuppressesStaleCaptchaAndReconnectFlags() {
        when(webSocketService.isConnected(7L)).thenReturn(true);
        when(applicationContext.getBean(WebSocketTokenService.class)).thenReturn(tokenService);
        when(applicationContext.getBean(AccountService.class)).thenReturn(accountService);
        when(applicationContext.getBean(XianyuCookieMapper.class)).thenReturn(cookieMapper);
        when(applicationContext.getBean(XianyuGoodsConfigMapper.class)).thenReturn(goodsConfigMapper);
        WebSocketController.GetWebSocketStatusReqDTO request =
                new WebSocketController.GetWebSocketStatusReqDTO();
        request.setXianyuAccountId(7L);

        var response = controller.getWebSocketStatus(request);

        assertEquals(200, response.getCode());
        assertEquals(true, response.getData().getConnected());
        assertEquals(false, response.getData().getCaptchaPending());
        assertEquals(false, response.getData().getReconnecting());
        assertEquals(0, response.getData().getReconnectAttempt());
        assertEquals(null, response.getData().getNextReconnectAt());
        assertEquals("已连接", response.getData().getStatus());
        verify(tokenService, never()).isCaptchaPending(7L);
        verify(webSocketService, never()).isReconnecting(7L);
        verify(webSocketService, never()).getReconnectAttempt(7L);
        verify(webSocketService, never()).getNextReconnectAt(7L);
    }

    @Test
    void updatingCookieClearsCaptchaAndAtomicallyResumesTheConnection() {
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setUnb("buyer-7");
        when(applicationContext.getBean(XianyuAccountMapper.class)).thenReturn(accountMapper);
        when(applicationContext.getBean(AccountService.class)).thenReturn(accountService);
        when(applicationContext.getBean(WebSocketTokenService.class)).thenReturn(tokenService);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountService.updateAccountCookie(7L, "buyer-7", "unb=buyer-7; cookie=value"))
                .thenReturn(true);
        when(webSocketService.resumeAutoConnection(7L)).thenReturn(true);
        UpdateCookieReqDTO request = new UpdateCookieReqDTO();
        request.setXianyuAccountId(7L);
        request.setCookieText("unb=buyer-7; cookie=value");

        var response = controller.updateCookie(request);

        assertEquals(200, response.getCode());
        InOrder order = inOrder(accountService, tokenService, webSocketService);
        order.verify(accountService).updateAccountCookie(
                7L, "buyer-7", "unb=buyer-7; cookie=value");
        order.verify(tokenService).clearCaptchaWait(7L);
        order.verify(webSocketService).resumeAutoConnection(7L);
        verify(webSocketService, never()).stopWebSocket(7L);
        verify(webSocketService, never()).ensureConnected(7L);
    }
}
