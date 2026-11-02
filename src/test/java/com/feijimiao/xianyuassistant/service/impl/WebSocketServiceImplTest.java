package com.feijimiao.xianyuassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.service.WebSocketTokenService;
import com.feijimiao.xianyuassistant.config.WebSocketConfig;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.utils.AccountDisplayNameUtils;
import com.feijimiao.xianyuassistant.websocket.WebSocketInitializer;
import com.feijimiao.xianyuassistant.websocket.WebSocketMessageHandler;
import com.feijimiao.xianyuassistant.websocket.XianyuWebSocketClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketServiceImplTest {

    @Mock private AccountService accountService;
    @Mock private ScheduledFuture<?> heartbeatTask;
    @Mock private ScheduledFuture<?> tokenRefreshTask;
    @Mock private ScheduledFuture<?> tokenRetryTask;
    @Mock private Future<?> reconnectTask;
    @Mock private XianyuWebSocketClient client;
    @Mock private WebSocketMessageHandler messageHandler;
    @Mock private WebSocketTokenService tokenService;
    @Mock private WebSocketInitializer initializer;
    @Mock private WebSocketConfig config;
    @Mock private CookieRefreshService cookieRefreshService;
    @Mock private AccountDisplayNameUtils displayNameUtils;
    @Mock private OperationLogService operationLogService;
    @Mock private EmailNotifyService emailNotifyService;
    @Mock private XianyuAccountMapper xianyuAccountMapper;
    @Mock private XianyuCookieMapper xianyuCookieMapper;

    private TestableWebSocketServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TestableWebSocketServiceImpl(client);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "messageHandler", messageHandler);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
        ReflectionTestUtils.setField(service, "initializer", initializer);
        ReflectionTestUtils.setField(service, "config", config);
        ReflectionTestUtils.setField(service, "cookieRefreshService", cookieRefreshService);
        ReflectionTestUtils.setField(service, "displayNameUtils", displayNameUtils);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", xianyuAccountMapper);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", xianyuCookieMapper);
        lenient().when(client.awaitRegistration(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
    }

    @Test
    void permanentStopCancelsEveryPendingTaskAndRejectsFutureStarts() {
        Long accountId = 7L;
        taskMap("heartbeatTasks").put(accountId, heartbeatTask);
        taskMap("tokenRefreshTasks").put(accountId, tokenRefreshTask);
        taskMap("tokenRefreshRetryTasks").put(accountId, tokenRetryTask);
        futureMap("reconnectTasks").put(accountId, reconnectTask);

        assertTrue(service.stopWebSocketForAccountDeletion(accountId));

        verify(heartbeatTask).cancel(false);
        verify(tokenRefreshTask).cancel(false);
        verify(tokenRetryTask).cancel(false);
        verify(reconnectTask).cancel(false);
        assertFalse(service.startWebSocket(accountId));
        // isPermanentlyStopped 的 ID 复用自愈会做一次 getById 状态检查，属预期交互；
        // 不应有任何写操作或连接重建
        verify(accountService, atMost(2)).getById(accountId);
        verify(accountService, never()).deleteAccountAndRelatedData(accountId);
    }

    @Test
    void permanentStopReportsFailureWhenTheLiveClientCannotClose() throws Exception {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        taskMap("heartbeatTasks").put(accountId, heartbeatTask);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertFalse(service.stopWebSocketForAccountDeletion(accountId));
        assertSame(client, clientMap().get(accountId));
        verify(client).setIntentionalClose(false);
        verify(client, never()).setOnConnectionClosed(null);
        verify(heartbeatTask, never()).cancel(false);
    }

    @Test
    void startDoesNotReplaceAClientThatCannotBeConfirmedClosed() throws Exception {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(client.isConnected()).thenReturn(false);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertFalse(service.startWebSocket(accountId));

        assertSame(client, clientMap().get(accountId));
        verifyNoInteractions(accountService);
    }

    @Test
    void deletionWaitsForInFlightHandshakeAndClosesTheClientBeforeReturning() throws Exception {
        Long accountId = 7L;
        CountDownLatch handshakeStarted = new CountDownLatch(1);
        CountDownLatch releaseHandshake = new CountDownLatch(1);
        CountDownLatch deletionInvoked = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        when(accountService.getCookieByAccountId(accountId)).thenReturn("unb=buyer-7");
        when(accountService.getOrGenerateDeviceId(accountId, "buyer-7")).thenReturn("device-7");
        when(tokenService.getAccessToken(accountId)).thenReturn("token-7");
        when(config.getHeartbeatInterval()).thenReturn(60);
        when(config.getHeartbeatTimeout()).thenReturn(10);

        when(client.connectBlocking(10, TimeUnit.SECONDS)).thenAnswer(invocation -> {
            handshakeStarted.countDown();
            assertTrue(releaseHandshake.await(10, TimeUnit.SECONDS));
            return true;
        });
        when(client.isConnected()).thenReturn(true);
        when(client.isOpen()).thenReturn(true);
        when(client.isClosed()).thenReturn(false, true);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        try {
            Future<Boolean> startResult = workers.submit(() -> service.startWebSocket(accountId));
            assertTrue(handshakeStarted.await(5, TimeUnit.SECONDS));

            Future<Boolean> deletionResult = workers.submit(() -> {
                deletionInvoked.countDown();
                return service.stopWebSocketForAccountDeletion(accountId);
            });
            assertTrue(deletionInvoked.await(1, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class,
                    () -> deletionResult.get(200, TimeUnit.MILLISECONDS));

            releaseHandshake.countDown();
            assertTrue(startResult.get(2, TimeUnit.SECONDS));
            assertTrue(deletionResult.get(2, TimeUnit.SECONDS));

            verify(client).connectBlocking(10, TimeUnit.SECONDS);
            verify(client).setOnConnectionClosed(null);
            verify(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
            assertFalse(clientMap().containsKey(accountId));
        } finally {
            releaseHandshake.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
            service.cleanup();
        }
    }

    @Test
    void permanentStopRejectsAllSendsEvenIfALateClientReferenceAppears() {
        Long accountId = 7L;
        assertTrue(service.stopWebSocketForAccountDeletion(accountId));

        clientMap().put(accountId, client);
        assertFalse(service.sendMessage(accountId, "cid", "buyer", "text"));
        assertFalse(service.sendMessageWithResult(accountId, "cid", "buyer", "text"));
        assertFalse(service.sendImageMessage(accountId, "cid", "buyer", "image", 1, 1));
        assertFalse(service.sendImageMessageWithResult(accountId, "cid", "buyer", "image", 1, 1));

        verifyNoInteractions(client);
    }

    @Test
    void permanentStopRejectsReconnectAndTokenRefreshWork() {
        Long accountId = 7L;
        assertTrue(service.stopWebSocketForAccountDeletion(accountId));

        ReflectionTestUtils.invokeMethod(service, "scheduleReconnect", accountId, 0, false);
        ReflectionTestUtils.invokeMethod(
                service, "requestReconnectFromCloseCallback", accountId, 0, false);
        ReflectionTestUtils.invokeMethod(service, "scheduleTokenRefreshRetry", accountId);
        ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

        assertTrue(futureMap("reconnectTasks").isEmpty());
        assertTrue(taskMap("tokenRefreshRetryTasks").isEmpty());
        verify(accountService, atMost(4)).getById(accountId);
        verifyNoInteractions(tokenService, config);
    }

    @Test
    void websocketTokenRefreshHasOnlyTheConnectionLifecycleScheduler() throws Exception {
        java.lang.reflect.Method legacyScheduler = TokenRefreshServiceImpl.class
                .getDeclaredMethod("scheduledRefreshWebSocketToken");

        assertNull(legacyScheduler.getAnnotation(
                org.springframework.scheduling.annotation.Scheduled.class));
    }

    @Test
    void ensureConnectedSchedulesOnlyOneReconnectForADisconnectedAccount() {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(config.getReconnectDelay()).thenReturn(60);

        try {
            service.ensureConnected(accountId);
            service.ensureConnected(accountId);

            assertEquals(1, futureMap("reconnectTasks").size());
            assertEquals(1, service.getReconnectAttempt(accountId));
            verify(config, times(1)).getReconnectDelay();
        } finally {
            service.cleanup();
        }
    }

    @Test
    void ensureConnectedLeavesAHealthyConnectionUntouched() {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(client.isConnected()).thenReturn(true);

        service.ensureConnected(accountId);

        assertTrue(futureMap("reconnectTasks").isEmpty());
        verifyNoInteractions(config);
    }

    @Test
    void ensureConnectedResumesAfterCaptchaWaitClears() {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(tokenService.isCaptchaPending(accountId)).thenReturn(true, false);
        when(config.getReconnectDelay()).thenReturn(60);

        try {
            service.ensureConnected(accountId);
            assertTrue(futureMap("reconnectTasks").isEmpty());

            service.ensureConnected(accountId);
            assertEquals(1, futureMap("reconnectTasks").size());
        } finally {
            service.cleanup();
        }
    }

    @Test
    void ensureConnectedDoesNotScheduleWorkAfterShutdown() {
        service.cleanup();

        service.ensureConnected(7L);

        assertTrue(futureMap("reconnectTasks").isEmpty());
        verifyNoInteractions(config, accountService, tokenService);
    }

    @Test
    void disablingAutoConnectPersistsTheManualStopIntent() {
        Long accountId = 7L;
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        assertTrue(service.setAutoConnectEnabled(accountId, false));

        assertTrue(futureMap("reconnectTasks").isEmpty());
        verify(xianyuAccountMapper).update(isNull(), any(UpdateWrapper.class));
        verify(xianyuAccountMapper, never()).updateById(any(XianyuAccount.class));
    }

    @Test
    void disablingAutoConnectDoesNotCloseTheConnectionWhenPersistenceFails() {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        assertFalse(service.setAutoConnectEnabled(accountId, false));

        assertSame(client, clientMap().get(accountId));
        verifyNoInteractions(client);
    }

    @Test
    void disablingAutoConnectPersistsBeforeClosingAnOnlineConnection() throws Exception {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertTrue(service.setAutoConnectEnabled(accountId, false));

        InOrder order = inOrder(xianyuAccountMapper, client);
        order.verify(xianyuAccountMapper).update(isNull(), any(UpdateWrapper.class));
        order.verify(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
        assertFalse(clientMap().containsKey(accountId));
    }

    @Test
    void enablingAutoConnectResetsBackoffAndSchedulesTheBaseDelay() {
        Long accountId = 7L;
        reconnectAttemptMap().put(accountId, new AtomicInteger(9));
        futureMap("reconnectTasks").put(accountId, reconnectTask);
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(config.getReconnectDelay()).thenReturn(5);

        try {
            assertTrue(service.setAutoConnectEnabled(accountId, true));
            assertEquals(0, service.getReconnectAttempt(accountId));

            long scheduledAt = System.currentTimeMillis();
            service.ensureConnected(accountId);

            assertEquals(1, service.getReconnectAttempt(accountId));
            assertTrue(service.getNextReconnectAt(accountId) <= scheduledAt + 6000L);
            verify(reconnectTask).cancel(false);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void resumingAutoConnectionQueuesARetryWhenTheCurrentClientCannotClose() throws Exception {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(config.getReconnectDelay()).thenReturn(60);

        try {
            assertTrue(service.resumeAutoConnection(accountId));

            assertSame(client, clientMap().get(accountId));
            assertTrue(service.isReconnecting(accountId));
            assertEquals(1, service.getReconnectAttempt(accountId));
            verify(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(accountService, never()).getCookieByAccountId(accountId);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void credentialUpdateQueuesOneDelayedReconnect() {
        Long accountId = 7L;
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));

        try {
            long scheduledAt = System.currentTimeMillis();
            assertTrue(service.resumeAutoConnectionAfterCredentialUpdate(accountId));

            assertTrue(service.isReconnecting(accountId));
            assertEquals(1, service.getReconnectAttempt(accountId));
            assertTrue(service.getNextReconnectAt(accountId) >= scheduledAt + 14_000L);
            assertTrue(service.getNextReconnectAt(accountId) <= scheduledAt + 16_000L);
            assertEquals(1, futureMap("reconnectTasks").size());
            verify(accountService, never()).getCookieByAccountId(accountId);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void cancelledRunningReconnectCannotRemoveItsReplacementTask() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(config.getReconnectDelay()).thenReturn(60);
        ReentrantLock lifecycleLock =
                ReflectionTestUtils.invokeMethod(service, "lifecycleLock", accountId);

        lifecycleLock.lock();
        Future<?> replacementTask;
        try {
            ReflectionTestUtils.invokeMethod(service, "scheduleReconnect", accountId, 0, false);
            assertTrue(service.setAutoConnectEnabled(accountId, true));
            service.ensureConnected(accountId);
            replacementTask = futureMap("reconnectTasks").get(accountId);
            assertTrue(replacementTask != null);
        } finally {
            lifecycleLock.unlock();
        }

        try {
            Thread.sleep(200);
            assertSame(replacementTask, futureMap("reconnectTasks").get(accountId));
        } finally {
            service.cleanup();
        }
    }

    @Test
    void queuedCloseCallbackCannotReconnectAfterManualDisable() throws Exception {
        Long accountId = 7L;
        AtomicReference<XianyuAccount> persistedAccount =
                new AtomicReference<>(autoConnectAccount(accountId));
        CountDownLatch disabledStateChecked = new CountDownLatch(1);
        when(xianyuAccountMapper.selectById(accountId)).thenAnswer(invocation -> {
            XianyuAccount account = persistedAccount.get();
            if (Integer.valueOf(0).equals(account.getWebsocketEnabled())) {
                disabledStateChecked.countDown();
            }
            return account;
        });
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            XianyuAccount disabled = autoConnectAccount(accountId);
            disabled.setWebsocketEnabled(0);
            persistedAccount.set(disabled);
            return 1;
        });
        ReentrantLock lifecycleLock =
                ReflectionTestUtils.invokeMethod(service, "lifecycleLock", accountId);
        ExecutorService callbackWorker = Executors.newSingleThreadExecutor();

        lifecycleLock.lock();
        try {
            Future<?> queuedCallback = callbackWorker.submit(() -> ReflectionTestUtils.invokeMethod(
                    service, "requestReconnectFromCloseCallback", accountId, 0, false));
            queuedCallback.get(1, TimeUnit.SECONDS);
            assertTrue(service.setAutoConnectEnabled(accountId, false));
        } finally {
            lifecycleLock.unlock();
        }

        try {
            assertTrue(disabledStateChecked.await(1, TimeUnit.SECONDS));
            assertTrue(futureMap("reconnectTasks").isEmpty());
            verifyNoInteractions(config, accountService, tokenService);
        } finally {
            callbackWorker.shutdownNow();
            assertTrue(callbackWorker.awaitTermination(2, TimeUnit.SECONDS));
            service.cleanup();
        }
    }

    @Test
    void watchdogReconnectDoesNotReplaceAConnectionRecoveredDuringItsDelay() throws Exception {
        Long accountId = 7L;
        CountDownLatch recoveredConnectionChecked = new CountDownLatch(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(config.getReconnectDelay()).thenReturn(1);

        try {
            service.ensureConnected(accountId);
            clientMap().put(accountId, client);
            when(client.isConnected()).thenAnswer(invocation -> {
                recoveredConnectionChecked.countDown();
                return true;
            });

            assertTrue(recoveredConnectionChecked.await(3, TimeUnit.SECONDS));
            awaitCondition(() -> futureMap("reconnectTasks").isEmpty()
                    && service.getReconnectAttempt(accountId) == 0);

            assertSame(client, clientMap().get(accountId));
            assertEquals(0, service.getReconnectAttempt(accountId));
            verify(client).isConnected();
            verifyNoInteractions(accountService);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void closeCallbackDoesNotReplaceAConnectionRecoveredDuringItsDelay() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        try {
            ReflectionTestUtils.invokeMethod(
                    service, "requestReconnectFromCloseCallback", accountId, 1, false);
            clientMap().put(accountId, client);
            when(client.isConnected()).thenReturn(true);

            awaitCondition(() -> futureMap("reconnectTasks").isEmpty()
                    && service.getReconnectAttempt(accountId) == 0);

            assertSame(client, clientMap().get(accountId));
            verify(client).isConnected();
            verify(client, never()).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verifyNoInteractions(accountService);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void watchdogReconnectStopsWhenTheCookieBecomesInvalidBeforeTheAttempt() throws Exception {
        Long accountId = 7L;
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setStatus(1);
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setCookieStatus(3);
        cookie.setCookieText("unb=buyer-7");
        when(config.getReconnectDelay()).thenReturn(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(account);
        when(xianyuCookieMapper.selectOne(any())).thenReturn(cookie);

        try {
            service.ensureConnected(accountId);
            awaitCondition(() -> futureMap("reconnectTasks").isEmpty()
                    && service.getReconnectAttempt(accountId) == 0);

            assertEquals(0, service.getReconnectAttempt(accountId));
            verifyNoInteractions(accountService);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void preservesUnknownTextOutcomeFromClient() {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(client.isConnected()).thenReturn(true);
        when(client.sendMessageWithOutcome("chat", "buyer", "content"))
                .thenReturn(MessageSendResult.UNKNOWN);

        MessageSendResult result = service.sendMessageWithOutcome(accountId, "chat", "buyer", "content");

        assertSame(MessageSendResult.UNKNOWN, result);
    }

    @Test
    void preservesFailedImageOutcomeFromClient() {
        Long accountId = 7L;
        clientMap().put(accountId, client);
        when(client.isConnected()).thenReturn(true);
        when(client.sendImageMessageWithOutcome("chat", "buyer", "image", 800, 800))
                .thenReturn(MessageSendResult.FAILED);

        MessageSendResult result = service.sendImageMessageWithOutcome(
                accountId, "chat", "buyer", "image", 800, 800);

        assertSame(MessageSendResult.FAILED, result);
    }

    @Test
    void acknowledgementCompletesWhileOutcomeSendHoldsLifecycleBarrier() throws Exception {
        Long accountId = 7L;
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch acknowledgementDelivered = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        clientMap().put(accountId, client);
        when(client.isConnected()).thenReturn(true);
        when(client.sendMessageWithOutcome("chat", "buyer", "content")).thenAnswer(invocation -> {
            sendStarted.countDown();
            assertTrue(acknowledgementDelivered.await(2, TimeUnit.SECONDS));
            return MessageSendResult.SUCCESS;
        });
        doAnswer(invocation -> {
            acknowledgementDelivered.countDown();
            return null;
        }).when(client).completePendingResponse("mid-1", 200);

        try {
            Future<MessageSendResult> sendResult = workers.submit(
                    () -> service.sendMessageWithOutcome(accountId, "chat", "buyer", "content"));
            assertTrue(sendStarted.await(1, TimeUnit.SECONDS));

            Future<?> acknowledgement = workers.submit(
                    () -> service.completePendingResponse(accountId, "mid-1", 200));
            acknowledgement.get(500, TimeUnit.MILLISECONDS);
            assertSame(MessageSendResult.SUCCESS, sendResult.get(1, TimeUnit.SECONDS));
        } finally {
            acknowledgementDelivered.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
            service.cleanup();
        }
    }

    @Test
    void tokenExpirySchedulesRetryWhenOldClientCannotClose() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        clientMap().put(accountId, client);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(config.getReconnectDelay()).thenReturn(60);

        try {
            ReflectionTestUtils.invokeMethod(service, "requestTokenExpiredReconnect", accountId);
            awaitCondition(() -> futureMap("reconnectTasks").containsKey(accountId));

            verify(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(tokenService).isCaptchaPending(accountId);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void replacementTokenIsKeptWhenTheOldClientCannotClose() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        clientMap().put(accountId, client);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(tokenService.refreshToken(accountId)).thenReturn("replacement-token");

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

            assertSame(client, clientMap().get(accountId));
            assertFalse(taskMap("tokenRefreshRetryTasks").containsKey(accountId));
            assertFalse(longMap("lastTokenRefreshTimes").containsKey(accountId));
            verify(tokenService).refreshToken(accountId);
            verify(tokenService, never()).clearToken(accountId);
            verify(accountService, never()).getCookieByAccountId(accountId);
            verify(initializer, never()).initialize(any(), any(), any(), any());
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void healthyPersistedTokenWithMoreThanThirtyMinutesRemainingIsNotClearedOrReconnected()
            throws Exception {
        Long accountId = 7L;
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setWebsocketToken("healthy-token");
        cookie.setTokenExpireTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(31));
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(xianyuCookieMapper.selectOne(any())).thenReturn(cookie);
        clientMap().put(accountId, client);

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

            assertSame(client, clientMap().get(accountId));
            assertFalse(taskMap("tokenRefreshRetryTasks").containsKey(accountId));
            verify(client, never()).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(tokenService, never()).clearToken(accountId);
            verifyNoInteractions(cookieRefreshService, accountService, initializer);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void captchaPendingShortCircuitsTheMinuteTokenRefreshTask() {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(tokenService.isCaptchaPending(accountId)).thenReturn(true);

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

            assertNotNull(captchaProbeTaskMap().get(accountId));
            verify(xianyuCookieMapper, never()).selectOne(any());
            verifyNoInteractions(cookieRefreshService, accountService, initializer);
            verify(client, never()).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(tokenService, never()).clearToken(accountId);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void failedReplacementTokenKeepsTheHealthyConnectionRunning() throws Exception {
        Long accountId = 7L;
        XianyuCookie cookie = new XianyuCookie();
        cookie.setXianyuAccountId(accountId);
        cookie.setWebsocketToken("current-token");
        cookie.setTokenExpireTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20));
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(xianyuCookieMapper.selectOne(any())).thenReturn(cookie);
        when(cookieRefreshService.checkLoginStatusQuietly(accountId)).thenReturn(true);
        when(tokenService.refreshToken(accountId)).thenReturn(null);
        when(config.getTokenRetryInterval()).thenReturn(60);
        clientMap().put(accountId, client);

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

            assertSame(client, clientMap().get(accountId));
            assertNotNull(taskMap("tokenRefreshRetryTasks").get(accountId));
            verify(tokenService).refreshToken(accountId);
            verify(client, never()).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(tokenService, never()).clearToken(accountId);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void delayedTokenRefreshRetryRunsWhileTheOldConnectionIsStillOpen() throws Exception {
        Long accountId = 7L;
        ScheduledExecutorService retryExecutor = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> retryFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledRetry = new AtomicReference<>();
        ScheduledExecutorService originalExecutor =
                (ScheduledExecutorService) ReflectionTestUtils.getField(service, "reconnectExecutor");
        ReflectionTestUtils.setField(service, "reconnectExecutor", retryExecutor);
        if (originalExecutor != null) {
            originalExecutor.shutdownNow();
        }

        when(retryExecutor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    scheduledRetry.set(invocation.getArgument(0));
                    return retryFuture;
                });
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(cookieRefreshService.checkLoginStatusQuietly(accountId)).thenReturn(true);
        when(tokenService.refreshToken(accountId)).thenReturn(null);
        when(config.getTokenRetryInterval()).thenReturn(300);
        clientMap().put(accountId, client);

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);
            assertNotNull(scheduledRetry.get());

            scheduledRetry.get().run();

            verify(tokenService, times(2)).refreshToken(accountId);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void registrationTimeoutIsAConnectionFailureAndDiscardsTheSocket() throws Exception {
        Long accountId = 7L;
        when(accountService.getCookieByAccountId(accountId)).thenReturn("unb=buyer-7");
        when(accountService.getOrGenerateDeviceId(accountId, "buyer-7")).thenReturn("device-7");
        when(tokenService.getAccessToken(accountId)).thenReturn("token-7");
        when(client.connectBlocking(10, TimeUnit.SECONDS)).thenReturn(true);
        when(client.awaitRegistration(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertFalse(service.startWebSocket(accountId));

        assertFalse(clientMap().containsKey(accountId));
        verify(initializer).initialize(client, "token-7", "device-7", "7");
        verify(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
        assertFalse(taskMap("heartbeatTasks").containsKey(accountId));
    }

    @Test
    void registrationTimeoutWithOpenSocketClosesItToTriggerReconnect() throws Exception {
        Long accountId = 7L;
        when(accountService.getCookieByAccountId(accountId)).thenReturn("unb=buyer-7");
        when(accountService.getOrGenerateDeviceId(accountId, "buyer-7")).thenReturn("device-7");
        when(tokenService.getAccessToken(accountId)).thenReturn("token-7");
        when(client.connectBlocking(10, TimeUnit.SECONDS)).thenReturn(true);
        when(client.awaitRegistration(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(client.isOpen()).thenReturn(true);
        when(client.isClosed()).thenReturn(false);

        try {
            // 升级：注册超时但连接仍打开时不再"假在线"监听，
            // 而是主动关闭触发 onClose -> 自动重连（指数退避 + 5次后邮件通知）
            assertFalse(service.startWebSocket(accountId));

            verify(initializer).initialize(client, "token-7", "device-7", "7");
            verify(client).close(1000, "registration timeout");
            assertFalse(taskMap("heartbeatTasks").containsKey(accountId));
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void minuteTokenInspectionDoesNotBypassAnActiveDelayedRetry() {
        Long accountId = 7L;
        ScheduledExecutorService minuteExecutor = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> minuteFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
        AtomicReference<Runnable> minuteInspection = new AtomicReference<>();
        ScheduledExecutorService originalScheduler =
                (ScheduledExecutorService) ReflectionTestUtils.getField(service, "tokenRefreshScheduler");
        ReflectionTestUtils.setField(service, "tokenRefreshScheduler", minuteExecutor);
        if (originalScheduler != null) {
            originalScheduler.shutdownNow();
        }

        when(minuteExecutor.scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    minuteInspection.set(invocation.getArgument(0));
                    return minuteFuture;
                });
        when(tokenRetryTask.isDone()).thenReturn(false);
        taskMap("tokenRefreshRetryTasks").put(accountId, tokenRetryTask);

        try {
            ReflectionTestUtils.invokeMethod(service, "startTokenRefresh", accountId);
            assertNotNull(minuteInspection.get());

            minuteInspection.get().run();

            verify(xianyuCookieMapper, never()).selectOne(any());
            verify(tokenService, never()).refreshToken(accountId);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void captchaProbeRefreshesCredentialsEvenWhenTheOldConnectionIsStillOpen() throws Exception {
        Long accountId = 7L;
        ScheduledExecutorService probeExecutor = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> probeFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledProbe = new AtomicReference<>();
        ScheduledExecutorService originalExecutor =
                (ScheduledExecutorService) ReflectionTestUtils.getField(service, "reconnectExecutor");
        ReflectionTestUtils.setField(service, "reconnectExecutor", probeExecutor);
        if (originalExecutor != null) {
            originalExecutor.shutdownNow();
        }

        when(probeExecutor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    scheduledProbe.set(invocation.getArgument(0));
                    return probeFuture;
                });
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(tokenService.probeAccessToken(accountId)).thenReturn("replacement-token");
        when(accountService.getCookieByAccountId(accountId)).thenReturn("unb=buyer-7");
        when(accountService.getOrGenerateDeviceId(accountId, "buyer-7")).thenReturn("device-7");
        when(client.isConnected()).thenReturn(true);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(client.connectBlocking(10, TimeUnit.SECONDS)).thenReturn(true);
        when(client.isOpen()).thenReturn(true);
        when(client.isClosed()).thenReturn(false);
        when(config.getHeartbeatInterval()).thenReturn(60);
        when(config.getHeartbeatTimeout()).thenReturn(10);
        clientMap().put(accountId, client);

        try {
            ReflectionTestUtils.invokeMethod(service, "scheduleCaptchaProbeLocked", accountId);
            assertNotNull(scheduledProbe.get());

            scheduledProbe.get().run();

            verify(tokenService).probeAccessToken(accountId);
            verify(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(initializer).initialize(client, "replacement-token", "device-7", "7");
            assertFalse(captchaProbeAttemptMap().containsKey(accountId));
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void captchaProbeUsesIncreasingJitteredBackoffWithSixHourCap() {
        Long accountId = 7L;
        ScheduledExecutorService probeExecutor = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        List<Runnable> scheduledProbes = new ArrayList<>();
        List<Long> scheduledDelays = new ArrayList<>();
        ScheduledExecutorService originalExecutor =
                (ScheduledExecutorService) ReflectionTestUtils.getField(service, "reconnectExecutor");
        ReflectionTestUtils.setField(service, "reconnectExecutor", probeExecutor);
        if (originalExecutor != null) {
            originalExecutor.shutdownNow();
        }

        when(probeExecutor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    scheduledProbes.add(invocation.getArgument(0));
                    scheduledDelays.add(invocation.getArgument(1));
                    return org.mockito.Mockito.mock(ScheduledFuture.class);
                });
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(tokenService.probeAccessToken(accountId))
                .thenThrow(new com.feijimiao.xianyuassistant.exception.CaptchaRequiredException(""));

        long[][] expectedDelayRanges = {
                {TimeUnit.MINUTES.toSeconds(30), TimeUnit.MINUTES.toSeconds(33)},
                {TimeUnit.MINUTES.toSeconds(60), TimeUnit.HOURS.toSeconds(1) + TimeUnit.MINUTES.toSeconds(6)},
                {TimeUnit.MINUTES.toSeconds(120), TimeUnit.HOURS.toSeconds(2) + TimeUnit.MINUTES.toSeconds(12)},
                {TimeUnit.MINUTES.toSeconds(240), TimeUnit.HOURS.toSeconds(4) + TimeUnit.MINUTES.toSeconds(24)},
                {TimeUnit.MINUTES.toSeconds(360), TimeUnit.HOURS.toSeconds(6) + TimeUnit.MINUTES.toSeconds(36)},
                {TimeUnit.MINUTES.toSeconds(360), TimeUnit.HOURS.toSeconds(6) + TimeUnit.MINUTES.toSeconds(36)}
        };

        try {
            ReflectionTestUtils.invokeMethod(service, "scheduleCaptchaProbeLocked", accountId);
            for (int attempt = 0; attempt < expectedDelayRanges.length; attempt++) {
                assertEquals(attempt + 1, scheduledDelays.size());
                long delay = scheduledDelays.get(attempt);
                assertTrue(delay >= expectedDelayRanges[attempt][0]
                                && delay <= expectedDelayRanges[attempt][1],
                        "unexpected captcha probe delay at attempt " + attempt + ": " + delay);
                if (attempt + 1 < expectedDelayRanges.length) {
                    scheduledProbes.get(attempt).run();
                }
            }
        } finally {
            service.cleanup();
        }
    }

    @Test
    void captchaDuringReconnectCookiePreflightUsesOnlyTheLowFrequencyProbe() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(cookieRefreshService.checkLoginStatusQuietly(accountId))
                .thenThrow(new com.feijimiao.xianyuassistant.exception.CaptchaRequiredException(""));

        try {
            ReflectionTestUtils.invokeMethod(service, "scheduleReconnect", accountId, 0, false);
            awaitCondition(() -> captchaProbeTaskMap().containsKey(accountId));

            verify(accountService, never()).getCookieByAccountId(accountId);
            verify(tokenService, never()).getAccessToken(accountId);
            assertNotNull(captchaProbeTaskMap().get(accountId));
        } finally {
            service.cleanup();
        }
    }

    @Test
    void captchaDuringTokenRefreshSchedulesThirtyMinuteProbeInsteadOfTokenRetry() {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(cookieRefreshService.checkLoginStatusQuietly(accountId)).thenReturn(true);
        when(tokenService.refreshToken(accountId))
                .thenThrow(new com.feijimiao.xianyuassistant.exception.CaptchaRequiredException(
                        "https://example.invalid/captcha"));

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

            assertFalse(taskMap("tokenRefreshRetryTasks").containsKey(accountId));
            ScheduledFuture<?> probe = captchaProbeTaskMap().get(accountId);
            assertNotNull(probe);
            long delaySeconds = probe.getDelay(TimeUnit.SECONDS);
            // 探针基准 30 分钟 + 最多 10% 向前抖动；保留调度/读取延迟余量，避免 CI 慢速竞态。
            assertTrue(delaySeconds >= TimeUnit.MINUTES.toSeconds(25)
                            && delaySeconds <= TimeUnit.MINUTES.toSeconds(35),
                    "expected a low-frequency captcha probe, delay=" + delaySeconds);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void captchaDuringCookiePreflightSkipsTokenClearAndUsesLowFrequencyProbe() {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(cookieRefreshService.checkLoginStatusQuietly(accountId))
                .thenThrow(new com.feijimiao.xianyuassistant.exception.CaptchaRequiredException(
                        "https://example.invalid/captcha"));
        org.mockito.Mockito.lenient().when(config.getTokenRetryInterval()).thenReturn(60);

        try {
            ReflectionTestUtils.invokeMethod(service, "refreshTokenAndReconnect", accountId);

            assertFalse(taskMap("tokenRefreshRetryTasks").containsKey(accountId));
            assertNotNull(captchaProbeTaskMap().get(accountId));
            verify(tokenService, never()).clearToken(accountId);
            verify(accountService, never()).getCookieByAccountId(accountId);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void manualResumeCancelsCaptchaProbeAndQueuesImmediateReconnect() {
        Long accountId = 7L;
        when(accountService.getCookieByAccountId(accountId)).thenReturn("unb=buyer-7");
        when(accountService.getOrGenerateDeviceId(accountId, "buyer-7")).thenReturn("device-7");
        when(tokenService.getAccessToken(accountId))
                .thenThrow(new com.feijimiao.xianyuassistant.exception.CaptchaRequiredException(
                        "https://example.invalid/captcha"));
        assertThrows(com.feijimiao.xianyuassistant.exception.CaptchaRequiredException.class,
                () -> service.startWebSocket(accountId));
        ScheduledFuture<?> probe = captchaProbeTaskMap().get(accountId);
        assertNotNull(probe);

        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        when(tokenService.isCaptchaPending(accountId)).thenReturn(false);
        ReentrantLock lifecycleLock =
                ReflectionTestUtils.invokeMethod(service, "lifecycleLock", accountId);
        lifecycleLock.lock();
        try {
            long resumedAt = System.currentTimeMillis();
            assertTrue(service.resumeAutoConnection(accountId));

            assertTrue(probe.isCancelled());
            assertFalse(captchaProbeTaskMap().containsKey(accountId));
            assertFalse(captchaProbeAttemptMap().containsKey(accountId));
            assertTrue(futureMap("reconnectTasks").containsKey(accountId));
            assertTrue(service.getNextReconnectAt(accountId) <= resumedAt + 1000L);
        } finally {
            lifecycleLock.unlock();
            service.cleanup();
        }
    }

    @Test
    void manualCaptchaRecoveryProbesImmediatelyAndStartsTheSocketAfterTokenSuccess() throws Exception {
        Long accountId = 7L;
        ScheduledFuture<?> captchaProbe = org.mockito.Mockito.mock(ScheduledFuture.class);
        captchaProbeTaskMap().put(accountId, captchaProbe);
        captchaProbeAttemptMap().put(accountId, new AtomicInteger(4));
        futureMap("reconnectTasks").put(accountId, reconnectTask);
        reconnectAttemptMap().put(accountId, new AtomicInteger(8));
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(tokenService.probeAccessTokenAfterManualVerification(accountId)).thenReturn("manual-token");
        when(accountService.getCookieByAccountId(accountId)).thenReturn("unb=buyer-7");
        when(accountService.getOrGenerateDeviceId(accountId, "buyer-7")).thenReturn("device-7");
        when(client.connectBlocking(10, TimeUnit.SECONDS)).thenReturn(true);
        when(client.awaitRegistration(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(client.isConnected()).thenReturn(true);
        when(client.isOpen()).thenReturn(true);
        when(client.isClosed()).thenReturn(false);
        when(config.getHeartbeatInterval()).thenReturn(60);
        when(config.getHeartbeatTimeout()).thenReturn(10);

        try {
            assertTrue(service.recoverAfterManualCaptcha(accountId));

            verify(tokenService).probeAccessTokenAfterManualVerification(accountId);
            verify(captchaProbe).cancel(false);
            verify(reconnectTask).cancel(false);
            verify(initializer).initialize(client, "manual-token", "device-7", "7");
            assertFalse(captchaProbeTaskMap().containsKey(accountId));
            assertFalse(captchaProbeAttemptMap().containsKey(accountId));
            assertEquals(0, service.getReconnectAttempt(accountId));
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void manualCaptchaRecoveryKeepsCaptchaPendingWhenPlatformStillRequiresVerification() {
        Long accountId = 7L;
        when(xianyuAccountMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(tokenService.probeAccessTokenAfterManualVerification(accountId))
                .thenThrow(new com.feijimiao.xianyuassistant.exception.CaptchaRequiredException(""));

        try {
            assertFalse(service.recoverAfterManualCaptcha(accountId));

            verify(tokenService).probeAccessTokenAfterManualVerification(accountId);
            verify(tokenService).restoreCaptchaWait(accountId);
            verify(accountService, never()).getCookieByAccountId(accountId);
            assertNotNull(captchaProbeTaskMap().get(accountId));
        } finally {
            service.cleanup();
        }
    }

    @Test
    void automaticReconnectDoesNotReuseAClientThatCannotClose() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        clientMap().put(accountId, client);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(config.getReconnectDelay()).thenReturn(60);

        try {
            ReflectionTestUtils.invokeMethod(service, "scheduleReconnect", accountId, 0, false);
            awaitCondition(() -> service.getReconnectAttempt(accountId) >= 2
                    && futureMap("reconnectTasks").containsKey(accountId));

            assertSame(client, clientMap().get(accountId));
            assertTrue(service.isReconnecting(accountId));
            verify(accountService, never()).getCookieByAccountId(accountId);
            verify(tokenService, never()).clearToken(accountId);
            verify(initializer, never()).initialize(any(), any(), any(), any());
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void staleTokenExpiryCallbackCannotReplaceTheCurrentClient() throws Exception {
        Long accountId = 7L;
        XianyuWebSocketClient staleClient = org.mockito.Mockito.mock(XianyuWebSocketClient.class);
        clientMap().put(accountId, client);
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));

        try {
            ReflectionTestUtils.invokeMethod(
                    service, "requestTokenExpiredReconnect", accountId, staleClient);
            awaitCondition(() -> futureMap("tokenExpiredRecoveryTasks").isEmpty());

            assertSame(client, clientMap().get(accountId));
            verify(client, never()).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verifyNoInteractions(accountService, tokenService);
        } finally {
            clientMap().remove(accountId);
            service.cleanup();
        }
    }

    @Test
    void disconnectNotificationUsesFiveFailureThresholdAndIsDebounced() {
        Long accountId = 7L;
        ReflectionTestUtils.setField(service, "emailNotifyService", emailNotifyService);
        when(emailNotifyService.isWsDisconnectNotifyEnabled()).thenReturn(true);

        assertEquals(5, ReflectionTestUtils.getField(service, "RECONNECT_NOTIFY_THRESHOLD"));

        ReflectionTestUtils.invokeMethod(service, "triggerWsDisconnectNotify", accountId);
        ReflectionTestUtils.invokeMethod(service, "triggerWsDisconnectNotify", accountId);

        verify(emailNotifyService, times(1)).sendWsDisconnectNotifyEmail(accountId, "");
    }

    @Test
    void tokenExpirySchedulesRetryWhenImmediateRestartReturnsFalse() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        clientMap().put(accountId, client);
        when(client.closeWithin(2000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(config.getReconnectDelay()).thenReturn(60);
        when(accountService.getCookieByAccountId(accountId)).thenReturn(null);

        try {
            ReflectionTestUtils.invokeMethod(service, "requestTokenExpiredReconnect", accountId);
            awaitCondition(() -> futureMap("reconnectTasks").containsKey(accountId));

            verify(tokenService).clearToken(accountId);
            verify(accountService).getCookieByAccountId(accountId);
        } finally {
            service.cleanup();
        }
    }

    @Test
    void burstTokenExpiryCallbacksShareOneRecoveryTask() throws Exception {
        Long accountId = 7L;
        when(xianyuAccountMapper.selectById(accountId)).thenReturn(autoConnectAccount(accountId));
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        clientMap().put(accountId, client);
        doAnswer(invocation -> {
            closeStarted.countDown();
            assertTrue(allowClose.await(2, TimeUnit.SECONDS));
            return true;
        }).when(client).closeWithin(2000L, TimeUnit.MILLISECONDS);
        when(config.getReconnectDelay()).thenReturn(60);
        when(accountService.getCookieByAccountId(accountId)).thenReturn(null);

        try {
            ReflectionTestUtils.invokeMethod(service, "requestTokenExpiredReconnect", accountId);
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
            ReflectionTestUtils.invokeMethod(service, "requestTokenExpiredReconnect", accountId);
            allowClose.countDown();
            awaitCondition(() -> futureMap("tokenExpiredRecoveryTasks").isEmpty());

            verify(client, times(1)).closeWithin(2000L, TimeUnit.MILLISECONDS);
            verify(tokenService, times(1)).clearToken(accountId);
        } finally {
            allowClose.countDown();
            service.cleanup();
        }
    }

    private void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private XianyuAccount autoConnectAccount(Long accountId) {
        XianyuAccount account = new XianyuAccount();
        account.setId(accountId);
        account.setStatus(1);
        account.setWebsocketEnabled(1);
        return account;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ScheduledFuture<?>> taskMap(String fieldName) {
        return (Map<Long, ScheduledFuture<?>>) ReflectionTestUtils.getField(service, fieldName);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ScheduledFuture<?>> captchaProbeTaskMap() {
        Field field = org.springframework.util.ReflectionUtils.findField(
                WebSocketServiceImpl.class, "captchaProbeTasks");
        assertNotNull(field, "captchaProbeTasks must track one low-frequency probe per account");
        org.springframework.util.ReflectionUtils.makeAccessible(field);
        return (Map<Long, ScheduledFuture<?>>)
                org.springframework.util.ReflectionUtils.getField(field, service);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, AtomicInteger> captchaProbeAttemptMap() {
        return (Map<Long, AtomicInteger>) ReflectionTestUtils.getField(
                service, "captchaProbeAttemptCounts");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Future<?>> futureMap(String fieldName) {
        return (Map<Long, Future<?>>) ReflectionTestUtils.getField(service, fieldName);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, AtomicInteger> reconnectAttemptMap() {
        return (Map<Long, AtomicInteger>) ReflectionTestUtils.getField(
                service, "reconnectAttemptCounts");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> longMap(String fieldName) {
        return (Map<Long, Long>) ReflectionTestUtils.getField(service, fieldName);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, XianyuWebSocketClient> clientMap() {
        return (Map<Long, XianyuWebSocketClient>) ReflectionTestUtils.getField(service, "webSocketClients");
    }

    private static final class TestableWebSocketServiceImpl extends WebSocketServiceImpl {
        private final XianyuWebSocketClient client;

        private TestableWebSocketServiceImpl(XianyuWebSocketClient client) {
            this.client = client;
        }

        @Override
        XianyuWebSocketClient createWebSocketClient(
                URI serverUri,
                Map<String, String> headers,
                String accountId,
                AccountDisplayNameUtils accountDisplayNameUtils) {
            return client;
        }
    }
}
