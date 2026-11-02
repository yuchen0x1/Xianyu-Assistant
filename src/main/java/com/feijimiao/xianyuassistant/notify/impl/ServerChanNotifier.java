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
 * Server酱通知
 * 使用 SendKey 发送消息
 */
@Slf4j
@Component
public class ServerChanNotifier implements Notifier {

    private static final String KEY_SERVERCHAN_SENDKEY = "notify_serverchan_sendkey";

    @Autowired
    private SysSettingService sysSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean send(NotifyMessage message) {
        String sendKey = getSendKey();
        if (sendKey.isEmpty()) {
            log.warn("[ServerChan] Server酱SendKey未配置，跳过通知");
            return false;
        }

        try {
            String url = "https://sctapi.ftqq.com/" + sendKey + ".send";

            String body = "title=" + URLEncoder.encode(message.getTitle(), StandardCharsets.UTF_8)
                    + "&desp=" + URLEncoder.encode(buildDesp(message), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            if (success) {
                log.info("[ServerChan] Server酱通知发送成功: title={}", message.getTitle());
            } else {
                log.warn("[ServerChan] Server酱通知发送失败: status={}", response.statusCode());
            }
            return success;
        } catch (Exception e) {
            log.error("[ServerChan] Server酱通知发送异常: title={}", message.getTitle(), e);
            return false;
        }
    }

    @Override
    public NotifyChannel getChannel() {
        return NotifyChannel.SERVERCHAN;
    }

    @Override
    public boolean isConfigured() {
        return !getSendKey().isEmpty();
    }

    private String getSendKey() {
        String value = sysSettingService.getSettingValue(KEY_SERVERCHAN_SENDKEY);
        return value != null ? value.trim() : "";
    }

    private String buildDesp(NotifyMessage message) {
        String levelTag = switch (message.getLevel()) {
            case ERROR -> "🔴";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };
        return levelTag + " 级别：" + message.getLevel().name() + "\n\n" + message.getContent();
    }
}
