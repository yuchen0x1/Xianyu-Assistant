package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoReplyRecord;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.service.AutomationHealthService;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoReplyManualConfirmTest {

    @Mock private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock private WebSocketService webSocketService;
    @Mock private AutomationHealthService automationHealthService;
    @Mock private com.feijimiao.xianyuassistant.service.SentMessageSaveService sentMessageSaveService;

    @Mock private com.feijimiao.xianyuassistant.rbac.service.RbacService rbacService;

    private AutoReplyServiceImpl buildService() {
        AutoReplyServiceImpl service = new AutoReplyServiceImpl();
        ReflectionTestUtils.setField(service, "autoReplyRecordMapper", autoReplyRecordMapper);
        ReflectionTestUtils.setField(service, "rbacService", rbacService);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "automationHealthService", automationHealthService);
        ReflectionTestUtils.setField(service, "sentMessageSaveService", sentMessageSaveService);
        return service;
    }

    @Test
    void confirmAndSendReplySendsEditedContentAndMarksSuccess() {
        XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
        record.setId(10L);
        record.setXianyuAccountId(3L);
        record.setSId("session-9@goofish");
        record.setXyGoodsId("goods-1");
        record.setState(2);
        record.setReplyContent("AI生成的草稿");
        when(autoReplyRecordMapper.selectById(10L)).thenReturn(record);
        when(webSocketService.sendMessageWithOutcome(eq(3L), eq("session-9"), eq("session-9"), eq("人工编辑后的内容")))
                .thenReturn(MessageSendResult.SUCCESS);

        AutoReplyServiceImpl service = buildService();
        ResultObject<String> result = service.confirmAndSendReply(10L, "人工编辑后的内容");

        assertThat(result.getCode()).isEqualTo(200);
        verify(autoReplyRecordMapper).updateStateAndContent(eq(10L), eq(1), eq("人工编辑后的内容"));
        verify(automationHealthService).recordReplySuccess(3L);
        verify(sentMessageSaveService).saveAiAssistantReply(eq(3L), eq("session-9"), eq("session-9"), eq("人工编辑后的内容"), eq("goods-1"));
    }

    @Test
    void confirmAndSendReplyUsesAiDraftWhenContentEmpty() {
        XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
        record.setId(11L);
        record.setXianyuAccountId(4L);
        record.setSId("session-10@goofish");
        record.setXyGoodsId("goods-2");
        record.setState(2);
        record.setReplyContent("AI草稿原文");
        when(autoReplyRecordMapper.selectById(11L)).thenReturn(record);
        when(webSocketService.sendMessageWithOutcome(eq(4L), eq("session-10"), eq("session-10"), eq("AI草稿原文")))
                .thenReturn(MessageSendResult.SUCCESS);

        AutoReplyServiceImpl service = buildService();
        ResultObject<String> result = service.confirmAndSendReply(11L, "   ");

        assertThat(result.getCode()).isEqualTo(200);
        verify(autoReplyRecordMapper).updateStateAndContent(eq(11L), eq(1), eq("AI草稿原文"));
    }

    @Test
    void confirmAndSendReplyRejectsNonPendingRecord() {
        XianyuGoodsAutoReplyRecord record = new XianyuGoodsAutoReplyRecord();
        record.setId(12L);
        record.setXianyuAccountId(5L);
        record.setSId("session-11@goofish");
        record.setState(1);
        when(autoReplyRecordMapper.selectById(12L)).thenReturn(record);

        AutoReplyServiceImpl service = buildService();
        ResultObject<String> result = service.confirmAndSendReply(12L, "内容");

        assertThat(result.getCode()).isEqualTo(500);
        verify(webSocketService, org.mockito.Mockito.never())
                .sendMessageWithOutcome(any(), anyString(), anyString(), anyString());
    }
}