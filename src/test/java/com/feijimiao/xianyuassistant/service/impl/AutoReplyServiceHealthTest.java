package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.service.AutomationHealthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AutoReplyServiceHealthTest {

    @Mock private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock private AutomationHealthService healthService;

    @Test
    void reportsExecutionAndDisabledDecision() {
        AutoReplyServiceImpl service = new AutoReplyServiceImpl();
        ReflectionTestUtils.setField(service, "goodsConfigMapper", goodsConfigMapper);
        ReflectionTestUtils.setField(service, "automationHealthService", healthService);
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(3L);
        message.setXyGoodsId("goods-9");
        message.setSId("session-7@goofish");
        message.setMsgContent("hello");

        service.executeAutoReply(List.of(message));

        InOrder order = inOrder(healthService);
        order.verify(healthService).recordReplyState(3L, "RUNNING", "GENERATING_REPLY");
        order.verify(healthService).recordReplyState(3L, "SKIPPED", "REPLY_DISABLED");
    }

    @Test
    void textAutoReplyWaitsForTheServerAcknowledgementInsteadOfSocketWriteOnly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/feijimiao/xianyuassistant/service/impl/AutoReplyServiceImpl.java"));

        assertThat(source).contains(
                "webSocketService.sendMessageWithOutcome(accountId, cid, toId, item.getTextContent())");
        assertThat(source).doesNotContain(
                "webSocketService.sendMessage(accountId, cid, toId, item.getTextContent())");
    }
}
