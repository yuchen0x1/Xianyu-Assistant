package com.feijimiao.xianyuassistant.notify;

import com.feijimiao.xianyuassistant.notify.impl.BarkNotifier;
import com.feijimiao.xianyuassistant.notify.impl.DingTalkNotifier;
import com.feijimiao.xianyuassistant.notify.impl.NtfyNotifier;
import com.feijimiao.xianyuassistant.notify.impl.ServerChanNotifier;
import com.feijimiao.xianyuassistant.notify.impl.TelegramNotifier;
import com.feijimiao.xianyuassistant.notify.impl.WeComNotifier;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotifyServiceChannelTest {

    @Mock
    private EmailNotifyService emailNotifyService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private WeComNotifier weComNotifier;
    @Mock
    private DingTalkNotifier dingTalkNotifier;

    private NotifyService notifyService;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        notifyService = new NotifyService();
        try {
            var field = NotifyService.class.getDeclaredField("emailNotifyService");
            field.setAccessible(true);
            field.set(notifyService, emailNotifyService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            var field = NotifyService.class.getDeclaredField("notificationService");
            field.setAccessible(true);
            field.set(notifyService, notificationService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            var field = NotifyService.class.getDeclaredField("weComNotifier");
            field.setAccessible(true);
            field.set(notifyService, weComNotifier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            var field = NotifyService.class.getDeclaredField("dingTalkNotifier");
            field.setAccessible(true);
            field.set(notifyService, dingTalkNotifier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        notifyService.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) mocks.close();
    }

    @Test
    void oneChannelFailureDoesNotBlockOtherChannels() {
        when(weComNotifier.isConfigured()).thenReturn(true);
        when(dingTalkNotifier.isConfigured()).thenReturn(true);
        when(weComNotifier.send(any(NotifyMessage.class))).thenReturn(false);
        when(dingTalkNotifier.send(any(NotifyMessage.class))).thenReturn(true);
        doNothing().when(notificationService).createBroadcastNotification(any(), any(), any(), any());

        assertThat(notifyService.send(new NotifyMessage("t", "c", NotifyMessage.NotifyLevel.WARNING)))
                .isEqualTo(NotifyService.SendResult.SENT);

        verify(weComNotifier).send(any(NotifyMessage.class));
        verify(dingTalkNotifier).send(any(NotifyMessage.class));
    }

    @Test
    void allChannelsFailuresReportedAsSendFailure() {
        when(weComNotifier.isConfigured()).thenReturn(true);
        when(dingTalkNotifier.isConfigured()).thenReturn(true);
        when(weComNotifier.send(any(NotifyMessage.class))).thenReturn(false);
        when(dingTalkNotifier.send(any(NotifyMessage.class))).thenReturn(false);
        doNothing().when(notificationService).createBroadcastNotification(any(), any(), any(), any());

        assertThat(notifyService.send(new NotifyMessage("t", "c", NotifyMessage.NotifyLevel.ERROR)))
                .isEqualTo(NotifyService.SendResult.FAILED);
    }

    @Test
    void inAppNotificationPersistedBeforeChannels() {
        when(weComNotifier.isConfigured()).thenReturn(true);
        when(weComNotifier.send(any(NotifyMessage.class))).thenReturn(true);
        doNothing().when(notificationService).createBroadcastNotification(any(), any(), any(), any());

        notifyService.send(new NotifyMessage("t", "c", NotifyMessage.NotifyLevel.INFO));

        verify(notificationService).createBroadcastNotification("t", "c", "system", "INFO");
        verify(weComNotifier).send(any(NotifyMessage.class));
    }

    @Test
    void unconfiguredChannelIsSkippedSilently() {
        when(weComNotifier.isConfigured()).thenReturn(false);
        when(dingTalkNotifier.isConfigured()).thenReturn(false);
        doNothing().when(notificationService).createBroadcastNotification(any(), any(), any(), any());

        assertThat(notifyService.send(new NotifyMessage("t", "c", NotifyMessage.NotifyLevel.INFO)))
                .isEqualTo(NotifyService.SendResult.NO_CHANNEL);

        verify(weComNotifier, never()).send(any());
        verify(dingTalkNotifier, never()).send(any());
    }
}