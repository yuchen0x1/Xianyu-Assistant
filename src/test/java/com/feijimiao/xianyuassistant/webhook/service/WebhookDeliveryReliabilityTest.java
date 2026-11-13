package com.feijimiao.xianyuassistant.webhook.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookConfig;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookDelivery;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookConfigMapper;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookDeliveryMapper;
import okhttp3.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryReliabilityTest {
    private WebhookConfig hook() {
        WebhookConfig hook = new WebhookConfig();
        hook.setId(1L);
        hook.setUrl("https://93.184.216.34/hook");
        hook.setEnabled(1);
        hook.setRetryCount(3);
        return hook;
    }

    @Test
    void emptySubscriptionsMatchAllButMalformedSubscriptionsFailClosed() {
        WebhookService service = new WebhookService(mock(WebhookConfigMapper.class), mock(WebhookDeliveryMapper.class), new ObjectMapper());
        WebhookConfig hook = hook();
        for (String events : new String[]{null, "", "[]", "[ ]", "[\"*\"]", "[\"delivery.completed\"]"}) {
            hook.setEvents(events);
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "matchesEvent", hook, "delivery.completed")).isTrue();
        }
        for (String events : new String[]{"null", "broken delivery.completed", "[\"order.created\"]"}) {
            hook.setEvents(events);
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "matchesEvent", hook, "delivery.completed")).isFalse();
        }
    }

    @Test
    void longBusinessKeysKeepDistinctSuffixes() {
        WebhookService service = new WebhookService(mock(WebhookConfigMapper.class), mock(WebhookDeliveryMapper.class), new ObjectMapper());
        String first = ReflectionTestUtils.invokeMethod(service, "buildIdempotencyKey", 1L, "delivery.completed", Map.of("orderId", "a".repeat(150) + "1"));
        String second = ReflectionTestUtils.invokeMethod(service, "buildIdempotencyKey", 1L, "delivery.completed", Map.of("orderId", "a".repeat(150) + "2"));
        assertThat(first).hasSizeLessThanOrEqualTo(100).isNotEqualTo(second);
    }

    @Test
    void initialQueuedDeliveryCannotBeScheduledAgain() throws Exception {
        WebhookConfigMapper configs = mock(WebhookConfigMapper.class);
        WebhookDeliveryMapper deliveries = mock(WebhookDeliveryMapper.class);
        WebhookService service = new WebhookService(configs, deliveries, new ObjectMapper());
        ReflectionTestUtils.setField(service, "poolSize", 1);
        ReflectionTestUtils.setField(service, "queueCapacity", 5);
        service.init();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            when(configs.selectList(any())).thenReturn(List.of(hook()));
            when(deliveries.insert(any(WebhookDelivery.class))).thenAnswer(call -> {
                ((WebhookDelivery) call.getArgument(0)).setId(9L);
                return 1;
            });
            service.dispatch("delivery.completed", Map.of("orderId", "one"));
            WebhookDelivery stale = new WebhookDelivery();
            stale.setId(9L);
            stale.setWebhookId(1L);
            when(deliveries.selectList(any())).thenReturn(List.of(stale));
            service.redeliverFailedDeliveries();
            assertThat(executor.getQueue()).hasSize(1);
            verify(configs, never()).selectById(1L);
        } finally {
            executor.shutdownNow();
            release.countDown();
        }
    }

    @Test
    void sqliteRoundTripPreservesAttemptsClearsFailureAndDoesNotOverwriteEditedConfig() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", "jdbc:sqlite::memory:", null);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("webhook-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WebhookDeliveryMapper.class);
        configuration.addMapper(WebhookConfigMapper.class);
        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            try (var sql = session.getConnection().createStatement()) {
                sql.execute("CREATE TABLE webhook_delivery (id INTEGER PRIMARY KEY, webhook_id INTEGER, event TEXT, payload_json TEXT, status TEXT, http_status INTEGER, attempts INTEGER, next_attempt_at TIMESTAMP, last_error TEXT, idempotency_key TEXT, delivered_at TIMESTAMP, created_time TIMESTAMP)");
                sql.execute("CREATE TABLE webhook_config (id INTEGER PRIMARY KEY, url TEXT, enabled INTEGER, last_trigger_at TIMESTAMP, last_trigger_status TEXT, last_error TEXT, updated_time TIMESTAMP)");
                sql.execute("INSERT INTO webhook_config (id,url,enabled,last_error) VALUES (1,'https://new.example/hook',0,'old error')");
            }
            WebhookDeliveryMapper deliveries = session.getMapper(WebhookDeliveryMapper.class);
            WebhookService service = spy(new WebhookService(session.getMapper(WebhookConfigMapper.class), deliveries, new ObjectMapper()));
            WebhookDelivery record = new WebhookDelivery();
            record.setId(1L);
            record.setWebhookId(1L);
            record.setStatus("PENDING");
            record.setAttempts(0);
            record.setCreatedTime(LocalDateTime.now());
            deliveries.insert(record);

            OkHttpClient client = mock(OkHttpClient.class);
            Call call = mock(Call.class);
            Response failure = mock(Response.class);
            Response success = mock(Response.class);
            when(failure.code()).thenReturn(500);
            when(success.code()).thenReturn(204);
            when(client.newCall(any(Request.class))).thenReturn(call);
            when(call.execute()).thenReturn(failure, success);
            doReturn(client).when(service).clientFor(any(), anyInt());

            ReflectionTestUtils.invokeMethod(service, "sendWebhook", hook(), "delivery.completed", Map.of(), 1L);
            session.clearCache();
            WebhookDelivery failed = deliveries.selectById(1L);
            assertThat(failed.getAttempts()).isEqualTo(1);
            assertThat(failed.getNextAttemptAt()).isNotNull();
            ReflectionTestUtils.invokeMethod(service, "sendWebhook", hook(), "delivery.completed", Map.of(), 1L);
            session.clearCache();
            WebhookDelivery completed = deliveries.selectById(1L);
            assertThat(completed.getStatus()).isEqualTo("SUCCESS");
            assertThat(completed.getAttempts()).isEqualTo(2);
            assertThat(completed.getNextAttemptAt()).isNull();
            assertThat(completed.getLastError()).isNull();
            assertThat(completed.getDeliveredAt()).isNotNull();
            var requests = org.mockito.ArgumentCaptor.forClass(Request.class);
            verify(client, times(2)).newCall(requests.capture());
            assertThat(requests.getAllValues()).extracting(r -> r.header("X-Webhook-Attempt")).containsExactly("1", "2");
            assertThat(requests.getAllValues()).extracting(r -> r.header("X-Webhook-Id")).containsExactly("1", "1");
            // A stale scanner must not send an already successful record again.
            ReflectionTestUtils.invokeMethod(service, "sendWebhook", hook(), "delivery.completed", Map.of(), 1L);
            verify(client, times(2)).newCall(any(Request.class));
            try (var sql = session.getConnection().createStatement(); var result = sql.executeQuery("SELECT url,enabled,last_error FROM webhook_config WHERE id=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("url")).isEqualTo("https://new.example/hook");
                assertThat(result.getInt("enabled")).isZero();
                assertThat(result.getString("last_error")).isNull();
            }
        }
    }

    @Test
    void corruptedPayloadStopsWithoutInflatingAttempts() {
        WebhookConfigMapper configs = mock(WebhookConfigMapper.class);
        WebhookDeliveryMapper deliveries = mock(WebhookDeliveryMapper.class);
        WebhookService service = new WebhookService(configs, deliveries, new ObjectMapper());
        ExecutorService executor = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "executor", executor);
        WebhookDelivery record = new WebhookDelivery();
        record.setId(4L);
        record.setWebhookId(1L);
        record.setPayloadJson("broken json");
        record.setAttempts(2);
        when(deliveries.selectList(any())).thenReturn(List.of(record), List.of());
        when(deliveries.selectById(4L)).thenReturn(record);
        when(configs.selectById(1L)).thenReturn(hook());
        service.redeliverFailedDeliveries();
        assertThat(record.getAttempts()).isEqualTo(2);
        assertThat(record.getNextAttemptAt()).isNull();
        assertThat(record.getLastError()).contains("停止重投");
        verify(executor, never()).execute(any());
    }

    @Test
    void exhaustedBudgetDoesNotSendAgainEvenWithStaleRetryTimestamp() {
        WebhookDeliveryMapper deliveries = mock(WebhookDeliveryMapper.class);
        WebhookService service = spy(new WebhookService(mock(WebhookConfigMapper.class), deliveries, new ObjectMapper()));
        WebhookDelivery record = new WebhookDelivery();
        record.setId(8L);
        record.setStatus("FAILED");
        record.setAttempts(3);
        record.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        when(deliveries.selectById(8L)).thenReturn(record);
        ReflectionTestUtils.invokeMethod(service, "sendWebhook", hook(), "test", Map.of(), 8L);
        verify(service, never()).clientFor(any(), anyInt());
        assertThat(record.getAttempts()).isEqualTo(3);
        assertThat(record.getNextAttemptAt()).isNull();
    }

    @Test
    void requestErrorsNeverPersistUrlTokens() {
        WebhookService service = new WebhookService(mock(WebhookConfigMapper.class), mock(WebhookDeliveryMapper.class), new ObjectMapper());
        String error = ReflectionTestUtils.invokeMethod(service, "safeRequestError",
                new java.io.IOException("failed https://receiver.example/?token=private-token"));
        assertThat(error).contains("IOException").doesNotContain("private-token", "receiver.example");
    }
}
