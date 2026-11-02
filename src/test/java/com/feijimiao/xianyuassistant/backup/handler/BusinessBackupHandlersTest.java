package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import com.feijimiao.xianyuassistant.entity.XianyuPublishHistory;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.mapper.JobDeadLetterMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobDefinitionMapper;
import com.feijimiao.xianyuassistant.job.mapper.JobExecutionMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuNotificationMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuPublishHistoryMapper;
import com.feijimiao.xianyuassistant.monitor.entity.AlertEvent;
import com.feijimiao.xianyuassistant.monitor.mapper.AlertEventMapper;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookConfig;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 核心业务备份 handler 的导出脱敏与导入幂等测试：
 * 订单、聊天记录、站内通知、任务/监控、发布历史。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class BusinessBackupHandlersTest {

    @Mock private XianyuGoodsOrderMapper goodsOrderMapper;
    @Mock private XianyuChatMessageMapper chatMessageMapper;
    @Mock private XianyuNotificationMapper notificationMapper;
    @Mock private XianyuPublishHistoryMapper publishHistoryMapper;
    @Mock private JobDefinitionMapper jobDefinitionMapper;
    @Mock private JobExecutionMapper jobExecutionMapper;
    @Mock private JobDeadLetterMapper jobDeadLetterMapper;
    @Mock private WebhookConfigMapper webhookConfigMapper;
    @Mock private AlertEventMapper alertEventMapper;
    @Mock private XianyuAccountMapper accountMapper;

    private XianyuAccount account(Long id, String unb) {
        XianyuAccount a = new XianyuAccount();
        a.setId(id);
        a.setUnb(unb);
        return a;
    }

    private Map<String, Object> contextWithAccount() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("123456", 7L));
        return context;
    }

    @BeforeEach
    void setUp() {
        when(accountMapper.selectById(7L)).thenReturn(account(7L, "123456"));
        when(accountMapper.selectList(null)).thenReturn(List.of(account(7L, "123456")));
    }

    // ==================== orders ====================

    @Test
    void ordersExportResolvesUnbAndImportCreatesOrder() {
        OrdersBackupHandler handler = new OrdersBackupHandler();
        ReflectionTestUtils.setField(handler, "goodsOrderMapper", goodsOrderMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setXianyuAccountId(7L);
        order.setOrderId("O1");
        order.setXyGoodsId("g-1");
        order.setBuyerUserName("买家甲");
        order.setState(2);
        when(goodsOrderMapper.selectByAccountId(7L)).thenReturn(List.of(order));
        when(goodsOrderMapper.selectByAccountIdAndOrderId(7L, "O1")).thenReturn(null);

        Map<String, Object> exported = handler.exportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) exported.get("orders");
        assertEquals("123456", orders.get(0).get("unb"));
        assertEquals("O1", orders.get(0).get("orderId"));

        handler.importData(exported, contextWithAccount());

        ArgumentCaptor<XianyuGoodsOrder> captor = ArgumentCaptor.forClass(XianyuGoodsOrder.class);
        verify(goodsOrderMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getXianyuAccountId());
        assertEquals("O1", captor.getValue().getOrderId());
    }

    @Test
    void ordersImportUpdatesExistingRecordStateOnSameOrder() {
        OrdersBackupHandler handler = new OrdersBackupHandler();
        ReflectionTestUtils.setField(handler, "goodsOrderMapper", goodsOrderMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        XianyuGoodsOrder existing = new XianyuGoodsOrder();
        existing.setId(9L);
        when(goodsOrderMapper.selectByAccountIdAndOrderId(7L, "O1")).thenReturn(existing);

        Map<String, Object> data = Map.of("orders", List.of(Map.of(
                "unb", "123456", "orderId", "O1", "state", 3, "failReason", "风控")));

        handler.importData(data, contextWithAccount());

        verify(goodsOrderMapper).updateStateContentAndFailReason(9L, 3, null, "风控");
    }

    // ==================== chatLogs ====================

    @Test
    void chatLogsExportRedactsBuyerIdentityAndFullBody() {
        ChatLogsBackupHandler handler = new ChatLogsBackupHandler();
        ReflectionTestUtils.setField(handler, "chatMessageMapper", chatMessageMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        XianyuChatMessage message = new XianyuChatMessage();
        message.setId(1L);
        message.setXianyuAccountId(7L);
        message.setPnmId("P1");
        message.setSId("S1");
        message.setMsgContent("在吗");
        message.setSenderUserName("买家甲");
        message.setSenderUserId("buyer-1");
        message.setReminderUrl("https://goofish.example/item?id=9");
        message.setCompleteMsg("{\"secret\":true}");
        message.setMessageTime(1700000000000L);
        // 导出改为按主键游标翻页，第二批返回空表示读完
        when(chatMessageMapper.selectBatchAfterId(0L, 500)).thenReturn(List.of(message));
        when(chatMessageMapper.selectBatchAfterId(1L, 500)).thenReturn(List.of());

        Map<String, Object> exported = handler.exportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) exported.get("messages");
        Map<String, Object> first = messages.get(0);
        assertEquals("123456", first.get("unb"));
        assertEquals("在吗", first.get("msgContent"));
        // 脱敏字段不落备份：完整消息体、链接、买家用户ID
        assertNull(first.get("completeMsg"));
        assertNull(first.get("reminderUrl"));
        assertNull(first.get("senderUserId"));
    }

    /**
     * 两页数据、只查一次账号表、流式输出必须是完整合法的 JSON。
     *
     * <p>这三条一起守的是"大账号导出不会把堆吃满"：旧的实现每条消息一次
     * `selectById`（N+1），并且整表读进内存。</p>
     */
    @Test
    void chatLogsExportPagesByCursorAndLooksUpAccountTableOnce() throws Exception {
        ChatLogsBackupHandler handler = new ChatLogsBackupHandler();
        ReflectionTestUtils.setField(handler, "chatMessageMapper", chatMessageMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        XianyuChatMessage first = new XianyuChatMessage();
        first.setId(10L);
        first.setXianyuAccountId(7L);
        first.setPnmId("P1");
        first.setMsgContent("在吗");
        XianyuChatMessage second = new XianyuChatMessage();
        second.setId(20L);
        second.setXianyuAccountId(7L);
        second.setPnmId("P2");
        second.setMsgContent("在的");

        when(chatMessageMapper.selectBatchAfterId(0L, 500)).thenReturn(List.of(first));
        when(chatMessageMapper.selectBatchAfterId(10L, 500)).thenReturn(List.of(second));
        when(chatMessageMapper.selectBatchAfterId(20L, 500)).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectMapper mapper = new ObjectMapper();
        try (com.fasterxml.jackson.core.JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            handler.exportJson(generator, Map.of());
        }

        Map<String, Object> parsed = mapper.readValue(out.toString(StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) parsed.get("messages");
        assertEquals(2, messages.size());
        assertEquals("P1", messages.get(0).get("pnmId"));
        assertEquals("P2", messages.get(1).get("pnmId"));

        verify(accountMapper, times(1)).selectList(null);
        verify(accountMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
        verify(chatMessageMapper, never()).findByAccountId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void chatLogsImportInsertsNewMessageAndSkipsDuplicate() {
        ChatLogsBackupHandler handler = new ChatLogsBackupHandler();
        ReflectionTestUtils.setField(handler, "chatMessageMapper", chatMessageMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        Map<String, Object> data = Map.of("messages", List.of(Map.of(
                "unb", "123456", "pnmId", "P1", "sId", "S1",
                "msgContent", "在吗", "messageTime", 1700000000000L)));
        when(chatMessageMapper.findByPnmId(7L, "P1")).thenReturn(null);
        handler.importData(data, contextWithAccount());
        verify(chatMessageMapper).insert(any(XianyuChatMessage.class));

        when(chatMessageMapper.findByPnmId(7L, "P1")).thenReturn(new XianyuChatMessage());
        handler.importData(data, contextWithAccount());
        // 幂等：重复导入不新增记录
        verify(chatMessageMapper, org.mockito.Mockito.times(1)).insert(any(XianyuChatMessage.class));
    }

    // ==================== notifications ====================

    @Test
    void notificationsImportAppendsHistoricalBroadcastWithAccountMapped() {
        NotificationsBackupHandler handler = new NotificationsBackupHandler();
        ReflectionTestUtils.setField(handler, "notificationMapper", notificationMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        Map<String, Object> data = Map.of("notifications", List.of(Map.of(
                "unb", "123456", "title", "连接断开", "notifyType", "ws_disconnect",
                "broadcast", 1, "readStatus", 0)));

        handler.importData(data, contextWithAccount());

        ArgumentCaptor<XianyuNotification> captor = ArgumentCaptor.forClass(XianyuNotification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getAccountId());
        assertEquals("连接断开", captor.getValue().getTitle());
    }

    @Test
    void notificationsExportHandlesNullAccountAsBroadcast() {
        NotificationsBackupHandler handler = new NotificationsBackupHandler();
        ReflectionTestUtils.setField(handler, "notificationMapper", notificationMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        XianyuNotification notification = new XianyuNotification();
        notification.setAccountId(null);
        notification.setTitle("系统升级");
        when(notificationMapper.selectList(null)).thenReturn(List.of(notification));

        Map<String, Object> exported = handler.exportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) exported.get("notifications");
        assertNull(notifications.get(0).get("unb"));
    }

    // ==================== publishHistory ====================

    @Test
    void publishHistoryExportAndImportRoundTrip() {
        PublishHistoryBackupHandler handler = new PublishHistoryBackupHandler();
        ReflectionTestUtils.setField(handler, "publishHistoryMapper", publishHistoryMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        XianyuPublishHistory record = new XianyuPublishHistory();
        record.setId(3L);
        record.setAccountId(7L);
        record.setTitle("测试商品");
        record.setStatus("SUCCESS");
        when(publishHistoryMapper.selectList(null)).thenReturn(List.of(record));
        when(publishHistoryMapper.selectById(3L)).thenReturn(null);

        Map<String, Object> exported = handler.exportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) exported.get("publishRecords");
        assertEquals("123456", records.get(0).get("unb"));
        assertEquals("SUCCESS", records.get(0).get("status"));

        handler.importData(exported, contextWithAccount());

        ArgumentCaptor<XianyuPublishHistory> captor = ArgumentCaptor.forClass(XianyuPublishHistory.class);
        verify(publishHistoryMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getAccountId());
    }

    // ==================== jobs & monitoring ====================

    @Test
    void jobsExportNeverIncludesWebhookSecretOrExecutionsMissingKeys() {
        JobsBackupHandler handler = new JobsBackupHandler();
        ReflectionTestUtils.setField(handler, "jobDefinitionMapper", jobDefinitionMapper);
        ReflectionTestUtils.setField(handler, "jobExecutionMapper", jobExecutionMapper);
        ReflectionTestUtils.setField(handler, "jobDeadLetterMapper", jobDeadLetterMapper);
        ReflectionTestUtils.setField(handler, "webhookConfigMapper", webhookConfigMapper);
        ReflectionTestUtils.setField(handler, "alertEventMapper", alertEventMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        WebhookConfig webhook = new WebhookConfig();
        webhook.setId(1L);
        webhook.setName("钉钉");
        webhook.setUrl("https://93.184.216.34/hook");
        webhook.setSecret("super-secret");
        when(webhookConfigMapper.selectList(null)).thenReturn(List.of(webhook));
        when(jobDefinitionMapper.selectList(null)).thenReturn(List.of());
        when(jobExecutionMapper.selectList(null)).thenReturn(List.of());
        when(jobDeadLetterMapper.selectList(null)).thenReturn(List.of());
        when(alertEventMapper.selectList(null)).thenReturn(List.of());

        Map<String, Object> exported = handler.exportData();
        String json = exported.toString();
        assertFalse(json.contains("super-secret"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> webhooks = (List<Map<String, Object>>) exported.get("webhooks");
        assertEquals("钉钉", webhooks.get(0).get("name"));
        assertFalse(webhooks.get(0).containsKey("secret"));
        assertFalse(webhooks.get(0).containsKey("hasSecret"));
    }

    @Test
    void jobsImportCreatesJobDefinitionAndWebhook() {
        JobsBackupHandler handler = new JobsBackupHandler();
        ReflectionTestUtils.setField(handler, "jobDefinitionMapper", jobDefinitionMapper);
        ReflectionTestUtils.setField(handler, "jobExecutionMapper", jobExecutionMapper);
        ReflectionTestUtils.setField(handler, "jobDeadLetterMapper", jobDeadLetterMapper);
        ReflectionTestUtils.setField(handler, "webhookConfigMapper", webhookConfigMapper);
        ReflectionTestUtils.setField(handler, "alertEventMapper", alertEventMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        Map<String, Object> data = Map.of(
                "jobDefinitions", List.of(Map.of(
                        "unb", "123456", "jobType", "DELIVERY_REVIEW",
                        "idempotencyKey", "review:O1", "status", "PENDING")),
                "jobExecutions", List.of(),
                "deadLetters", List.of(),
                "webhooks", List.of(Map.of(
                        "name", "钉钉", "url", "https://93.184.216.34/hook",
                        "enabled", 1, "retryCount", 3)),
                "alerts", List.of());
        when(jobDefinitionMapper.selectOne(any())).thenReturn(null);
        when(webhookConfigMapper.selectOne(any())).thenReturn(null);

        handler.importData(data, contextWithAccount());

        ArgumentCaptor<JobDefinition> jobCaptor = ArgumentCaptor.forClass(JobDefinition.class);
        verify(jobDefinitionMapper).insert(jobCaptor.capture());
        assertEquals(7L, jobCaptor.getValue().getAccountId());
        assertEquals("review:O1", jobCaptor.getValue().getIdempotencyKey());

        ArgumentCaptor<WebhookConfig> webhookCaptor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(webhookConfigMapper).insert(webhookCaptor.capture());
        assertEquals("钉钉", webhookCaptor.getValue().getName());
        assertNull(webhookCaptor.getValue().getSecret());
    }

    @Test
    void jobsExportIncludesAlertStatusForMonitoringRecovery() {
        JobsBackupHandler handler = new JobsBackupHandler();
        ReflectionTestUtils.setField(handler, "jobDefinitionMapper", jobDefinitionMapper);
        ReflectionTestUtils.setField(handler, "jobExecutionMapper", jobExecutionMapper);
        ReflectionTestUtils.setField(handler, "jobDeadLetterMapper", jobDeadLetterMapper);
        ReflectionTestUtils.setField(handler, "webhookConfigMapper", webhookConfigMapper);
        ReflectionTestUtils.setField(handler, "alertEventMapper", alertEventMapper);
        ReflectionTestUtils.setField(handler, "accountMapper", accountMapper);

        AlertEvent alert = new AlertEvent();
        alert.setAccountId(7L);
        alert.setAlertKey("connection_lost_7");
        alert.setStatus("ACTIVE");
        alert.setOccurrences(3);
        when(jobDefinitionMapper.selectList(null)).thenReturn(List.of());
        when(jobExecutionMapper.selectList(null)).thenReturn(List.of());
        when(jobDeadLetterMapper.selectList(null)).thenReturn(List.of());
        when(webhookConfigMapper.selectList(null)).thenReturn(List.of());
        when(alertEventMapper.selectList(null)).thenReturn(List.of(alert));

        Map<String, Object> exported = handler.exportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) exported.get("alerts");
        assertEquals("123456", alerts.get(0).get("unb"));
        assertEquals("ACTIVE", alerts.get(0).get("status"));
        assertTrue(exported.containsKey("jobExecutions"));
    }
}