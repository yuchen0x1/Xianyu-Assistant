package com.feijimiao.xianyuassistant.event.chatMessageEvent.lister;

import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.AutoReplyDelayService;
import com.feijimiao.xianyuassistant.service.AutoReplyService;
import com.feijimiao.xianyuassistant.service.AutomationHealthService;
import com.feijimiao.xianyuassistant.service.reply.HumanTakeoverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageEventAutoReplyListenerHealthTest {

    @Mock private AutoReplyDelayService delayService;
    @Mock private AccountService accountService;
    @Mock private AutoReplyService autoReplyService;
    @Mock private HumanTakeoverManager takeoverManager;
    @Mock private AutomationHealthService healthService;

    private ChatMessageEventAutoReplyListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatMessageEventAutoReplyListener();
        ReflectionTestUtils.setField(listener, "autoReplyDelayService", delayService);
        ReflectionTestUtils.setField(listener, "accountService", accountService);
        ReflectionTestUtils.setField(listener, "autoReplyService", autoReplyService);
        ReflectionTestUtils.setField(listener, "takeoverManager", takeoverManager);
        ReflectionTestUtils.setField(listener, "automationHealthService", healthService);
    }

    @Test
    void validCustomerMessageIsVisibleBeforeItEntersTheDelayQueue() {
        ChatMessageData message = customerMessage();
        when(accountService.getXianyuUserId(3L)).thenReturn("seller-3");
        when(takeoverManager.isTakenOver(3L, "session-7@goofish")).thenReturn(false);
        when(autoReplyService.isAutoReplyEnabled(3L, "goods-9")).thenReturn(true);

        listener.handleChatMessageReceived(new ChatMessageReceivedEvent(this, message));

        InOrder order = inOrder(healthService, delayService);
        order.verify(healthService).recordCustomerMessage(3L);
        order.verify(healthService).recordReplyState(3L, "QUEUED", "DELAY_PENDING");
        order.verify(delayService).submitDelayTask(message);
    }

    @Test
    void missingConversationContextIsReportedInsteadOfSilentlyDisappearing() {
        ChatMessageData message = customerMessage();
        message.setXyGoodsId(null);
        when(accountService.getXianyuUserId(3L)).thenReturn("seller-3");

        listener.handleChatMessageReceived(new ChatMessageReceivedEvent(this, message));

        verify(healthService).recordReplyState(3L, "SKIPPED", "MISSING_CONTEXT");
        verifyNoInteractions(delayService);
    }

    private ChatMessageData customerMessage() {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(3L);
        message.setPnmId("pnm-1");
        message.setContentType(1);
        message.setSenderUserId("buyer-7");
        message.setMsgContent("hello");
        message.setXyGoodsId("goods-9");
        message.setSId("session-7@goofish");
        return message;
    }
}
