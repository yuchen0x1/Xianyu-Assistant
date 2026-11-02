package com.feijimiao.xianyuassistant.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AutomationHealthService {

    private final ConcurrentMap<Long, State> states = new ConcurrentHashMap<>();

    public void recordWebSocketMessages(Long accountId, int messageCount) {
        if (accountId == null || messageCount <= 0) {
            return;
        }
        states.computeIfAbsent(accountId, ignored -> new State())
                .recordWebSocketMessages(messageCount);
    }

    public void recordCustomerMessage(Long accountId) {
        if (accountId == null) {
            return;
        }
        states.computeIfAbsent(accountId, ignored -> new State())
                .recordCustomerMessage();
    }

    public void recordReplyState(Long accountId, String status, String reason) {
        if (accountId == null || status == null || reason == null) {
            return;
        }
        states.computeIfAbsent(accountId, ignored -> new State())
                .recordReplyState(status, reason);
    }

    public void recordReplySuccess(Long accountId) {
        if (accountId == null) {
            return;
        }
        states.computeIfAbsent(accountId, ignored -> new State())
                .recordReplySuccess();
    }

    public Snapshot getSnapshot(Long accountId) {
        State state = accountId == null ? null : states.get(accountId);
        return state == null ? Snapshot.empty() : state.snapshot();
    }

    private static final class State {
        private long webSocketMessageCount;
        private Long lastWebSocketMessageAt;
        private Long lastCustomerMessageAt;
        private String lastAutoReplyStatus = "IDLE";
        private String lastAutoReplyReason = "NO_ACTIVITY";
        private Long lastAutoReplyUpdatedAt;
        private Long lastSuccessfulReplyAt;

        private synchronized void recordWebSocketMessages(int count) {
            webSocketMessageCount += count;
            lastWebSocketMessageAt = System.currentTimeMillis();
        }

        private synchronized void recordCustomerMessage() {
            lastCustomerMessageAt = System.currentTimeMillis();
            recordReplyState("RECEIVED", "CUSTOMER_MESSAGE");
        }

        private synchronized void recordReplyState(String status, String reason) {
            lastAutoReplyStatus = status;
            lastAutoReplyReason = reason;
            lastAutoReplyUpdatedAt = System.currentTimeMillis();
        }

        private synchronized void recordReplySuccess() {
            long now = System.currentTimeMillis();
            lastAutoReplyStatus = "SUCCESS";
            lastAutoReplyReason = "SENT";
            lastAutoReplyUpdatedAt = now;
            lastSuccessfulReplyAt = now;
        }

        private synchronized Snapshot snapshot() {
            return new Snapshot(
                    webSocketMessageCount,
                    lastWebSocketMessageAt,
                    lastCustomerMessageAt,
                    lastAutoReplyStatus,
                    lastAutoReplyReason,
                    lastAutoReplyUpdatedAt,
                    lastSuccessfulReplyAt);
        }
    }

    public static final class Snapshot {
        private final long webSocketMessageCount;
        private final Long lastWebSocketMessageAt;
        private final Long lastCustomerMessageAt;
        private final String lastAutoReplyStatus;
        private final String lastAutoReplyReason;
        private final Long lastAutoReplyUpdatedAt;
        private final Long lastSuccessfulReplyAt;

        private Snapshot(
                long webSocketMessageCount,
                Long lastWebSocketMessageAt,
                Long lastCustomerMessageAt,
                String lastAutoReplyStatus,
                String lastAutoReplyReason,
                Long lastAutoReplyUpdatedAt,
                Long lastSuccessfulReplyAt) {
            this.webSocketMessageCount = webSocketMessageCount;
            this.lastWebSocketMessageAt = lastWebSocketMessageAt;
            this.lastCustomerMessageAt = lastCustomerMessageAt;
            this.lastAutoReplyStatus = lastAutoReplyStatus;
            this.lastAutoReplyReason = lastAutoReplyReason;
            this.lastAutoReplyUpdatedAt = lastAutoReplyUpdatedAt;
            this.lastSuccessfulReplyAt = lastSuccessfulReplyAt;
        }

        private static Snapshot empty() {
            return new Snapshot(0, null, null, "IDLE", "NO_ACTIVITY", null, null);
        }

        public long getWebSocketMessageCount() { return webSocketMessageCount; }
        public Long getLastWebSocketMessageAt() { return lastWebSocketMessageAt; }
        public Long getLastCustomerMessageAt() { return lastCustomerMessageAt; }
        public String getLastAutoReplyStatus() { return lastAutoReplyStatus; }
        public String getLastAutoReplyReason() { return lastAutoReplyReason; }
        public Long getLastAutoReplyUpdatedAt() { return lastAutoReplyUpdatedAt; }
        public Long getLastSuccessfulReplyAt() { return lastSuccessfulReplyAt; }
    }
}
