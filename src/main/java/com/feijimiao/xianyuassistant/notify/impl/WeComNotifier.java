package com.feijimiao.xianyuassistant.notify.impl;

import com.feijimiao.xianyuassistant.notify.NotifyChannel;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.Notifier;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 企业微信机器人通知
 * 使用 Webhook URL 发送 Markdown 消息
 */
@Slf4j
@Component
public class WeComNotifier implements Notifier {

    private static final String KEY_WECOM_WEBHOOK = "notify_wecom_webhook";

    @Autowired
    private SysSettingService sysSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean send(NotifyMessage message) {
        String webhookUrl = getWebhookUrl();
        if (webhookUrl.isEmpty()) {
            log.warn("[WeCom] 企业微信Webhook未配置，跳过通知");
            return false;
        }

        try {
            String color = switch (message.getLevel()) {
                case ERROR -> "warning";
                case WARNING -> "comment";
                case INFO -> "info";
            };

            String markdown = String.format(
                    "## %s\n> %s\n> 级别：<font color=\"%s\">%s</font>",
                    message.getTitle(),
                    message.getContent().replace("\n", "\n> "),
                    color,
                    message.getLevel().name()
            );

            String payload = String.format(
                    "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"%s\"}}",
                    escapeJson(markdown)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            if (success) {
                log.info("[WeCom] 企业微信通知发送成功: title={}", message.getTitle());
            } else {
                log.warn("[WeCom] 企业微信通知发送失败: status={}, body={}", response.statusCode(), response.body());
            }
            return success;
        } catch (Exception e) {
            log.error("[WeCom] 企业微信通知发送异常: title={}", message.getTitle(), e);
            return false;
        }
    }

    @Override
    public NotifyChannel getChannel() {
        return NotifyChannel.WECOM;
    }

    @Override
    public boolean isConfigured() {
        return !getWebhookUrl().isEmpty();
    }

    private String getWebhookUrl() {
        String value = sysSettingService.getSettingValue(KEY_WECOM_WEBHOOK);
        return value != null ? value.trim() : "";
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
