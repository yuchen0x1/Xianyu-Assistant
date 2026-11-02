package com.feijimiao.xianyuassistant.notify.impl;

import com.feijimiao.xianyuassistant.notify.NotifyChannel;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.Notifier;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ntfy 通知（pub/sub 推送，支持自托管/公共 ntfy.sh）。
 *
 * <p>向 {@code https://ntfy.sh/{topic}} 发送纯文本 POST；标题走 {@code Title} 头，
 * 优先级走 {@code Priority} 头（1-5）。可选 Bearer Token 鉴权。</p>
 */
@Slf4j
@Component
public class NtfyNotifier implements Notifier {

    private static final String KEY_NTFY_TOPIC = "notify_ntfy_topic";
    private static final String KEY_NTFY_SERVER = "notify_ntfy_server";
    private static final String KEY_NTFY_TOKEN = "notify_ntfy_token";

    @Autowired
    private SysSettingService sysSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean send(NotifyMessage message) {
        String topic = getTopic();
        if (topic.isEmpty()) {
            log.warn("[ntfy] ntfy主题未配置，跳过通知");
            return false;
        }

        try {
            String baseUrl = getServerUrl();
            String url = baseUrl + "/" + URLEncoder.encode(topic, StandardCharsets.UTF_8);
            int priority = switch (message.getLevel()) {
                case ERROR -> 5;
                case WARNING -> 3;
                case INFO -> 1;
            };

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Title", encodeHeader(message.getTitle()))
                    .header("Priority", String.valueOf(priority))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            message.getContent(), StandardCharsets.UTF_8));

            String token = getToken();
            if (!token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            if (success) {
                log.info("[ntfy] ntfy通知发送成功: title={}", message.getTitle());
            } else {
                log.warn("[ntfy] ntfy通知发送失败: status={}, body={}", response.statusCode(), response.body());
            }
            return success;
        } catch (Exception e) {
            log.error("[ntfy] ntfy通知发送异常: title={}", message.getTitle(), e);
            return false;
        }
    }

    @Override
    public NotifyChannel getChannel() {
        return NotifyChannel.NTFY;
    }

    @Override
    public boolean isConfigured() {
        return !getTopic().isEmpty();
    }

    private String getTopic() {
        String value = sysSettingService.getSettingValue(KEY_NTFY_TOPIC);
        return value != null ? value.trim() : "";
    }

    private String getServerUrl() {
        String value = sysSettingService.getSettingValue(KEY_NTFY_SERVER);
        if (value != null && !value.isBlank()) {
            return value.trim().replaceAll("/+$", "");
        }
        return "https://ntfy.sh";
    }

    private String getToken() {
        String value = sysSettingService.getSettingValue(KEY_NTFY_TOKEN);
        return value != null ? value.trim() : "";
    }

    /** 标题头不能带换行，需清理。 */
    private String encodeHeader(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n]", " ");
    }
}
