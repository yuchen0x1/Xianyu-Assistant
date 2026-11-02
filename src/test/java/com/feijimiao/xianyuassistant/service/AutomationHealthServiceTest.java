package com.feijimiao.xianyuassistant.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationHealthServiceTest {

    @Test
    void tracksMessageAndReplyLifecycleWithoutStoringMessageContent() {
        AutomationHealthService service = new AutomationHealthService();

        service.recordWebSocketMessages(3L, 2);
        service.recordCustomerMessage(3L);
        service.recordReplyState(3L, "QUEUED", "DELAY_PENDING");
        service.recordReplySuccess(3L);

        AutomationHealthService.Snapshot snapshot = service.getSnapshot(3L);
        assertThat(snapshot.getWebSocketMessageCount()).isEqualTo(2L);
        assertThat(snapshot.getLastWebSocketMessageAt()).isPositive();
        assertThat(snapshot.getLastCustomerMessageAt()).isPositive();
        assertThat(snapshot.getLastAutoReplyStatus()).isEqualTo("SUCCESS");
        assertThat(snapshot.getLastAutoReplyReason()).isEqualTo("SENT");
        assertThat(snapshot.getLastAutoReplyUpdatedAt()).isPositive();
        assertThat(snapshot.getLastSuccessfulReplyAt()).isPositive();
        assertThat(AutomationHealthService.Snapshot.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("message", "content", "cookie", "senderUserId");
    }

    @Test
    void returnsAnEmptySnapshotForAnAccountWithNoRuntimeActivity() {
        AutomationHealthService.Snapshot snapshot =
                new AutomationHealthService().getSnapshot(99L);

        assertThat(snapshot.getWebSocketMessageCount()).isZero();
        assertThat(snapshot.getLastWebSocketMessageAt()).isNull();
        assertThat(snapshot.getLastCustomerMessageAt()).isNull();
        assertThat(snapshot.getLastAutoReplyStatus()).isEqualTo("IDLE");
        assertThat(snapshot.getLastAutoReplyReason()).isEqualTo("NO_ACTIVITY");
    }
}
