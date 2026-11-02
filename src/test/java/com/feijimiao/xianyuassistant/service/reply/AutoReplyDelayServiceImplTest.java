package com.feijimiao.xianyuassistant.service.reply;

import com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData;
import com.feijimiao.xianyuassistant.job.config.JobWorkerRegistry;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.service.JobEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoReplyDelayServiceImplTest {

    @Mock
    private JobEngine jobEngine;
    @Mock
    private HumanTakeoverManager takeoverManager;
    @Mock
    private ReplyConfigProvider configProvider;

    private AutoReplyDelayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutoReplyDelayServiceImpl();
        ReflectionTestUtils.setField(service, "jobEngine", jobEngine);
        ReflectionTestUtils.setField(service, "takeoverManager", takeoverManager);
        ReflectionTestUtils.setField(service, "configProvider", configProvider);
    }

    private ChatMessageData message(Long accountId, String sId, String content) {
        ChatMessageData data = new ChatMessageData();
        data.setXianyuAccountId(accountId);
        data.setSId(sId);
        data.setMsgContent(content);
        return data;
    }

    @Test
    void submitsPersistentJobWithMessagesAndDelay() {
        when(takeoverManager.isTakenOver(1L, "s-1")).thenReturn(false);
        when(configProvider.getDelaySeconds(1L, null)).thenReturn(15);

        service.submitDelayTask(message(1L, "s-1", "你好"));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jobEngine).createJob(
                eq(JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY),
                eq(JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY + ":1:s-1"),
                paramsCaptor.capture(),
                eq(1L),
                any(),
                eq(3),
                eq(0),
                eq(true));
        assertThat(paramsCaptor.getValue()).containsKey("sId");
        assertThat(paramsCaptor.getValue().get("sId")).isEqualTo("s-1");
    }

    @Test
    void consecutiveMessagesAppendToSamePersistentJob() {
        when(takeoverManager.isTakenOver(1L, "s-1")).thenReturn(false);
        when(configProvider.getDelaySeconds(1L, null)).thenReturn(15);

        service.submitDelayTask(message(1L, "s-1", "第一条"));
        service.submitDelayTask(message(1L, "s-1", "第二条"));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jobEngine, times(2)).createJob(
                eq(JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY),
                eq(JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY + ":1:s-1"),
                paramsCaptor.capture(),
                eq(1L),
                any(),
                eq(3),
                eq(0),
                eq(true));
        Map<String, Object> lastParams = paramsCaptor.getAllValues().get(1);
        assertThat(lastParams.get("messages")).asList().hasSize(2);
    }

    @Test
    void skipsSubmitWhenSessionTakenOver() {
        when(takeoverManager.isTakenOver(1L, "s-1")).thenReturn(true);

        service.submitDelayTask(message(1L, "s-1", "你好"));

        verify(jobEngine, never()).createJob(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void cancelRemovesPendingMessagesAndCancelsJob() {
        when(takeoverManager.isTakenOver(1L, "s-1")).thenReturn(false);
        when(configProvider.getDelaySeconds(1L, null)).thenReturn(15);
        service.submitDelayTask(message(1L, "s-1", "你好"));

        service.cancelDelayTask(1L, "s-1");

        verify(jobEngine).cancelJob(JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY + ":1:s-1");
        assertThat(service.getPendingTaskCount()).isZero();
    }

    @Test
    void manualReplyCancelsPersistentJobWhenInterventionEnabled() {
        when(configProvider.isHumanInterventionEnabled(1L, "g-1")).thenReturn(true);
        when(configProvider.getInterventionMinutes(1L, "g-1")).thenReturn(10);

        service.recordSellerManualReply(1L, "g-1", "s-1");

        verify(takeoverManager).takeover(1L, "s-1", 10);
        verify(jobEngine).cancelJob(JobWorkerRegistry.JOB_TYPE_AUTO_REPLY_DELAY + ":1:s-1");
    }

    @Test
    void paramsJsonRoundTripsMessagesForWorker() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<ChatMessageData> messages = java.util.List.of(message(1L, "s-1", "你好"));
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("sId", "s-1");
        params.put("messages", messages);

        String json = mapper.writeValueAsString(params);
        java.util.Map<String, Object> parsed = mapper.readValue(json, java.util.Map.class);

        @SuppressWarnings("unchecked")
        java.util.List<Object> parsedMessages = (java.util.List<Object>) parsed.get("messages");
        assertThat(parsedMessages).hasSize(1);
        com.feijimiao.xianyuassistant.event.chatMessageEvent.ChatMessageData restored =
                mapper.convertValue(parsedMessages.get(0), ChatMessageData.class);
        assertThat(restored.getMsgContent()).isEqualTo("你好");
        assertThat(restored.getSId()).isEqualTo("s-1");
    }
}
