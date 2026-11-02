package com.feijimiao.xianyuassistant.websocket;

import com.feijimiao.xianyuassistant.service.MessageSendResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XianyuWebSocketClientSendResultTest {

    @Test
    void mapsOnlyServer200ToSuccess() {
        assertThat(await(200)).isEqualTo(MessageSendResult.SUCCESS);
        assertThat(await(401)).isEqualTo(MessageSendResult.FAILED);
        assertThat(await(500)).isEqualTo(MessageSendResult.FAILED);
    }

    @Test
    void mapsMissingResponseToUnknown() {
        MessageSendResult result = XianyuWebSocketClient.awaitPendingResponse(
                new CompletableFuture<>(), 1, TimeUnit.MILLISECONDS);

        assertThat(result).isEqualTo(MessageSendResult.UNKNOWN);
    }

    @Test
    void mapsLocalWaitingFailureToFailed() {
        CompletableFuture<Integer> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("local failure"));

        MessageSendResult result = XianyuWebSocketClient.awaitPendingResponse(
                failed, 1, TimeUnit.SECONDS);

        assertThat(result).isEqualTo(MessageSendResult.FAILED);
    }

    @Test
    void registrationReadinessRequiresANonBlankRegSidAcknowledgement() throws Exception {
        XianyuWebSocketClient client = new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null);

        client.onMessage("{\"code\":200,\"headers\":{\"reg-sid\":\"\"}}");
        assertThat(client.awaitRegistration(50, TimeUnit.MILLISECONDS)).isFalse();

        client.onMessage("{\"code\":200,\"headers\":{\"reg-sid\":\"registered-session\"}}");
        assertThat(client.awaitRegistration(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void completes401WaiterBeforeTokenExpiryHandlingCanReturn() throws Exception {
        XianyuWebSocketClient client = new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null);
        CompletableFuture<Integer> response = new CompletableFuture<>();
        Map<String, CompletableFuture<Integer>> pendingResponses =
                (Map<String, CompletableFuture<Integer>>) ReflectionTestUtils.getField(
                        client, "pendingResponses");
        pendingResponses.put("message-mid", response);
        Map<String, Object> message = new HashMap<>();
        message.put("headers", Map.of("mid", "message-mid"));

        client.completePendingResponseFromMessage(message, 401);

        assertThat(response.join()).isEqualTo(401);
        assertThat(pendingResponses).doesNotContainKey("message-mid");
    }

    @Test
    void notifiesConnectionLossOnlyOnceWhenErrorIsFollowedByClose() throws Exception {
        XianyuWebSocketClient client = new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null);
        AtomicInteger notifications = new AtomicInteger();
        client.setOnConnectionClosed(notifications::incrementAndGet);

        client.onError(new IllegalStateException("network failure"));
        client.onClose(1006, "", true);

        assertThat(notifications).hasValue(1);
    }

    @Test
    void errorImmediatelyMarksTheClientDisconnectedAndStopsMessageWorkers() throws Exception {
        XianyuWebSocketClient client = new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null);
        ExecutorService messageExecutor = (ExecutorService) ReflectionTestUtils.getField(client, "messageExecutor");
        ReflectionTestUtils.setField(client, "isConnected", true);

        client.onError(new IllegalStateException("network failure"));

        assertThat(ReflectionTestUtils.getField(client, "isConnected")).isEqualTo(false);
        assertThat(messageExecutor.isShutdown()).isTrue();
    }

    @Test
    void intentionalCloseDoesNotNotifyConnectionLoss() throws Exception {
        XianyuWebSocketClient client = new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null);
        AtomicInteger notifications = new AtomicInteger();
        client.setOnConnectionClosed(notifications::incrementAndGet);
        client.setIntentionalClose(true);

        client.onError(new IllegalStateException("closed locally"));
        client.onClose(1000, "", false);

        assertThat(notifications).hasValue(0);
    }

    @Test
    void boundedCloseReturnsAfterAGracefulClose() throws Exception {
        XianyuWebSocketClient client = spy(new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null));
        AtomicInteger closedChecks = new AtomicInteger();
        doNothing().when(client).close();
        when(client.isClosed()).thenAnswer(ignored -> closedChecks.incrementAndGet() >= 2);
        when(client.getSocket()).thenReturn(null);

        assertThat(client.closeWithin(100, TimeUnit.MILLISECONDS)).isTrue();

        verify(client, never()).closeConnection(eq(1006), anyString());
    }

    @Test
    void boundedCloseForcesTheSocketClosedAfterTimeout() throws Exception {
        XianyuWebSocketClient client = spy(new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null));
        Socket socket = mock(Socket.class);
        AtomicBoolean forcedClosed = new AtomicBoolean();
        AtomicBoolean socketClosed = new AtomicBoolean();
        doNothing().when(client).close();
        when(client.isClosed()).thenAnswer(ignored -> forcedClosed.get());
        when(client.getSocket()).thenReturn(socket);
        when(socket.isClosed()).thenAnswer(ignored -> socketClosed.get());
        doAnswer(ignored -> {
            forcedClosed.set(true);
            return null;
        }).when(client).closeConnection(eq(1006), anyString());
        doAnswer(ignored -> {
            socketClosed.set(true);
            return null;
        }).when(socket).close();

        assertThat(client.closeWithin(1, TimeUnit.MILLISECONDS)).isTrue();

        verify(client, timeout(500)).closeConnection(eq(1006), anyString());
        verify(socket).close();
    }

    @Test
    void boundedCloseReturnsEvenWhenLibraryForceCloseBlocks() throws Exception {
        XianyuWebSocketClient client = spy(new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null));
        Socket socket = mock(Socket.class);
        AtomicBoolean socketClosed = new AtomicBoolean();
        CountDownLatch gracefulCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseGracefulClose = new CountDownLatch(1);
        CountDownLatch forceCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseForceClose = new CountDownLatch(1);
        doAnswer(ignored -> {
            gracefulCloseEntered.countDown();
            releaseGracefulClose.await(2, TimeUnit.SECONDS);
            return null;
        }).when(client).close();
        when(client.isClosed()).thenReturn(false);
        when(client.getSocket()).thenReturn(socket);
        when(socket.isClosed()).thenAnswer(ignored -> socketClosed.get());
        doAnswer(ignored -> {
            socketClosed.set(true);
            return null;
        }).when(socket).close();
        doAnswer(ignored -> {
            forceCloseEntered.countDown();
            releaseForceClose.await(2, TimeUnit.SECONDS);
            return null;
        }).when(client).closeConnection(eq(1006), anyString());

        long startedAt = System.nanoTime();
        try {
            assertThat(client.closeWithin(25, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(gracefulCloseEntered.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(forceCloseEntered.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(System.nanoTime() - startedAt)
                    .isLessThan(TimeUnit.MILLISECONDS.toNanos(750));
        } finally {
            releaseGracefulClose.countDown();
            releaseForceClose.countDown();
        }
    }

    @Test
    void repeatedBoundedCloseDoesNotDuplicateBlockedCloseActions() throws Exception {
        XianyuWebSocketClient client = spy(new XianyuWebSocketClient(
                new URI("ws://127.0.0.1"), Map.of(), "7", null));
        Socket socket = mock(Socket.class);
        CountDownLatch actionsEntered = new CountDownLatch(3);
        CountDownLatch releaseActions = new CountDownLatch(1);
        doAnswer(ignored -> {
            actionsEntered.countDown();
            releaseActions.await(2, TimeUnit.SECONDS);
            return null;
        }).when(client).close();
        when(client.isClosed()).thenReturn(false);
        when(client.getSocket()).thenReturn(socket);
        when(socket.isClosed()).thenReturn(false);
        doAnswer(ignored -> {
            actionsEntered.countDown();
            releaseActions.await(2, TimeUnit.SECONDS);
            return null;
        }).when(socket).close();
        doAnswer(ignored -> {
            actionsEntered.countDown();
            releaseActions.await(2, TimeUnit.SECONDS);
            return null;
        }).when(client).closeConnection(eq(1006), anyString());

        try {
            assertThat(client.closeWithin(1, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(actionsEntered.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(client.closeWithin(1, TimeUnit.MILLISECONDS)).isFalse();

            verify(client, times(1)).close();
            verify(socket, times(1)).close();
            verify(client, times(1)).closeConnection(eq(1006), anyString());
        } finally {
            releaseActions.countDown();
        }
    }

    private MessageSendResult await(int code) {
        return XianyuWebSocketClient.awaitPendingResponse(
                CompletableFuture.completedFuture(code), 1, TimeUnit.SECONDS);
    }
}
