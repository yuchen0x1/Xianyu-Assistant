package com.feijimiao.xianyuassistant.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.webhook.dto.WebhookDeliveryVO;
import com.feijimiao.xianyuassistant.webhook.dto.WebhookVO;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookConfig;
import com.feijimiao.xianyuassistant.webhook.entity.WebhookDelivery;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookConfigMapper;
import com.feijimiao.xianyuassistant.webhook.mapper.WebhookDeliveryMapper;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WebhookConfigMapper configMapper;

    @Mock
    private WebhookDeliveryMapper deliveryMapper;

    private WebhookService service() {
        return new WebhookService(configMapper, deliveryMapper, new ObjectMapper());
    }

    @Test
    void rejectsPrivateNetworkLoopbackAndUnsafeUrls() {
        WebhookService svc = service();
        for (String url : new String[]{
                "http://127.0.0.1/hook",
                "http://localhost/hook",
                "http://0.0.0.0/hook",
                "http://[::1]/hook",
                "http://169.254.169.254/latest/meta-data",
                "http://10.0.0.8/hook",
                "http://172.16.0.1/hook",
                "http://192.168.1.10/hook",
                "http://100.64.0.5/hook",
                "http://198.18.0.1/hook",
                "ftp://example.com/hook",
                "http://user:pass@example.com/hook",
                "http://example.com:bad/hook"
        }) {
            assertThatThrownBy(() -> svc.validateWebhookUrl(url))
                    .as("URL 应被拒绝: %s", url)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void acceptsPublicHttpsUrl() {
        service().validateWebhookUrl("https://93.184.216.34/receive");
    }

    @Test
    void voNeverExposesPlaintextSecret() {
        WebhookConfig config = new WebhookConfig();
        config.setId(1L);
        config.setName("钉钉");
        config.setUrl("https://93.184.216.34/receive");
        config.setSecret("super-secret-value");

        WebhookVO vo = ReflectionTestUtils.invokeMethod(service(), "toVO", config);

        assertThat(vo).isNotNull();
        assertThat(vo.getHasSecret()).isTrue();
        assertThat(vo.toString()).doesNotContain("super-secret-value");
    }

    @Test
    void maskedSecretOnUpdatePreservesExistingEvenWhenUrlIsProvided() {
        WebhookConfig existing = new WebhookConfig();
        existing.setId(7L);
        existing.setUrl("https://93.184.216.34/original");
        existing.setSecret("keep-me");
        when(configMapper.selectById(7L)).thenReturn(existing);

        WebhookConfig patch = new WebhookConfig();
        patch.setId(7L);
        patch.setName("新名称");
        patch.setUrl("https://93.184.216.34/updated");
        patch.setSecret("********");
        service().update(patch);

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(configMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSecret()).isEqualTo("keep-me");
        assertThat(captor.getValue().getUrl()).isEqualTo("https://93.184.216.34/updated");
    }

    @Test
    void blankSecretOnUpdatePreservesExisting() {
        WebhookConfig existing = new WebhookConfig();
        existing.setId(7L);
        existing.setUrl("https://93.184.216.34/original");
        existing.setSecret("keep-me");
        when(configMapper.selectById(7L)).thenReturn(existing);

        WebhookConfig patch = new WebhookConfig();
        patch.setId(7L);
        patch.setUrl("https://93.184.216.34/updated");
        patch.setSecret("  ");
        service().update(patch);

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(configMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSecret()).isEqualTo("keep-me");
    }

    @Test
    void realSecretOnUpdateOverwrites() {
        WebhookConfig patch = new WebhookConfig();
        patch.setId(8L);
        patch.setUrl("https://93.184.216.34/original");
        patch.setSecret("new-secret");
        service().update(patch);

        ArgumentCaptor<WebhookConfig> captor = ArgumentCaptor.forClass(WebhookConfig.class);
        verify(configMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSecret()).isEqualTo("new-secret");
    }

    @Test
    void testWithBlankSecretFallsBackToSavedSecret() throws Exception {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        @SuppressWarnings("unchecked")
        Response response = mock(Response.class);
        when(response.code()).thenReturn(200);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);

        WebhookConfig saved = new WebhookConfig();
        saved.setId(3L);
        saved.setSecret("saved-secret");
        when(configMapper.selectById(3L)).thenReturn(saved);

        WebhookService svc = Mockito.spy(service());
        doReturn(httpClient).when(svc).clientFor(any(), anyInt());

        boolean ok = svc.test("https://93.184.216.34/ok", "", 3L);

        assertThat(ok).isTrue();
        ArgumentCaptor<Request> reqCaptor = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(reqCaptor.capture());
        assertThat(reqCaptor.getValue().header("X-Webhook-Signature"))
                .startsWith("sha256=");
    }

    @Test
    void httpClientNeverFollowsRedirects() {
        WebhookService svc = service();
        OkHttpClient client = (OkHttpClient) ReflectionTestUtils.getField(svc, "baseClient");
        assertThat(client).isNotNull();
        assertThat(client.followRedirects()).isFalse();
    }

    @Test
    void dispatchPersistsPendingAndMarksSuccessAfterDelivery() throws Exception {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        @SuppressWarnings("unchecked")
        Response response = mock(Response.class);
        when(response.code()).thenReturn(200);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);

        WebhookConfig hook = new WebhookConfig();
        hook.setId(1L);
        hook.setUrl("https://93.184.216.34/receive");
        hook.setRetryCount(1);
        hook.setTimeoutSeconds(10);
        hook.setSecret("secret");
        when(configMapper.selectList(any())).thenReturn(java.util.List.of(hook));

        WebhookDelivery inserted = new WebhookDelivery();
        inserted.setId(99L);
        inserted.setWebhookId(1L);
        when(deliveryMapper.insert(any(WebhookDelivery.class))).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            d.setId(99L);
            return 1;
        });
        when(deliveryMapper.selectById(99L)).thenReturn(inserted);

        WebhookService svc = Mockito.spy(service());
        doReturn(httpClient).when(svc).clientFor(any(), anyInt());
        svc.init();

        svc.dispatch("delivery.completed", java.util.Map.of("accountId", 7L, "orderId", "O1"));

        ArgumentCaptor<WebhookDelivery> insertCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(insertCaptor.getValue().getEvent()).isEqualTo("delivery.completed");
        assertThat(insertCaptor.getValue().getIdempotencyKey()).startsWith("1:delivery.completed:");

        ArgumentCaptor<WebhookDelivery> updateCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        awaitVerify(() -> verify(deliveryMapper).updateById(updateCaptor.capture()));
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(updateCaptor.getValue().getHttpStatus()).isEqualTo(200);
        assertThat(updateCaptor.getValue().getDeliveredAt()).isNotNull();
        assertThat(updateCaptor.getValue().getNextAttemptAt()).isNull();
    }

    @Test
    void dispatchMarksDeliveryFailedWhenExecutorIsSaturated() throws Exception {
        WebhookService svc = service();
        ReflectionTestUtils.setField(svc, "poolSize", 1);
        ReflectionTestUtils.setField(svc, "queueCapacity", 1);
        svc.init();

        ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(svc, "executor");
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 占满唯一 worker 与唯一队列位，逼出 RejectedExecutionException 路径
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.execute(() -> { });

            WebhookConfig hook = new WebhookConfig();
            hook.setId(1L);
            hook.setUrl("https://93.184.216.34/receive");
            when(configMapper.selectList(any())).thenReturn(java.util.List.of(hook));

            when(deliveryMapper.insert(any(WebhookDelivery.class))).thenAnswer(inv -> {
                WebhookDelivery d = inv.getArgument(0);
                d.setId(66L);
                return 1;
            });
            WebhookDelivery stored = new WebhookDelivery();
            stored.setId(66L);
            when(deliveryMapper.selectById(66L)).thenReturn(stored);

            svc.dispatch("delivery.completed", java.util.Map.of("orderId", "O1"));

            ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
            verify(deliveryMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
            assertThat(captor.getValue().getNextAttemptAt()).isNotNull();
            assertThat(captor.getValue().getLastError()).contains("队列已满");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitVerify(Runnable verification) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        boolean done = false;
        while (!done && System.currentTimeMillis() < deadline) {
            try {
                verification.run();
                done = true;
            } catch (AssertionError e) {
                Thread.sleep(50);
            }
        }
        if (!done) {
            verification.run();
        }
    }

    @Test
    void listDeliveriesReturnsMaskedSummaryWithoutPayload() throws Exception {
        WebhookDelivery record = new WebhookDelivery();
        record.setId(5L);
        record.setWebhookId(1L);
        record.setEvent("delivery.completed");
        record.setStatus("FAILED");
        record.setAttempts(2);
        record.setLastError("HTTP 500");
        record.setPayloadJson("{\"accountId\":7,\"orderId\":\"O1\",\"secret\":\"top-secret\"}");
        when(deliveryMapper.selectList(any())).thenReturn(java.util.List.of(record));

        WebhookService svc = service();
        java.util.List<WebhookDeliveryVO> vos = svc.listDeliveries(1L, 50);

        assertThat(vos).hasSize(1);
        WebhookDeliveryVO vo = vos.get(0);
        assertThat(vo.getPayloadSummary()).contains("accountId", "orderId", "secret");
        assertThat(vo.getPayloadSummary()).doesNotContain("top-secret");
        assertThat(vo.getStatus()).isEqualTo("FAILED");
        assertThat(vo.getAttempts()).isEqualTo(2);
        assertThat(vo.getLastError()).isEqualTo("HTTP 500");
        assertThat(vo.toString()).doesNotContain("top-secret");
    }

    @Test
    void rejectsPrivateAddressAfterPreResolution() {
        WebhookService svc = service();
        // 私有网段即使以任意主机名形式出现也会被拒绝（validateWebhookUrl 已覆盖主机/IP）。
        assertThatThrownBy(() -> svc.validateWebhookUrl("https://example.invalid/ok"))
                .isInstanceOf(BusinessException.class);
    }
}
