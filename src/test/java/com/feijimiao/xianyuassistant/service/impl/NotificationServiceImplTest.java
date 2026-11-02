package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import com.feijimiao.xianyuassistant.mapper.XianyuNotificationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private XianyuNotificationMapper notificationMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private XianyuNotification sampleNotification;

    @BeforeEach
    void setUp() {
        sampleNotification = new XianyuNotification();
        sampleNotification.setId(1L);
        sampleNotification.setTitle("测试通知");
        sampleNotification.setContent("测试内容");
        sampleNotification.setNotifyType("system");
        sampleNotification.setLevel("ERROR");
        sampleNotification.setReadStatus(0);
        sampleNotification.setCreatedTime("2026-08-02 10:00:00");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createNotificationPersistsAsBroadcastForCompatibility() {
        when(notificationMapper.insert(any(XianyuNotification.class))).thenReturn(1);

        notificationService.createNotification("标题", "内容", "ws_disconnect", "WARNING");

        var captor = org.mockito.ArgumentCaptor.forClass(XianyuNotification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getBroadcast()).isEqualTo(1);
        assertThat(captor.getValue().getRecipientUserId()).isNull();
    }

    @Test
    void createNotificationForCurrentUserUsesUserContext() {
        UserContext.set(42L, "tester");
        when(notificationMapper.insert(any(XianyuNotification.class))).thenReturn(1);

        notificationService.createNotificationForCurrentUser("标题", "内容", "system", null, 7L);

        var captor = org.mockito.ArgumentCaptor.forClass(XianyuNotification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getRecipientUserId()).isEqualTo(42L);
        assertThat(captor.getValue().getAccountId()).isEqualTo(7L);
        assertThat(captor.getValue().getBroadcast()).isEqualTo(0);
        assertThat(captor.getValue().getLevel()).isEqualTo("INFO");
    }

    @Test
    void createNotificationForCurrentUserDoesNotPersistWithoutUserContext() {
        notificationService.createNotificationForCurrentUser("标题", "内容", "system", "INFO");

        verifyNoInteractions(notificationMapper);
    }

    @Test
    void createNotificationDoesNotThrowOnDbFailure() {
        when(notificationMapper.insert(any(XianyuNotification.class))).thenThrow(new RuntimeException("db error"));

        notificationService.createNotification("标题", "内容", "system", "INFO");
    }

    @Test
    void getRecentNotificationsClampsLimitAndScopesUser() {
        UserContext.set(42L, "tester");
        when(notificationMapper.selectRecent(42L, 1)).thenReturn(List.of(sampleNotification));

        List<XianyuNotification> result = notificationService.getRecentNotifications(0);
        assertThat(result).hasSize(1);
        verify(notificationMapper).selectRecent(42L, 1);

        notificationService.getRecentNotifications(500);
        verify(notificationMapper).selectRecent(42L, 100);
    }

    @Test
    void countUnreadScopesCurrentUser() {
        UserContext.set(42L, "tester");
        when(notificationMapper.countUnread(42L)).thenReturn(3L);

        assertThat(notificationService.countUnread()).isEqualTo(3L);
        verify(notificationMapper).countUnread(42L);
    }

    @Test
    void markReadScopesCurrentUserAndDoesNotUseUnscopedByIdUpdate() {
        UserContext.set(42L, "tester");
        when(notificationMapper.selectById(1L)).thenReturn(sampleNotification);
        when(notificationMapper.markBroadcastRead(1L, 42L)).thenReturn(1);

        assertThat(notificationService.markRead(1L)).isTrue();
        verify(notificationMapper).markBroadcastRead(1L, 42L);
    }

    @Test
    void markReadReturnsFalseWhenNotificationBelongsToAnotherUser() {
        UserContext.set(42L, "tester");
        sampleNotification.setBroadcast(0);
        sampleNotification.setRecipientUserId(99L);
        when(notificationMapper.selectById(1L)).thenReturn(sampleNotification);

        assertThat(notificationService.markRead(1L)).isFalse();
        verify(notificationMapper, never()).markBroadcastRead(1L, 42L);
        verify(notificationMapper, never()).markDirectRead(1L, 42L);
    }

    @Test
    void markReadReturnsTrueWhenBroadcastReceiptAlreadyExists() {
        UserContext.set(42L, "tester");
        sampleNotification.setBroadcast(1);
        when(notificationMapper.selectById(1L)).thenReturn(sampleNotification);
        when(notificationMapper.markBroadcastRead(1L, 42L)).thenReturn(0);
        when(notificationMapper.hasReadReceipt(1L, 42L)).thenReturn(true);

        assertThat(notificationService.markRead(1L)).isTrue();
    }

    @Test
    void markReadReturnsFalseForNullIdOrMissingUser() {
        assertThat(notificationService.markRead(null)).isFalse();
        assertThat(notificationService.markRead(1L)).isFalse();
    }

    @Test
    void markAllReadScopesCurrentUser() {
        UserContext.set(42L, "tester");
        when(notificationMapper.markAllBroadcastRead(42L)).thenReturn(3);
        when(notificationMapper.markAllDirectRead(42L)).thenReturn(2);

        assertThat(notificationService.markAllRead()).isEqualTo(5);
        verify(notificationMapper).markAllBroadcastRead(42L);
        verify(notificationMapper).markAllDirectRead(42L);
    }
}
