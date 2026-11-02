package com.feijimiao.xianyuassistant.notify.impl;

import com.feijimiao.xianyuassistant.notify.NotifyChannel;
import com.feijimiao.xianyuassistant.notify.NotifyMessage;
import com.feijimiao.xianyuassistant.notify.Notifier;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Telegram Bot 通知。
 *
 * <p>调用 {@code https://api.telegram.org/bot{token}/sendMessage}，
 * 需配置 Bot Token 与 Chat ID。</p>
 */
@Slf4j
@Component
public class TelegramNotifier implements Notifier {

    private static final String KEY_TELEGRAM_BOT_TOKEN = "notify_telegram_bot_token";
    private static final String KEY_TELEGRAM_CHAT_ID = "notify_telegram_chat_id";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private SysSettingService sysSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public boolean send(NotifyMessage message) {
        String botToken = getBotToken();
        String chatId = getChatId();
        if (botToken.isEmpty() || chatId.isEmpty()) {
            log.warn("[Telegram] Telegram Bot Token 或 Chat ID 未配置，跳过通知");
            return false;
        }

        try {
            String levelPrefix = switch (message.getLevel()) {
                case ERROR -> "🔴 ";
                case WARNING -> "⚠️ ";
                case INFO -> "ℹ️ ";
            };
            String text = levelPrefix + message.getTitle() + "\n\n" + message.getContent();

            // Jackson 序列化：手写 escapeJson 不处理 <0x20 控制字符，买家消息含控制字符时 payload 非法
            String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "text", text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() == 200;
            if (success) {
                log.info("[Telegram] Telegram通知发送成功: title={}", message.getTitle());
            } else {
                log.warn("[Telegram] Telegram通知发送失败: status={}, body={}", response.statusCode(), response.body());
            }
            return success;
        } catch (Exception e) {
            log.error("[Telegram] Telegram通知发送异常: title={}", message.getTitle(), e);
            return false;
        }
    }

    @Override
    public NotifyChannel getChannel() {
        return NotifyChannel.TELEGRAM;
    }

    @Override
    public boolean isConfigured() {
        return !getBotToken().isEmpty() && !getChatId().isEmpty();
    }

    private String getBotToken() {
        String value = sysSettingService.getSettingValue(KEY_TELEGRAM_BOT_TOKEN);
        return value != null ? value.trim() : "";
    }

    private String getChatId() {
        String value = sysSettingService.getSettingValue(KEY_TELEGRAM_CHAT_ID);
        return value != null ? value.trim() : "";
    }
}
